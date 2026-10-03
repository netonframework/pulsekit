package pulse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import neton.io.net.runReactor
import pulse.core.PulseConfig
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The host-app entry point: Objective-C facing on iOS, behind the Java facade on Android.
 *
 * [Pulse.start] takes a CoroutineScope and suspends, which is the right shape for Kotlin callers
 * and a bad one for a host app: an iOS app has no coroutine scope to hand over, and the transport
 * needs a neton-io reactor, which is a loop that owns its thread and must never be the main one.
 *
 * So this owns a background thread running that reactor, and everything the host calls is a plain
 * non-suspending method that posts a command onto it. Posting is the only cross-thread operation;
 * the SDK's own state stays single-threaded on the reactor, which is the discipline the rest of
 * the stack is built on.
 *
 * The host app's Kotlin version does not have to match this SDK's — the boundary here is the
 * framework's Objective-C interface, not a klib.
 */
@OptIn(ExperimentalAtomicApi::class)
object PulseSDK {
    // Named PulseSDK, not PulseKit: Swift drops the framework prefix from exported classes, so an
    // object called PulseKit inside a framework called PulseKit collides with the module name at
    // the call site.


    private sealed class Command {
        class Track(val name: String, val attributes: Map<String, String>) : Command()
        class Identify(val userId: String) : Command()
        class Error(val name: String, val message: String, val stack: String?) : Command()
        class Performance(val name: String, val durationMs: Long) : Command()
        class Behavior(val name: String, val module: String?) : Command()
        class Flush(val done: CompletableDeferred<Unit>?) : Command()
        class Stop(val done: CompletableDeferred<Unit>) : Command()
    }

    /**
     * Created fresh by each [start] and closed by [stop]. A single long-lived channel leaked
     * commands across sessions: a Stop left unconsumed by a session that never finished connecting
     * was picked up by the *next* session's loop and shut it down immediately, so its events were
     * silently dropped.
     *
     * Bounded and dropping the oldest, matching the event buffer's own policy: if the server is
     * unreachable and the host keeps calling track(), the queue must not grow without limit.
     */
    private var commands: Channel<Command>? = null
    /** Ends the SDK thread (see [startSdkThread]); null while the SDK is not running. */
    private var stopThread: (() -> Unit)? = null
    private var lifecycleFlushObserver: AppLifecycleFlushObserver? = null

    /**
     * The startup update check's answer, published once the SDK has connected and asked.
     *
     * The host reads this to decide whether to gate its UI, so it is written by the reactor thread
     * and read by the main thread. An AtomicReference rather than a plain var: this is the one
     * value that genuinely crosses threads, and a torn read here would mean a forced update the
     * host never sees.
     */
    private val updateState = AtomicReference<AppUpdate?>(null)

    /**
     * The update check's answer, or null while it is still in flight.
     *
     * A host that must gate on a forced update should wait for this with [awaitUpdateInfo] rather
     * than reading it once at launch, since the connection takes a moment to establish.
     */
    val updateInfo: AppUpdate? get() = updateState.load()

    private const val COMMAND_QUEUE_CAPACITY = 512

    /**
     * How often runtime observations are gathered. Half a minute: soon enough that a launch-time
     * clipboard read reaches the server within the same session, rare enough that the polling
     * itself is not the thing draining the battery.
     */
    private const val RUNTIME_POLL_MS = 30_000L

    /** True once [start] has spun up the reactor thread. */
    val isRunning: Boolean get() = stopThread != null

    /**
     * Start the SDK on its own thread. Returns immediately; the connection is established in the
     * background and events emitted before it is up are buffered, not lost.
     */
    fun start(config: PulseConfig) {
        if (stopThread != null) return

        // Hooks go in first, synchronously, before anything else in start() can block.
        //
        // They used to be armed on the reactor thread after Pulse.start had completed, which
        // includes opening a connection and waiting for the update check. That is a network round
        // trip's worth of launch during which nothing is being watched — and launch is exactly
        // when an advertising or analytics SDK reads the identifiers it came for. Installing is
        // local and allocation-light, so there is no reason for it to wait behind I/O.
        if (config.runtime) armRuntimeMonitor()
        val queue = Channel<Command>(COMMAND_QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)
        commands = queue
        lifecycleFlushObserver = AppLifecycleFlushObserver { flush() }
        val cfg = config
        val channel = queue
        stopThread = startSdkThread("pulsekit") {
            runReactor {
                val pulse = Pulse.start(this, cfg)
                val stopped = CompletableDeferred<CompletableDeferred<Unit>>()
                var autoInstrumentation: AutoInstrumentation? = null
                launch { updateState.store(pulse.awaitUpdate().toAppUpdate()) }

                // What the SDK measures on its own. Without this an app only reports the numbers
                // its developers remembered to call recordPerformance() for, which in practice
                // means quality monitoring covers whatever someone instrumented by hand and is
                // silent about launches, freezes and memory.
                if (cfg.apm) {
                    val auto = AutoInstrumentation(pulse, this)
                    auto.install()
                    autoInstrumentation = auto
                }

                // Runtime observation runs on a timer rather than per event. Two things are being
                // polled: images loaded after launch, which the baseline by definition cannot
                // show, and sensitive API calls, which are collapsed per (api, caller) so a
                // callback firing continuously costs one event per tick instead of thousands.
                val runtime = pulse.runtime
                if (runtime != null) {
                    // Already armed above; this reports what ended up being watched and what the
                    // final build declares, which needs the client that now exists.
                    runtime.reportRuntimeDeclarations()
                    launch {
                        while (true) {
                            kotlinx.coroutines.delay(RUNTIME_POLL_MS)
                            runtime.pollRuntimeObservations()
                        }
                    }
                }
                launch {
                    for (command in channel) {
                        when (command) {
                            is Command.Track -> pulse.track(command.name, command.attributes)
                            is Command.Identify -> pulse.identify(command.userId)
                            is Command.Error ->
                                pulse.apm.recordError(command.name, command.message, command.stack)
                            is Command.Performance ->
                                pulse.apm.recordPerformance(command.name, command.durationMs)
                            is Command.Behavior ->
                                pulse.runtime?.recordBehavior(command.name, command.module)
                            is Command.Flush -> {
                                pulse.client.flushOnce()
                                command.done?.complete(Unit)
                            }
                            is Command.Stop -> {
                                stopped.complete(command.done)
                                return@launch
                            }
                        }
                    }
                }
                // Keep the reactor alive until stop() cancels it; without this runReactor would
                // return as soon as start() finished and take the connection with it.
                val done = stopped.await()
                try {
                    // 生命周期、定时器与客户端都由同一个 reactor 关闭，避免主线程竞争。
                    autoInstrumentation?.close()
                    coroutineContext.cancelChildren()
                    pulse.stop()
                } finally {
                    done.complete(Unit)
                }
            }
        }
    }

    /**
     * Start with the App ID created in the Pulse console and the ingest server's URL, e.g.
     * `startWithAppId("pulse_xxx", "tcp://collect.example.com:6000")`. Runtime observation is on,
     * and the app's package, build number and version are read from the platform.
     *
     * The App ID identifies the event stream; it is not a secret.
     */
    fun startWithAppId(appId: String, endpoint: String) =
        startWithAppId(appId, endpoint, runtime = true, packageName = null, buildNumber = 0L, appVersion = null)

    /**
     * The full form, without Kotlin default arguments: they do not survive into the Objective-C
     * interface, so a Swift caller would otherwise have to pass every parameter of a defaulted one.
     *
     * [packageName] null, [buildNumber] 0 and [appVersion] null mean "this app's own", as the
     * platform reports it. A malformed [endpoint] does not throw: telemetry must never be the
     * reason an app fails to start, so the SDK logs why and stays stopped.
     */
    fun startWithAppId(
        appId: String,
        endpoint: String,
        runtime: Boolean,
        packageName: String?,
        buildNumber: Long,
        appVersion: String?,
    ) {
        val own = hostBuild()
        val config = try {
            PulseConfig(
                projectId = appId, endpoint = endpoint, runtime = runtime,
                // Fixed per platform ("ios" / "android"): the facade knows which one it was built
                // for, so asking the host to pass its own platform would only create a way to get
                // it wrong.
                platform = hostPlatform, deviceType = currentDeviceType(),
                packageName = packageName ?: own.packageName,
                buildNumber = if (buildNumber != 0L) buildNumber else own.buildNumber,
                appVersion = appVersion ?: own.appVersion,
            )
        } catch (e: IllegalArgumentException) {
            println("PulseKit: not started: ${e.message}")
            return
        }
        start(config)
    }

    /**
     * Wait up to [timeoutMillis] for the startup update check to come back.
     *
     * Returns [UpdateAction.None] on timeout, not null: a host calling this is about to decide
     * whether to show its UI, and "we could not ask" has to mean "let them in". A telemetry SDK
     * that can strand users behind a spinner is worse than a missed update prompt.
     */
    fun awaitUpdateInfo(timeoutMillis: Long = 5_000): AppUpdate {
        val deadline = pulse.core.nowMillis() + timeoutMillis
        while (updateState.load() == null) {
            if (pulse.core.nowMillis() > deadline) return noUpdate()
            sleepMillis(5)
        }
        return updateState.load() ?: noUpdate()
    }

    private fun noUpdate() = AppUpdate(AppUpdateAction.None, null, 0L, "", null)

    /** A business event. Attributes are strings so the Objective-C signature stays predictable. */
    fun track(name: String, attributes: Map<String, String> = emptyMap()) {
        commands?.trySend(Command.Track(name, attributes))
    }

    fun identify(userId: String) {
        commands?.trySend(Command.Identify(userId))
    }

    fun recordError(name: String, message: String, stack: String? = null) {
        commands?.trySend(Command.Error(name, message, stack))
    }

    fun recordPerformance(name: String, durationMs: Long) {
        commands?.trySend(Command.Performance(name, durationMs))
    }

    /** A runtime behaviour the host attributes to a module; ignored unless runtime is enabled. */
    fun recordBehavior(name: String, module: String? = null) {
        commands?.trySend(Command.Behavior(name, module))
    }

    /**
     * Send whatever is buffered right now and wait for it. Meant for the moments an app knows it
     * may be about to go away — entering the background, or a host-detected fatal path.
     */
    fun flushAndWait(timeoutMillis: Long = 3_000) {
        val queue = commands ?: return
        val done = CompletableDeferred<Unit>()
        if (queue.trySend(Command.Flush(done)).isFailure) return
        awaitCompletion(done, timeoutMillis)
    }

    /**
     * Schedule a FIFO flush without blocking the caller. iOS invokes this automatically when the
     * app enters the background; hosts may also call it at other lifecycle boundaries.
     */
    fun flush() {
        commands?.trySend(Command.Flush(done = null))
    }

    /** Flush, close the connection and stop the reactor thread. */
    fun stop(timeoutMillis: Long = 3_000) {
        val requestStop = stopThread ?: return
        lifecycleFlushObserver?.close()
        lifecycleFlushObserver = null
        val queue = commands
        if (queue != null) {
            val done = CompletableDeferred<Unit>()
            if (queue.trySend(Command.Stop(done)).isSuccess) awaitCompletion(done, timeoutMillis)
            // Closed and dropped whether or not the loop got to it: a session that never finished
            // connecting must not hand its leftovers to the next one.
            queue.close()
        }
        commands = null
        updateState.store(null)
        requestStop()
        stopThread = null
    }

    /**
     * Block the calling thread until [done] settles or the deadline passes.
     *
     * A busy wait rather than runBlocking: the caller is an app thread with no dispatcher of ours,
     * and the work is happening on the reactor thread. The waits are short and bounded, and both
     * callers are explicitly "I am willing to block for a moment" operations.
     */
    private fun awaitCompletion(done: CompletableDeferred<Unit>, timeoutMillis: Long) {
        val deadline = pulse.core.nowMillis() + timeoutMillis
        while (!done.isCompleted) {
            if (pulse.core.nowMillis() > deadline) return
            sleepMillis(2)
        }
    }
}
