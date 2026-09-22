package pulse.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import pulse.core.Identity
import pulse.core.ClientConnectRequest
import pulse.core.ClientConnectResult
import pulse.core.ClientHeartbeatRequest
import pulse.core.ClientHeartbeatResult
import pulse.core.clientConnectRequest

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
class MsgTransEventSink private constructor(
    private val scope: CoroutineScope,
    private val config: PulseConfig,
    private val identity: Identity,
    /**
     * Called with the server's collection policy on connect, on reconnect and on every heartbeat.
     *
     * A callback rather than a property the client polls: the policy also arrives after a
     * reconnect the client never asked for, and a poll would apply it only at the next event —
     * which on a quiet app can be minutes of collecting something the operator has turned off.
     */
    private val onCollectionPolicy: ((List<String>) -> Unit)? = null,
) : EventSink {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val reconnect = Mutex()
    private var conn: Connection? = null
    private var closed = false
    private var connectResult: ClientConnectResult? = null

    val heartbeatIntervalMs: Long
        get() = connectResult?.heartbeatIntervalMs?.coerceIn(15_000, 5L * 60_000) ?: 60_000

    override suspend fun send(batch: ByteArray) {
        send(batch, Compression.None.code)
    }

    override suspend fun send(batch: ByteArray, compression: Int) {
        // request() throws on timeout / connection failure; PulseClient requeues the batch on throw.
        val payload = withConnection { connection ->
            connection.request(
                batch,
                bizType = PulseBizType.CLIENT_EVENT_BATCH_UPLOAD,
                compression = Compression.fromCode(compression),
            )
        }
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
    override suspend fun request(bizType: Int, payload: ByteArray): ByteArray? = try {
        withConnection { it.request(payload, bizType = bizType) }
    } catch (t: CancellationException) {
        throw t
    } catch (_: Throwable) {
        null
    }

    /** Refresh the server-side online TTL and detect an otherwise idle broken socket. */
    suspend fun heartbeat() {
        val payload = withConnection { connection ->
            // Build this after ensureConnected(): a failed heartbeat may reconnect, in which case
            // the replacement connection has a different server-issued connection ID.
            val connected = checkNotNull(connectResult) { "Pulse client connection is not registered" }
            val request = ClientHeartbeatRequest(
                connectionId = connected.connectionId,
                clientTimeMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            )
            connection.request(
                json.encodeToString(ClientHeartbeatRequest.serializer(), request).encodeToByteArray(),
                bizType = PulseBizType.CLIENT_HEARTBEAT,
            )
        }
        val response = json.decodeFromString(
            PulseResponse.serializer(ClientHeartbeatResult.serializer()),
            payload.decodeToString(),
        )
        if (!response.isSuccess || response.data?.accepted != true) {
            throw PulseResponseException(response.code, response.msg ?: "Pulse heartbeat rejected")
        }
        val result = checkNotNull(response.data)
        connectResult = connectResult?.copy(
            configRevision = result.configRevision,
            heartbeatIntervalMs = result.heartbeatIntervalMs,
            collectKinds = result.collectKinds,
        )
        onCollectionPolicy?.invoke(result.collectKinds)
    }

    override suspend fun close() {
        closed = true
        reconnect.withLock {
            val current = conn
            conn = null
            connectResult = null
            if (current != null) runCatching { current.close() }
        }
    }

    companion object {
        /** Open a Pulse ingest connection over TCP msgtrans using [config]. */
        suspend fun connect(
            scope: CoroutineScope,
            config: PulseConfig,
            identity: Identity,
            onCollectionPolicy: ((List<String>) -> Unit)? = null,
        ): MsgTransEventSink {
            val sink = MsgTransEventSink(scope, config, identity, onCollectionPolicy)
            sink.ensureConnected()
            return sink
        }
    }

    private suspend fun ensureConnected(): Connection = reconnect.withLock {
        check(!closed) { "Pulse event sink is closed" }
        conn?.let { return@withLock it }
        val connection = Transport.connect(
            scope, config.host, config.port,
            ConnectionConfig(requestTimeoutMillis = 15_000, maxInFlightRequests = 8),
        )
        try {
            // The connection is bidirectional even before Pulse defines its first server command.
            // Unknown reverse requests still receive a valid application response instead of the
            // transport's empty default response or a connection failure.
            connection.onRequest { _, bizType ->
                Json.encodeToString(
                    PulseResponse.serializer(Unit.serializer()),
                    PulseResponse(code = 404, msg = "unsupported Pulse server biz_type=$bizType"),
                ).encodeToByteArray()
            }
            val result = performClientConnect(connection, clientConnectRequest(config, identity))
            connectResult = result
            onCollectionPolicy?.invoke(result.collectKinds)
            conn = connection
            connection
        } catch (t: Throwable) {
            runCatching { connection.close() }
            throw t
        }
    }

    private suspend fun <T> withConnection(block: suspend (Connection) -> T): T {
        var failure: Throwable? = null
        repeat(2) {
            val connection = ensureConnected()
            try {
                return block(connection)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                failure = t
                invalidate(connection)
            }
        }
        throw failure ?: IllegalStateException("Pulse connection unavailable")
    }

    private suspend fun invalidate(failed: Connection) = reconnect.withLock {
        if (conn === failed) {
            conn = null
            connectResult = null
            runCatching { failed.close() }
        }
    }

    private suspend fun performClientConnect(connection: Connection, request: ClientConnectRequest): ClientConnectResult {
        val payload = connection.request(
            json.encodeToString(ClientConnectRequest.serializer(), request).encodeToByteArray(),
            bizType = PulseBizType.CLIENT_CONNECT,
        )
        val response = json.decodeFromString(
            PulseResponse.serializer(ClientConnectResult.serializer()),
            payload.decodeToString(),
        )
        if (!response.isSuccess || response.data?.connected != true) {
            throw PulseResponseException(response.code, response.msg ?: "Pulse client connection was rejected")
        }
        return checkNotNull(response.data)
    }
}
