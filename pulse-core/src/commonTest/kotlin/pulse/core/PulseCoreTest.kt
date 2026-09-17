package pulse.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
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
        val identity = WireIdentity("p", "install", "device", userId = "u")
        val decoded = JsonEventCodec.decode(JsonEventCodec.encode(EventBatch(identity, events)))
        assertEquals(events.map { it.name }, decoded.events.map { it.name })
        assertEquals(events.map { it.kind }, decoded.events.map { it.kind })
        // Identity travels with the batch; without it the server cannot count devices or users.
        assertEquals(identity, decoded.identity)
    }

    @Test
    fun wireIdentityCarriesTheRuntimePackageForServerPolicy() {
        val config = PulseConfig(
            projectId = "pulse_app_123",
            host = "127.0.0.1",
            platform = "ios",
            packageName = "com.example.resigned",
        )
        val wire = Identity(config.projectId, "install", "device").toWire(config)
        assertEquals("pulse_app_123", config.appId)
        assertEquals("pulse_app_123", wire.projectId)
        assertEquals("ios", wire.platform)
        assertEquals("com.example.resigned", wire.packageName)
        assertEquals(ClientConnectRequest.SDK_VERSION, wire.sdkVersion)
    }

    @Test
    fun clientFlushesBatchToSink() = runTest {
        val sent = mutableListOf<ByteArray>()
        val sink = object : EventSink {
            override suspend fun send(batch: ByteArray) { sent.add(batch) }
            override suspend fun close() {}
        }
        val client = PulseClient(
            PulseConfig(
                projectId = "t", host = "127.0.0.1", batchMaxEvents = 100,
                retryBaseDelayMs = 0, retryMaxDelayMs = 0,
            ),
            Identity("t", "i", "d"), this, sink,
        )
        repeat(5) { client.emit(EventKind.Analytics, "e$it") }
        client.flushOnce()
        assertEquals(1, sent.size)
        assertEquals(5, JsonEventCodec.decode(sent[0]).events.size)
    }

    @Test
    fun failedSendRequeuesForRetry() = runTest {
        var fail = true
        val delivered = mutableListOf<Int>()
        val sink = object : EventSink {
            override suspend fun send(batch: ByteArray) {
                if (fail) throw RuntimeException("down")
                delivered.add(JsonEventCodec.decode(batch).events.size)
            }
            override suspend fun close() {}
        }
        val client = PulseClient(
            PulseConfig(
                projectId = "t", host = "127.0.0.1", batchMaxEvents = 100,
                retryBaseDelayMs = 0, retryMaxDelayMs = 0,
            ),
            Identity("t", "i", "d"), this, sink,
        )
        repeat(3) { client.emit(EventKind.Error, "err$it") }
        client.flushOnce() // throws internally, requeues
        fail = false
        client.flushOnce() // now delivers the same 3
        assertEquals(listOf(3), delivered)
    }

    @Test
    fun selectsCompressionOnceAndKeepsItAcrossRetry() = runTest {
        var fail = true
        val compressionCodes = mutableListOf<Int>()
        val sink = object : EventSink {
            override suspend fun send(batch: ByteArray) = error("compression-aware path expected")
            override suspend fun send(batch: ByteArray, compression: Int) {
                compressionCodes += compression
                if (fail) throw RuntimeException("down")
            }
            override suspend fun close() {}
        }
        val client = PulseClient(
            PulseConfig(
                projectId = "t", host = "127.0.0.1", batchMaxEvents = 100,
                uploadCompression = UploadCompression.Zlib, compressionMinBytes = 1,
                retryBaseDelayMs = 0, retryMaxDelayMs = 0,
            ),
            Identity("t", "i", "d"), this, sink,
        )
        client.emit(EventKind.Analytics, "event")
        client.flushOnce()
        fail = false
        client.flushOnce()
        assertEquals(listOf(UploadCompression.Zlib.wireCode, UploadCompression.Zlib.wireCode), compressionCodes)
    }

    @Test
    fun batchByteLimitSplitsWithoutReordering() = runTest {
        val limit = 800
        val sent = mutableListOf<ByteArray>()
        val sink = object : EventSink {
            override suspend fun send(batch: ByteArray) { sent += batch }
            override suspend fun close() {}
        }
        val client = PulseClient(
            PulseConfig(
                projectId = "t", host = "127.0.0.1",
                batchMaxEvents = 100, batchMaxBytes = limit,
                retryBaseDelayMs = 0, retryMaxDelayMs = 0,
            ),
            Identity("t", "i", "d"), this, sink,
        )
        repeat(5) { index ->
            client.emit(
                EventKind.Analytics,
                "e$index",
                mapOf("payload" to JsonPrimitive("x".repeat(300))),
            )
        }

        repeat(5) { client.flushOnce() }

        assertTrue(sent.size > 1)
        assertTrue(sent.all { it.size <= limit }, sent.map { it.size }.toString())
        assertEquals(
            listOf("e0", "e1", "e2", "e3", "e4"),
            sent.flatMap { JsonEventCodec.decode(it).events }.map { it.name },
        )
        assertEquals(0, client.oversizedEventsDropped)
    }

    @Test
    fun oversizedEventDoesNotBlockFollowingEvents() = runTest {
        val sent = mutableListOf<ByteArray>()
        val sink = object : EventSink {
            override suspend fun send(batch: ByteArray) { sent += batch }
            override suspend fun close() {}
        }
        val client = PulseClient(
            PulseConfig(
                projectId = "t", host = "127.0.0.1",
                batchMaxEvents = 100, batchMaxBytes = 600,
                retryBaseDelayMs = 0, retryMaxDelayMs = 0,
            ),
            Identity("t", "i", "d"), this, sink,
        )
        client.emit(
            EventKind.Analytics,
            "too-large",
            mapOf("payload" to JsonPrimitive("x".repeat(2_000))),
        )
        client.emit(EventKind.Analytics, "deliverable")

        client.flushOnce()

        assertEquals(1, client.oversizedEventsDropped)
        assertEquals(listOf("deliverable"), JsonEventCodec.decode(sent.single()).events.map { it.name })
        assertTrue(sent.single().size <= 600)
    }

    private fun ev(name: String) = Event(newId(), nowMillis(), "sess", EventKind.Analytics, name)
}
