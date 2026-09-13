package pulse

import platform.posix.getenv
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import pulse.core.PulseConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs on the iOS simulator and reports to a real Pulse ingest server, so the whole client path is
 * exercised the way a host app hits it: the facade's own thread, the neton-io reactor, the msgtrans
 * connection, and a synchronous flush.
 *
 * Opt-in through PULSE_IT_HOST, because it needs a server listening. Without it the test asserts
 * the facade's lifecycle only, which is still worth running everywhere — start() must not block the
 * calling thread and stop() must actually tear the thread down.
 */
@OptIn(ExperimentalForeignApi::class)
class PulseKitIosTest {

    private val itHost: String? get() = getenv("PULSE_IT_HOST")?.toKString()?.takeIf { it.isNotEmpty() }
    private val itPort: Int get() = getenv("PULSE_IT_PORT")?.toKString()?.toIntOrNull() ?: 9600

    @Test
    fun startIsNonBlockingAndStopTearsDown() {
        // A host calls this from the main thread during launch; if it blocked, the app would hang.
        PulseSDK.start(PulseConfig(projectId = "it-lifecycle", host = "127.0.0.1", port = 1))
        assertTrue(PulseSDK.isRunning)
        PulseSDK.track("noop")          // must not throw even with no server reachable
        PulseSDK.stop(timeoutMillis = 1_500)
        assertTrue(!PulseSDK.isRunning)
    }

    @Test
    fun reportsToARealServerWhenOneIsConfigured() {
        val host = itHost ?: return     // no server wired up; covered by the lifecycle test above
        val marker = "ios_sim_${platform.posix.time(null)}"

        PulseSDK.start(
            PulseConfig(
                projectId = "vip-mall", host = host, port = itPort,
                runtime = true, batchMaxEvents = 1000, flushIntervalMs = 60_000,
            ),
        )
        PulseSDK.identify("ios-tester")
        PulseSDK.track(marker, mapOf("surface" to "ios-simulator"))
        PulseSDK.recordError("IosDemoError", "synthetic from the simulator")
        PulseSDK.recordPerformance("ios_startup", 640)
        PulseSDK.recordBehavior("clipboard_read", module = "DemoSDK")

        PulseSDK.flushAndWait(timeoutMillis = 10_000)
        PulseSDK.stop(timeoutMillis = 5_000)

        // The assertion that matters is on the server side; this side just has to have got there.
        println("PULSE_IOS_MARKER=$marker")
        assertTrue(!PulseSDK.isRunning)
    }
}
