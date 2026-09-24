package pulse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import pulse.analytics.Analytics
import pulse.apm.Apm
import pulse.apm.CrashReporter
import pulse.apm.installUncaughtExceptionReporter
import pulse.core.Identity
import pulse.core.PulseClient
import pulse.core.PulseConfig
import pulse.core.InMemoryEventOutbox
import pulse.core.SqlDelightEventOutbox
import pulse.core.defaultPulseStorageDir
import pulse.core.UpdateAction
import pulse.core.UpdateInfo
import pulse.core.persistentDeviceId
import pulse.core.persistentInstallationId
import pulse.runtime.Runtime
import pulse.transport.MsgTransEventSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Batteries-included SDK entry. Wires the core pipeline, the analytics/apm/runtime surfaces and the
 * msgtrans ingest transport. Apps that want link-time stripping can instead build a [PulseClient]
 * with only the capabilities they need; this umbrella is the convenience path.
 *
 * Outward-neutral by design (Pulse / analytics / apm / runtime), matching the naming convention in
 * the architecture doc — nothing here reads as security/audit tooling.
 */
class Pulse private constructor(
    val client: PulseClient,
    val analytics: Analytics,
    val apm: Apm,
    /** Non-null only when [PulseConfig.runtime] is on, so the capability can be stripped. */
    val runtime: Runtime?,
) {
    private var updateValue = UpdateInfo()

    /**
     * The startup update check's answer. [UpdateAction.None] when no platform was configured, when
     * the build is current, or when the check could not be completed — the host can treat this as
     * "there is nothing to do" in every one of those cases.
     */
    val update: UpdateInfo get() = updateValue
    private val updateReady = CompletableDeferred<UpdateInfo>()
    private var connectionJob: Job? = null

    /** 启动只准备本地采集；需要更新检查结果的调用方显式等待，超时放行。 */
    suspend fun awaitUpdate(timeoutMillis: Long = 5_000): UpdateInfo =
        withTimeoutOrNull(timeoutMillis) { updateReady.await() } ?: UpdateInfo()

    fun identify(userId: String) = analytics.identify(userId)
    fun track(name: String, attributes: Map<String, Any?> = emptyMap()) = analytics.track(name, attributes)
    suspend fun stop() {
        connectionJob?.cancelAndJoin()
        updateReady.complete(UpdateInfo())
        client.close()
    }

    companion object {

        /**
         * Start the SDK: recover any crash from the previous run, open the ingest connection, begin
         * the flush pipeline, emit app_launch.
         *
         * The order matters. Pending crashes are drained *before* the crash handler is armed,
         * because arming truncates the record file this run will own — draining afterwards would
         * throw away the report we came for.
         */
        suspend fun start(scope: CoroutineScope, config: PulseConfig): Pulse {
            val identity = Identity(
                config.projectId,
                // Persisted, so a device stays one device across launches. Regenerating these per
                // start silently inflates DAU and makes crash-per-device rates meaningless.
                installationId = persistentInstallationId(),
                deviceId = persistentDeviceId(),
            )
            val dir = storageDirFor(config)
            // Telemetry must never prevent the host from starting. Disk-full/corrupt-database
            // failures fall back to the bounded in-memory outbox for this run.
            val outbox = runCatching {
                SqlDelightEventOutbox(
                    dir,
                    maxBatches = config.outboxMaxBatches,
                    maxBytes = config.outboxMaxBytes,
                    maxAgeMs = config.outboxMaxAgeMs,
                )
            }.getOrElse {
                InMemoryEventOutbox(
                    config.outboxMaxBatches.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                    config.outboxMaxAgeMs.coerceAtLeast(1),
                )
            }
            val client = PulseClient(config, identity, scope, outbox = outbox)
            val apm = Apm(client)
            val runtime = if (config.runtime) Runtime(client) else null

            val pulse = Pulse(client, Analytics(client), apm, runtime)

            if (config.apm) {
                // Report last run's crash first, then take ownership of the record file.
                for (crash in CrashReporter.drainPending(dir)) {
                    apm.recordCrash(
                        crash.name,
                        crash.message,
                        stack = null,
                        attributes = mapOf(
                            "crashed_session_id" to crash.sessionId,
                            "crashed_at_ms" to crash.timestampMs,
                            // The slide travels with the frames: without it the server cannot
                            // turn these runtime addresses back into anything a symbol table
                            // can resolve.
                            "image_slide" to crash.imageSlide,
                            "frames" to crash.frames.joinToString(",") { it.toString() },
                        ),
                    )
                }
                CrashReporter.install(dir, client.currentSession.id)
                // Signals say the process died; this says what threw. Both write a record for the
                // next launch to upload, so neither depends on the network at the worst moment.
                installUncaughtExceptionReporter(dir, client.currentSession.id)
            }

            if (config.analytics) { pulse.analytics.sessionStart(); pulse.analytics.appLaunch() }
            // The baseline is taken after the session exists so the inventory joins this session.
            runtime?.captureBaseline()
            // 先启用本地落盘和崩溃处理，再异步连接；首次断网不阻塞宿主事件消费。
            client.start()
            pulse.connectionJob = scope.launch {
                var connectDelay = config.retryBaseDelayMs.coerceAtLeast(100)
                var sink: MsgTransEventSink? = null
                while (sink == null) {
                    try {
                        sink = withTimeout(15_000) {
                            MsgTransEventSink.connect(scope, config, identity) { kinds ->
                                client.applyCollectionPolicy(kinds)
                            }
                        }
                    } catch (t: Throwable) {
                        if (!isActive) throw t
                        delay(connectDelay)
                        connectDelay = (connectDelay * 2)
                            .coerceAtMost(config.retryMaxDelayMs.coerceAtLeast(100))
                    }
                }
                val eventSink = checkNotNull(sink)
                client.attachSink(eventSink)
                // Asked before the first events go out, so the host has the answer as early as it can
                // possibly act on it — a forced update should gate the UI, not arrive after it.
                pulse.updateValue = withTimeoutOrNull(5_000) { client.checkForUpdate() } ?: UpdateInfo()
                pulse.updateReady.complete(pulse.updateValue)
                while (isActive) {
                    delay(eventSink.heartbeatIntervalMs)
                    runCatching { eventSink.heartbeat() }
                }
            }
            return pulse
        }

        private fun storageDirFor(config: PulseConfig): String =
            config.storageDir ?: defaultPulseStorageDir(config.projectId)
    }
}
