@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pulse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import neton.io.net.runReactor
import pulse.core.PulseConfig

/**
 * The Objective-C facing entry point, and the one an iOS host should use.
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
@OptIn(ObsoleteWorkersApi::class)
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
        class Flush(val done: CompletableDeferred<Unit>) : Command()
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
    private var worker: Worker? = null

    private const val COMMAND_QUEUE_CAPACITY = 512

    /** True once [start] has spun up the reactor thread. */
    val isRunning: Boolean get() = worker != null

    /**
     * Start the SDK on its own thread. Returns immediately; the connection is established in the
     * background and events emitted before it is up are buffered, not lost.
     */
    fun start(config: PulseConfig) {
        if (worker != null) return
        val queue = Channel<Command>(COMMAND_QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)
        commands = queue
        val w = Worker.start(name = "pulsekit")
        worker = w
        w.execute(TransferMode.SAFE, { config to queue }) { (cfg, channel) ->
            runReactor {
                val pulse = Pulse.start(this, cfg)
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
                                command.done.complete(Unit)
                            }
                            is Command.Stop -> {
                                pulse.stop()
                                command.done.complete(Unit)
                                return@launch
                            }
                        }
                    }
                }
                // Keep the reactor alive until stop() cancels it; without this runReactor would
                // return as soon as start() finished and take the connection with it.
                try {
                    awaitCancellation()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Convenience start for hosts that do not want to build a [PulseConfig] across the
     * Objective-C boundary, where a Kotlin data class with ten defaulted parameters turns into an
     * initialiser taking all ten.
     */
    fun start(projectId: String, host: String, port: Int = 9600, runtime: Boolean = true) =
        start(PulseConfig(projectId = projectId, host = host, port = port, runtime = runtime))

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

    /** Flush, close the connection and stop the reactor thread. */
    fun stop(timeoutMillis: Long = 3_000) {
        val w = worker ?: return
        val queue = commands
        if (queue != null) {
            val done = CompletableDeferred<Unit>()
            if (queue.trySend(Command.Stop(done)).isSuccess) awaitCompletion(done, timeoutMillis)
            // Closed and dropped whether or not the loop got to it: a session that never finished
            // connecting must not hand its leftovers to the next one.
            queue.close()
        }
        commands = null
        w.requestTermination(processScheduledJobs = false)
        worker = null
    }

    /**
     * Block the calling thread until [done] settles or the deadline passes.
     *
     * A busy wait rather than runBlocking: the caller is an app thread with no dispatcher of ours,
     * and the work is happening on the reactor thread. The waits are short and bounded, and both
     * callers are explicitly "I am willing to block for a moment" operations.
     */
    private fun awaitCompletion(done: CompletableDeferred<Unit>, timeoutMillis: Long) {
        val deadline = platform.posix.time(null).toLong() * 1000L + timeoutMillis
        while (!done.isCompleted) {
            if (platform.posix.time(null).toLong() * 1000L > deadline) return
            platform.posix.usleep(2_000u)
        }
    }
}
