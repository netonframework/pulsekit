package pulse.transport

import kotlinx.coroutines.CoroutineScope
import msgtrans.transport.Connection
import msgtrans.transport.ConnectionConfig
import msgtrans.transport.Transport
import pulse.core.EventSink
import pulse.core.PulseConfig

/**
 * EventSink over the msgtrans long connection. A batch is sent as one msgtrans Request with the
 * ingest biz type; the server acknowledges (its reply confirms receipt into the Pulse pipeline →
 * Redis queue → consumer → module DB). Using request (not one-way) gives the client delivery
 * confirmation and natural backpressure; the ingest ack payload is currently ignored.
 *
 * Connect/reconnect is the SDK entry's job; this sink holds an open [Connection] and is replaced on
 * reconnect. It never touches connection internals off the reactor — Connection is thread-safe by
 * dispatch (calls post to the owning reactor).
 */
class MsgTransEventSink(private val conn: Connection) : EventSink {
    override suspend fun send(batch: ByteArray) {
        // request() throws on timeout / connection failure; PulseClient requeues the batch on throw.
        conn.request(batch, bizType = BIZ_INGEST)
    }
    override suspend fun close() = conn.close()

    companion object {
        /** Application biz type for an event-batch ingest request. */
        const val BIZ_INGEST = 1

        /** Open a Pulse ingest connection over TCP msgtrans using [config]. */
        suspend fun connect(scope: CoroutineScope, config: PulseConfig): MsgTransEventSink =
            MsgTransEventSink(Transport.connect(scope, config.host, config.port,
                ConnectionConfig(requestTimeoutMillis = 15_000, maxInFlightRequests = 8)))
    }
}
