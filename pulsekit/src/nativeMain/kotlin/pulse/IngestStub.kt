@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pulse

import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import msgtrans.transport.Transport
import neton.io.net.runReactor
import pulse.core.ClientConnectRequest
import pulse.core.ClientConnectResult
import pulse.core.ClientHeartbeatResult
import pulse.core.JsonEventCodec
import pulse.core.PulseBizType
import pulse.core.PulseResponse
import pulse.core.UpdateCheckRequest
import pulse.core.UpdateCheckWireResult
import pulse.core.WireIdentity
import pulse.core.nowMillis

/**
 * A stand-in for the Pulse ingest server, for driving a real device or emulator end to end without
 * the server, Redis and PostgreSQL: it registers connections, answers heartbeats and the update
 * check (always "optional", so the host's update path runs), acknowledges every batch, and prints
 * one line per event it received.
 *
 *   pulseIngestStub [host=127.0.0.1] [port=9600]
 *
 * An Android emulator reaches the Mac's loopback as 10.0.2.2.
 */
fun pulseIngestStubMain(args: Array<String>) {
    val host = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 9600
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    runReactor {
        val server = Transport.bind(this, host, port) { conn ->
            conn.onRequest { payload, bizType ->
                when (bizType) {
                    PulseBizType.CLIENT_CONNECT -> {
                        val request = json.decodeFromString(ClientConnectRequest.serializer(), payload.decodeToString())
                        report("PULSE_CONNECT ${json.encodeToString(ClientConnectRequest.serializer(), request)}")
                        json.encodeToString(
                            PulseResponse.serializer(ClientConnectResult.serializer()),
                            PulseResponse(data = ClientConnectResult(
                                connected = true, protocolVersion = 2,
                                connectionId = "stub-${nowMillis()}", serverTimeMs = nowMillis(),
                                heartbeatIntervalMs = 15_000,
                            )),
                        ).encodeToByteArray()
                    }
                    PulseBizType.CLIENT_HEARTBEAT -> json.encodeToString(
                        PulseResponse.serializer(ClientHeartbeatResult.serializer()),
                        PulseResponse(data = ClientHeartbeatResult(accepted = true, serverTimeMs = nowMillis())),
                    ).encodeToByteArray()
                    PulseBizType.CLIENT_APP_UPDATE_CHECK -> {
                        val request = json.decodeFromString(UpdateCheckRequest.serializer(), payload.decodeToString())
                        report("PULSE_UPDATE_CHECK ${json.encodeToString(UpdateCheckRequest.serializer(), request)}")
                        json.encodeToString(
                            PulseResponse.serializer(UpdateCheckWireResult.serializer()),
                            PulseResponse(data = UpdateCheckWireResult(
                                action = "optional", latestVersionName = "9.9.9",
                                latestBuildNumber = request.buildNumber + 1, releaseNotes = "stub",
                            )),
                        ).encodeToByteArray()
                    }
                    PulseBizType.CLIENT_EVENT_BATCH_UPLOAD -> {
                        val batch = JsonEventCodec.decode(payload)
                        report("PULSE_BATCH ${batch.batchId} ${json.encodeToString(WireIdentity.serializer(), batch.identity)}")
                        for (event in batch.events) {
                            report(
                                "PULSE_EVENT ${event.kind} ${event.name} session=${event.sessionId} " +
                                    "module=${event.source.module} uuid=${event.source.imageUuid} ${event.attributes}",
                            )
                        }
                        json.encodeToString(PulseResponse.serializer(Boolean.serializer()), PulseResponse(data = true))
                            .encodeToByteArray()
                    }
                    else -> json.encodeToString(
                        PulseResponse.serializer(Unit.serializer()),
                        PulseResponse(code = 404, msg = "unsupported biz_type=$bizType"),
                    ).encodeToByteArray()
                }
            }
        }
        report("PULSE_STUB_LISTENING $host:$port")
        launch { server.acceptLoop() }.join()
    }
}

/** One line, flushed at once: the output is usually piped into a log someone is watching. */
private fun report(line: String) {
    println(line)
    platform.posix.fflush(null)
}
