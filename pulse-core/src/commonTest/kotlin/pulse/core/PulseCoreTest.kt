package pulse.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PulseCoreTest {

    @Test
    fun bufferDropsOldestWhenFull() {
        val b = EventBuffer(2)
        repeat(3) { b.add(ev("e$it")) }
        assertEquals(1, b.dropped)
        val drained = b.drain(10).map { it.name }
        assertEquals(listOf("e1", "e2"), drained) // e0 dropped, order preserved
    }

    @Test
    fun requeuePreservesOrder() {
        val b = EventBuffer(10)
        val batch = listOf(ev("a"), ev("b"))
        b.requeueFront(batch)
        b.add(ev("c"))
        assertEquals(listOf("a", "b", "c"), b.drain(10).map { it.name })
    }

    @Test
    fun jsonCodecRoundTrips() {
        val events = listOf(ev("purchase"), ev("view"))
        val decoded = JsonEventCodec.decode(JsonEventCodec.encode(events))
        assertEquals(events.map { it.name }, decoded.map { it.name })
        assertEquals(events.map { it.kind }, decoded.map { it.kind })
    }

    @Test
    fun clientFlushesBatchToSink() = runTest {
        val sent = mutableListOf<ByteArray>()
        val sink = object : EventSink {
            override suspend fun send(batch: ByteArray) { sent.add(batch) }
            override suspend fun close() {}
        }
        val client = PulseClient(
            PulseConfig(projectId = "t", host = "127.0.0.1", batchMaxEvents = 100),
            Identity("t", "i", "d"), this, sink,
        )
        repeat(5) { client.emit(EventKind.Analytics, "e$it") }
        client.flushOnce()
        assertEquals(1, sent.size)
        assertEquals(5, JsonEventCodec.decode(sent[0]).size)
    }

    @Test
    fun failedSendRequeuesForRetry() = runTest {
        var fail = true
        val delivered = mutableListOf<Int>()
        val sink = object : EventSink {
            override suspend fun send(batch: ByteArray) {
                if (fail) throw RuntimeException("down")
                delivered.add(JsonEventCodec.decode(batch).size)
            }
            override suspend fun close() {}
        }
        val client = PulseClient(
            PulseConfig(projectId = "t", host = "127.0.0.1", batchMaxEvents = 100),
            Identity("t", "i", "d"), this, sink,
        )
        repeat(3) { client.emit(EventKind.Error, "err$it") }
        client.flushOnce() // throws internally, requeues
        fail = false
        client.flushOnce() // now delivers the same 3
        assertEquals(listOf(3), delivered)
    }

    private fun ev(name: String) = Event(newId(), nowMillis(), "sess", EventKind.Analytics, name)
}
