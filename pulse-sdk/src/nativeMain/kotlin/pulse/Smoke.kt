package pulse

import kotlinx.coroutines.delay
import neton.io.net.runReactor
import pulse.core.PulseConfig

/**
 * Ingest smoke client: connect to a Pulse ingest server over msgtrans, emit a few real events
 * (analytics + apm) as one JSON batch, flush, and exit. Used to drive the server end-to-end.
 *
 *   pulseSmoke [host=127.0.0.1] [port=9600] [count=5]
 */
fun pulseSmokeMain(args: Array<String>) {
    val host = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 9600
    val count = args.getOrNull(2)?.toIntOrNull() ?: 5
    runReactor {
        val pulse = Pulse.start(this, PulseConfig(
            projectId = "vip-mall", host = host, port = port,
            batchMaxEvents = 1000, flushIntervalMs = 60_000
        ))
        pulse.identify("100086")
        repeat(count) { pulse.track("purchase", mapOf("amount" to (100 + it), "sku" to "sku-$it")) }
        pulse.apm.recordError("DemoError", message = "synthetic", stack = "at main()")
        pulse.apm.recordPerformance("startup", durationMs = 820)
        pulse.client.flushOnce()   // send one batch now
        delay(300)                 // let the ack round-trip settle
        pulse.stop()
        println("PULSE_SMOKE_DONE host=$host port=$port emitted=${count + 4}")
    }
}
