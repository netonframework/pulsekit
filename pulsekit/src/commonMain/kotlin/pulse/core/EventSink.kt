package pulse.core

/**
 * The transport boundary. pulse-core produces encoded event batches and hands them to a sink; the
 * concrete sink (msgtrans long connection) lives in pulse-transport, so core/analytics/apm do not
 * depend on the transport and stay link-time independent.
 *
 * Contract: [send] delivers one encoded batch. It suspends under backpressure and throws on a
 * permanent failure so the caller can keep the batch for retry (crash-safe delivery is the
 * buffer's job, not the sink's).
 */
interface EventSink {
    suspend fun send(batch: ByteArray)

    /** Send with a msgtrans wire compression code. Existing sinks may ignore the preference. */
    suspend fun send(batch: ByteArray, compression: Int) = send(batch)

    /**
     * Ask the server a question that expects an answer, on the same connection as the events.
     * Currently only the startup update check. Returns null when the sink has no request channel
     * (the no-op sink) or the call fails — callers must treat that as "no answer", never as an
     * error worth failing startup over.
     */
    suspend fun request(bizType: Int, payload: ByteArray): ByteArray? = null

    suspend fun close()
}

/** Discards batches; the default until a transport is attached. Lets the SDK run with no network. */
object NoopEventSink : EventSink {
    override suspend fun send(batch: ByteArray) {}
    override suspend fun close() {}
}

/** Encodes a batch of events to bytes for the wire. JSON by default (shared with the web/TS side). */
interface EventCodec {
    fun encode(batch: EventBatch): ByteArray
    fun decode(bytes: ByteArray): EventBatch
}
