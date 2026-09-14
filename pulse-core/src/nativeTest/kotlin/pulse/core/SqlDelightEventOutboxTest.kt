@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pulse.core

import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlDelightEventOutboxTest {
    @Test
    fun survivesCloseAndOnlyAckDeletes() {
        val dir = "/tmp/pulse-outbox-${newId()}"
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
            unlink("$dir/pulse-outbox.db")
            unlink("$dir/pulse-outbox.db-wal")
            unlink("$dir/pulse-outbox.db-shm")
            rmdir(dir)
        }
    }

    private inline fun <T : EventOutbox, R> T.use(block: (T) -> R): R =
        try { block(this) } finally { close() }
}
