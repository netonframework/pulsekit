package pulse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import msgtrans.transport.Transport
import neton.io.net.runReactor
import pulse.core.JsonEventCodec
import pulse.core.PulseConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end skeleton check: a msgtrans server stands in for Pulse ingest and decodes the batch;
 * Pulse.start connects over the transport, the pipeline flushes a batch, the server receives and
 * decodes real events. Exercises core -> analytics -> transport -> msgtrans on one reactor.
 */
class PulseE2ETest {

    @Test
    fun startTrackAndIngest() = runReactor {
        val port = 39610
        val firstBatchDecoded = CompletableDeferred<Int>()
        val server = Transport.bind(this, "127.0.0.1", port) { conn ->
            conn.onRequest { payload, _ ->
                val events = JsonEventCodec.decode(payload)
                if (!firstBatchDecoded.isCompleted) firstBatchDecoded.complete(events.size)
                ByteArray(0) // ack
            }
        }
        val serverJob = launch { server.acceptLoop() }

        val pulse = Pulse.start(this, PulseConfig(
            projectId = "vip-mall", host = "127.0.0.1", port = port,
            batchMaxEvents = 3, flushIntervalMs = 200,
        ))
        pulse.identify("100086")
        pulse.track("purchase", mapOf("amount" to 199))
        pulse.track("view", mapOf("page" to "home"))
        // start() already emitted session_start + app_launch, so >= 3 events -> a batch flushes.

        val count = withTimeout(5_000) { firstBatchDecoded.await() }
        assertTrue(count >= 3, "server should have decoded a batch of events, got $count")

        pulse.stop()
        serverJob.cancel()
        server.close()
    }
}
