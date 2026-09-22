@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pulse

import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import msgtrans.transport.Transport
import neton.io.net.runReactor
import pulse.core.SqlDelightEventOutbox
import pulse.core.UpdateAction
import pulse.core.JsonEventCodec
import pulse.core.PulseBizType
import pulse.core.PulseConfig
import pulse.core.PulseResponse
import pulse.core.ClientConnectRequest
import pulse.core.ClientConnectResult
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertTrue

// Test ports are deliberately below 32768, outside the kernel's ephemeral range
// (/proc/sys/net/ipv4/ip_local_port_range, typically 32768-60999). A fixed listen port
// inside that range intermittently loses the bind to some other process's outbound
// connection, which SO_REUSEADDR does not help with — it surfaces as a flaky EADDRINUSE.
/**
 * End-to-end skeleton check: a msgtrans server stands in for Pulse ingest and decodes the batch;
 * Pulse.start connects over the transport, the pipeline flushes a batch, the server receives and
 * decodes real events. Exercises core -> analytics -> transport -> msgtrans on one reactor.
 */
class PulseE2ETest {

    @Test
    fun offlineStartupPersistsAndReplaysAfterRestartAndReconnect() = runReactor {
        val config = PulseConfig(
            projectId = "offline-test", host = "127.0.0.1", port = 19611,
            analytics = false, apm = true, runtime = false,
            batchMaxEvents = 2, flushIntervalMs = 50,
            retryBaseDelayMs = 100, retryMaxDelayMs = 100,
            storageDir = "/tmp/pulse-offline-${kotlin.random.Random.nextLong()}",
        )
        val pulse = withTimeout(1_000) { Pulse.start(this@runReactor, config) }
        try {
            assertEquals(0, platform.posix.access("${config.storageDir}/crash.record", platform.posix.F_OK),
                "首次连接前必须已经准备好崩溃记录文件")
            assertEquals(UpdateAction.None, pulse.awaitUpdate(30).action)
            repeat(5) { pulse.track("offline-$it") }
            pulse.client.flushOnce()
        } finally {
            withTimeout(2_000) { pulse.stop() }
        }
        val saved = SqlDelightEventOutbox(checkNotNull(config.storageDir))
        val firstBatchId = try {
            assertTrue(saved.hasPending(), "离线事件必须写入 SQLite，而不是留在宿主命令队列")
            checkNotNull(saved.oldestDue(Long.MAX_VALUE)).batchId
        } finally { saved.close() }

        val restarted = withTimeout(1_000) { Pulse.start(this@runReactor, config) }
        delay(150)
        val received = mutableListOf<String>()
        val receivedBatchIds = mutableListOf<String>()
        val replayed = CompletableDeferred<Unit>()
        val server = Transport.bind(this, "127.0.0.1", config.port) { conn ->
            var registered = false
            conn.onRequest { payload, bizType ->
                when (bizType) {
                    PulseBizType.CLIENT_CONNECT -> {
                        registered = true
                        Json.encodeToString(
                            PulseResponse.serializer(ClientConnectResult.serializer()),
                            PulseResponse(data = ClientConnectResult(
                                connected = true, protocolVersion = 2,
                                connectionId = "offline-reconnected", serverTimeMs = 1,
                            )),
                        ).encodeToByteArray()
                    }
                    PulseBizType.CLIENT_EVENT_BATCH_UPLOAD -> {
                        check(registered)
                        val batch = JsonEventCodec.decode(payload)
                        receivedBatchIds += checkNotNull(batch.batchId)
                        received += batch.events.map { it.name }
                        if (received.size >= 5) replayed.complete(Unit)
                        Json.encodeToString(
                            PulseResponse.serializer(Boolean.serializer()), PulseResponse(data = true),
                        ).encodeToByteArray()
                    }
                    else -> ByteArray(0)
                }
            }
        }
        val serverJob = launch { server.acceptLoop() }
        try {
            withTimeout(5_000) { replayed.await() }
            assertEquals((0..4).map { "offline-$it" }, received)
            assertEquals(firstBatchId, receivedBatchIds.first())
        } finally {
            restarted.stop()
            serverJob.cancel()
            server.close()
        }
        val acknowledged = SqlDelightEventOutbox(checkNotNull(config.storageDir))
        try { assertTrue(!acknowledged.hasPending(), "只有服务端成功 ACK 后才清空 outbox") }
        finally { acknowledged.close() }
    }

    @Test
    fun startTrackAndIngest() = runReactor {
        val port = 19610
        val firstBatchDecoded = CompletableDeferred<Int>()
        val server = Transport.bind(this, "127.0.0.1", port) { conn ->
            var registered = false
            conn.onRequest { payload, bizType ->
                when (bizType) {
                    PulseBizType.CLIENT_CONNECT -> {
                        val request = Json.decodeFromString(
                            ClientConnectRequest.serializer(),
                            payload.decodeToString(),
                        )
                        check(request.appId == "vip-mall") { "wrong app in registration" }
                        check(request.deviceId.isNotEmpty()) { "registration carried no device id" }
                        registered = true
                        Json.encodeToString(
                            PulseResponse.serializer(ClientConnectResult.serializer()),
                            PulseResponse(
                                data = ClientConnectResult(
                                    connected = true,
                                    protocolVersion = 2,
                                    connectionId = "test-connection",
                                    serverTimeMs = 1,
                                ),
                            ),
                        ).encodeToByteArray()
                    }

                    PulseBizType.CLIENT_EVENT_BATCH_UPLOAD -> {
                        check(registered) { "batch arrived before CLIENT_CONNECT" }
                        val batch = JsonEventCodec.decode(payload)
                        if (!firstBatchDecoded.isCompleted) {
                            // The identity must be on the wire; a batch without a device id is useless
                            // to the server no matter how many events it carries.
                            check(batch.identity.deviceId.isNotEmpty()) { "batch carried no device id" }
                            check(batch.identity.projectId == "vip-mall") { "wrong project on the batch" }
                            firstBatchDecoded.complete(batch.events.size)
                        }
                        Json.encodeToString(
                            PulseResponse.serializer(Boolean.serializer()),
                            PulseResponse(data = true),
                        ).encodeToByteArray()
                    }

                    else -> ByteArray(0)
                }
            }
        }
        val serverJob = launch { server.acceptLoop() }

        val pulse = Pulse.start(this, PulseConfig(
            projectId = "vip-mall", host = "127.0.0.1", port = port,
            batchMaxEvents = 3, flushIntervalMs = 200,
            // Never inherit another run's retry schedule or backlog. This test is about the live
            // register -> update-check -> upload flow, not durable outbox recovery.
            storageDir = "/tmp/pulse-e2e-${kotlin.random.Random.nextLong()}",
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
