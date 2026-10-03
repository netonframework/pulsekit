package pulse.core

import pulse.deleteTestDirectory
import pulse.installTestSqlite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlDelightEventOutboxTest {
    init { installTestSqlite() }

    @Test
    fun survivesCloseAndOnlyAckDeletes() {
        val dir = "${pulse.testTempRoot()}/pulse-outbox-${newId()}"
        val now = nowMillis()
        try {
            SqlDelightEventOutbox(dir).use { first ->
                first.enqueue("one", byteArrayOf(1, 2), 0, 2, now)
                first.enqueue("two", byteArrayOf(3), 0, 1, now + 1)
                assertEquals("one", first.oldestDue(now + 1)?.batchId)
            }

            SqlDelightEventOutbox(dir).use { reopened ->
                val one = reopened.oldestDue(now + 1)!!
                assertEquals(listOf<Byte>(1, 2), one.payload.toList())
                reopened.markRetry(one.sequence, now + 500)
                assertTrue(reopened.hasPending())
                assertEquals("one", reopened.oldestDue(now + 500)?.batchId)
                reopened.acknowledge(one.sequence)
                val two = reopened.oldestDue(now + 500)!!
                assertEquals("two", two.batchId)
                reopened.acknowledge(two.sequence)
                assertFalse(reopened.hasPending())
            }
        } finally {
            deleteTestDirectory(dir)
        }
    }

    private inline fun <T : EventOutbox, R> T.use(block: (T) -> R): R =
        try { block(this) } finally { close() }
}
