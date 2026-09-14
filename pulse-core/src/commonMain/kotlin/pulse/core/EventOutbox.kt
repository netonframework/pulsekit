package pulse.core

/** One encoded event batch waiting for a msgtrans acknowledgement. */
data class OutboxBatch(
    val sequence: Long,
    val batchId: String,
    val payload: ByteArray,
    val compression: Int,
    val eventCount: Int,
    val createdAt: Long,
    val nextAttemptAt: Long,
    val attemptCount: Int,
)

/**
 * Persistent ordered delivery boundary. Implementations are single-owner: PulseClient calls every
 * method on its pipeline dispatcher, avoiding locks in the hot path.
 */
interface EventOutbox {
    fun enqueue(batchId: String, payload: ByteArray, compression: Int, eventCount: Int, nowMs: Long)
    fun oldestDue(nowMs: Long): OutboxBatch?
    fun acknowledge(sequence: Long)
    fun markRetry(sequence: Long, nextAttemptAtMs: Long)
    fun prune(nowMs: Long)
    fun hasPending(): Boolean
    fun close() {}
}

/** Test/default implementation; the shipping SDK installs the SQLDelight implementation. */
class InMemoryEventOutbox(
    private val maxBatches: Int = 1_024,
    private val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1_000,
) : EventOutbox {
    init {
        require(maxBatches > 0) { "maxBatches must be positive" }
        require(maxAgeMs > 0) { "maxAgeMs must be positive" }
    }

    private val batches = ArrayDeque<OutboxBatch>()
    private var nextSequence = 1L

    override fun enqueue(batchId: String, payload: ByteArray, compression: Int, eventCount: Int, nowMs: Long) {
        while (batches.size >= maxBatches) batches.removeFirst()
        batches.addLast(OutboxBatch(nextSequence++, batchId, payload, compression, eventCount, nowMs, nowMs, 0))
    }

    override fun oldestDue(nowMs: Long): OutboxBatch? = batches.firstOrNull()?.takeIf { it.nextAttemptAt <= nowMs }
    override fun acknowledge(sequence: Long) { batches.removeAll { it.sequence == sequence } }
    override fun markRetry(sequence: Long, nextAttemptAtMs: Long) {
        val index = batches.indexOfFirst { it.sequence == sequence }
        if (index < 0) return
        val old = batches[index]
        batches[index] = old.copy(attemptCount = old.attemptCount + 1, nextAttemptAt = nextAttemptAtMs)
    }
    override fun prune(nowMs: Long) { while (batches.firstOrNull()?.createdAt?.let { it < nowMs - maxAgeMs } == true) batches.removeFirst() }
    override fun hasPending(): Boolean = batches.isNotEmpty()
}
