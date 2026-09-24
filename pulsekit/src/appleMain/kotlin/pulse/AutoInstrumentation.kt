@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package pulse

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.timeval

/**
 * What the SDK measures without being asked.
 *
 * An app that has to call recordPerformance() for every number it wants is not monitored, it is
 * instrumented by hand — and the parts that matter most (a launch that got slower, a frozen main
 * thread, memory climbing until the OS kills the process) are exactly the ones nobody remembers to
 * instrument. Initialising PulseKit has to be enough for those to show up.
 *
 * Everything here is derived from what the OS already knows, so there is no cost to the app beyond
 * one background timer:
 *
 *  * **launch time** — the kernel records when the process started; the first activation says when
 *    the user could actually use it. The difference is the number an app-store review complains
 *    about, and neither end of it needs the app's cooperation.
 *  * **main-thread hangs** — a ping is dispatched to the main queue on a timer. The main queue not
 *    servicing it is the definition of the UI being frozen, whatever the cause.
 *  * **memory footprint** — sampled on a timer and on backgrounding; a jailed process is killed on
 *    footprint, so it is the number that predicts the kill.
 *  * **foreground / background** — session shape, which every analytics question is grouped by.
 */
internal class AutoInstrumentation(
    private val pulse: Pulse,
    private val scope: CoroutineScope,
) {
    private var activation: AppActivationObserver? = null
    private var watchdog: Job? = null
    private var sampler: Job? = null
    private var lifecycleJob: Job? = null
    private data class Activation(val active: Boolean, val observedAt: Long)
    private val lifecycleEvents = Channel<Activation>(32, BufferOverflow.DROP_OLDEST)

    internal fun onActive() { lifecycleEvents.trySend(Activation(true, nowMillisApple())) }
    internal fun onBackground() { lifecycleEvents.trySend(Activation(false, nowMillisApple())) }

    /** Written by the main queue, read by the watchdog coroutine — genuinely cross-thread. */
    private val lastPong = AtomicLong(0)
    private var launchReported = false

    fun install() {
        if (activation != null) return
        activation = AppActivationObserver(onActive = ::onActive, onBackground = ::onBackground)
        // 系统通知只投递有界消息，状态、事件缓冲和采样统一归 reactor 所有。
        lifecycleJob = scope.launch {
            for (event in lifecycleEvents) {
                // First activation is the launch; later ones are returns from the background, and
                // calling both "launch" would quietly halve every launch-time average.
                if (event.active) {
                    if (!launchReported) {
                        launchReported = true
                        reportLaunch(event.observedAt)
                    }
                    pulse.analytics.track("app_foreground")
                } else {
                    pulse.analytics.track("app_background")
                    sampleMemory()
                    pulse.client.flushOnce()
                }
            }
        }
        watchdog = scope.launch { watchMainThread() }
        sampler = scope.launch {
            while (isActive) {
                delay(MEMORY_SAMPLE_INTERVAL_MS)
                sampleMemory()
            }
        }
    }

    fun close() {
        activation?.close()
        activation = null
        lifecycleEvents.close()
        lifecycleJob?.cancel(); lifecycleJob = null
        watchdog?.cancel(); watchdog = null
        sampler?.cancel(); sampler = null
    }

    private fun reportLaunch(observedAt: Long) {
        val started = processStartMillis()
        if (started <= 0) return
        val elapsed = observedAt - started
        // A launch measured in minutes is not a launch: the process was resumed from a suspended
        // state, or the clock moved. Reporting it would drag every average somewhere meaningless.
        if (elapsed <= 0 || elapsed > MAX_PLAUSIBLE_LAUNCH_MS) return
        pulse.apm.recordPerformance(
            "app_launch_time",
            elapsed,
            mapOf("process_start_ms" to started, "phase" to "process_start_to_active"),
        )
    }

    /**
     * Ping the main queue and see how long it takes to answer.
     *
     * The ping is posted from the SDK's own thread, so a hang is observed rather than inferred: if
     * the main queue is busy, nothing at all runs there, including this. The watchdog reports one
     * event per episode, not one per tick — a ten second freeze is one thing that happened.
     */
    private suspend fun watchMainThread() {
        var reportedEpisode = false
        while (scope.isActive) {
            delay(PING_INTERVAL_MS)
            val sentAt = nowMillisApple()
            lastPong.store(0)
            dispatch_async(dispatch_get_main_queue()) { lastPong.store(nowMillisApple()) }

            var waited = 0L
            while (lastPong.load() == 0L && waited < MAX_HANG_WAIT_MS) {
                delay(PING_POLL_MS)
                waited = nowMillisApple() - sentAt
            }
            val answered = lastPong.load()
            val blockedMs = if (answered == 0L) waited else answered - sentAt

            if (blockedMs >= HANG_THRESHOLD_MS) {
                if (!reportedEpisode) {
                    reportedEpisode = true
                    pulse.apm.recordError(
                        "main_thread_hang",
                        // English, like every other message the SDK writes: the console
                        // localises for its reader, the wire carries one neutral form.
                        if (answered == 0L) "main thread unresponsive for at least $blockedMs ms (not yet recovered)"
                        else "main thread unresponsive for $blockedMs ms",
                        stack = null,
                        attributes = mapOf("blocked_ms" to blockedMs, "recovered" to (answered != 0L)),
                    )
                }
            } else {
                reportedEpisode = false
            }
        }
    }

    private fun sampleMemory() {
        val bytes = residentMemoryBytes()
        if (bytes <= 0) return
        pulse.apm.recordGauge("memory_footprint", bytes / (1024 * 1024), "MB")
    }

    private companion object {
        const val PING_INTERVAL_MS = 2_000L
        const val PING_POLL_MS = 100L
        /** Below this a slow frame is just a slow frame; above it the user sees a frozen app. */
        const val HANG_THRESHOLD_MS = 1_000L
        /** Stop waiting eventually: a watchdog that blocks forever stops being a watchdog. */
        const val MAX_HANG_WAIT_MS = 10_000L
        const val MEMORY_SAMPLE_INTERVAL_MS = 60_000L
        const val MAX_PLAUSIBLE_LAUNCH_MS = 60_000L
    }
}

/** Foreground/background transitions, which only the UI frameworks know about. */
internal expect class AppActivationObserver(onActive: () -> Unit, onBackground: () -> Unit) {
    fun close()
}

/** Epoch milliseconds; the SDK's own clock, not the host's. */
internal fun nowMillisApple(): Long = memScoped {
    val tv = alloc<timeval>()
    platform.posix.gettimeofday(tv.ptr, null)
    tv.tv_sec * 1000L + tv.tv_usec / 1000L
}
