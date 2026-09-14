package pulse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventOutboxTest {
    @Test
    fun preservesFifoAndWaitsForRetryDeadline() {
        val outbox = InMemoryEventOutbox()
        outbox.enqueue("a", byteArrayOf(1), 0, 1, 100)
        outbox.enqueue("b", byteArrayOf(2), 0, 1, 101)

        val first = outbox.oldestDue(101)!!
        assertEquals("a", first.batchId)
        outbox.markRetry(first.sequence, 500)
        // FIFO is strict: a later row must not jump over the failed oldest row.
        assertNull(outbox.oldestDue(499))
        assertEquals("a", outbox.oldestDue(500)?.batchId)
        outbox.acknowledge(first.sequence)
        assertEquals("b", outbox.oldestDue(500)?.batchId)
    }

    @Test
    fun boundedQueueDropsOldest() {
        val outbox = InMemoryEventOutbox(maxBatches = 2)
        repeat(3) { outbox.enqueue("b$it", byteArrayOf(), 0, 0, it.toLong()) }
        assertEquals("b1", outbox.oldestDue(10)?.batchId)
    }
}
