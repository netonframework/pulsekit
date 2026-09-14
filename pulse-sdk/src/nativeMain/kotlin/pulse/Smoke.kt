@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pulse

import kotlinx.coroutines.delay
import neton.io.net.runReactor
import pulse.core.PulseConfig

/**
 * Ingest smoke client: connect to a Pulse ingest server over msgtrans, emit a few real events
 * (analytics + apm + runtime) as one JSON batch, flush, and exit. Used to drive the server
 * end-to-end, including the analytics/apm/security split on the query side.
 *
 *   pulseSmoke [host=127.0.0.1] [port=9600] [count=5] [crash] [buildNumber=100]
 *
 * Passing `crash` makes the process die by SIGSEGV right after the batch is flushed, so the next
 * run can be used to check that the crash was recorded and reported one launch later.
 */
fun pulseSmokeMain(args: Array<String>) {
    val host = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 9600
    val count = args.getOrNull(2)?.toIntOrNull() ?: 5
    val crashAtEnd = args.getOrNull(3) == "crash"
    val buildNumber = args.getOrNull(4)?.toLongOrNull() ?: 100L
    runReactor {
        val pulse = Pulse.start(this, PulseConfig(
            projectId = "vip-mall", host = host, port = port,
            runtime = true,
            platform = "ios", packageName = "com.example.vipmall",
            buildNumber = buildNumber, appVersion = "1.0.0-smoke",
            batchMaxEvents = 1000, flushIntervalMs = 60_000
        ))
        // The update check has already run inside start(); report what the server said.
        val u = pulse.update
        println("PULSE_UPDATE action=${u.action} latest=${u.latestVersionName} build=${u.latestBuildNumber} blocking=${u.isBlocking} notes=${u.releaseNotes}")

        pulse.identify("100086")
        repeat(count) { pulse.track("purchase", mapOf("amount" to (100 + it), "sku" to "sku-$it")) }
        pulse.apm.recordError("DemoError", message = "synthetic", stack = "at main()")
        pulse.apm.recordPerformance("startup", durationMs = 820)
        // A runtime event, so the server's security domain gets exercised too. Descriptive only:
        // the client states what happened, the server decides what it means.
        pulse.runtime?.recordBehavior("clipboard_read", module = "DemoSDK", attributes = mapOf("count" to 1))
        pulse.client.flushOnce()   // send one batch now
        delay(300)                 // let the ack round-trip settle
        if (crashAtEnd) {
            println("PULSE_SMOKE_CRASHING")
            platform.posix.fflush(null)
            platform.posix.raise(platform.posix.SIGSEGV)
        }
        pulse.stop()
        println("PULSE_SMOKE_DONE host=$host port=$port emitted=${count + 6}")
    }
}
