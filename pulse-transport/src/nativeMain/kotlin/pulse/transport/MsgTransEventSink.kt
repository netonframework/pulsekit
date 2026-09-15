package pulse.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import msgtrans.core.Compression
import msgtrans.transport.Connection
import msgtrans.transport.ConnectionConfig
import msgtrans.transport.Transport
import pulse.core.EventSink
import pulse.core.PulseBizType
import pulse.core.PulseConfig
import pulse.core.PulseResponse
import pulse.core.PulseResponseException

/**
 * EventSink over the msgtrans long connection. A batch is sent as one msgtrans Request with the
 * ingest biz type; the server acknowledges (its reply confirms receipt into the Pulse pipeline →
 * Redis queue → consumer → module DB). Using request (not one-way) gives the client delivery
 * confirmation and natural backpressure. The local outbox is acknowledged only after the server
 * returns `{code:0,data:true}`.
 *
 * Connect/reconnect is the SDK entry's job; this sink holds an open [Connection] and is replaced on
 * reconnect. It never touches connection internals off the reactor — Connection is thread-safe by
 * dispatch (calls post to the owning reactor).
 */
class MsgTransEventSink(private val conn: Connection) : EventSink {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun send(batch: ByteArray) {
        send(batch, Compression.None.code)
    }

    override suspend fun send(batch: ByteArray, compression: Int) {
        // request() throws on timeout / connection failure; PulseClient requeues the batch on throw.
        val payload = conn.request(
            batch,
            bizType = PulseBizType.EVENT_BATCH_UPLOAD,
            compression = Compression.fromCode(compression),
        )
        val response = json.decodeFromString(
            PulseResponse.serializer(Boolean.serializer()),
            payload.decodeToString(),
        )
        if (!response.isSuccess || response.data != true) {
            throw PulseResponseException(response.code, response.msg)
        }
    }
    /**
     * A question with an answer, on the same connection. Failures are swallowed into null: the one
     * caller is the startup update check, and a transport hiccup there must not stop the app.
     */
    override suspend fun request(bizType: Int, payload: ByteArray): ByteArray? =
        try {
            conn.request(payload, bizType = bizType)
        } catch (t: Throwable) {
            null
        }

    override suspend fun close() = conn.close()

    companion object {
        /** Open a Pulse ingest connection over TCP msgtrans using [config]. */
        suspend fun connect(scope: CoroutineScope, config: PulseConfig): MsgTransEventSink {
            val connection = Transport.connect(
                scope, config.host, config.port,
                ConnectionConfig(requestTimeoutMillis = 15_000, maxInFlightRequests = 8),
            )
            // The connection is bidirectional even before Pulse defines its first server command.
            // Unknown reverse requests still receive a valid application response instead of the
            // transport's empty default response or a connection failure.
            connection.onRequest { _, bizType ->
                Json.encodeToString(
                    PulseResponse.serializer(Unit.serializer()),
                    PulseResponse(code = 404, msg = "unsupported Pulse server biz_type=$bizType"),
                ).encodeToByteArray()
            }
            return MsgTransEventSink(connection)
        }
    }
}
