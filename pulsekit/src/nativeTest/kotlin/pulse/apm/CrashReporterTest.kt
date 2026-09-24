@file:OptIn(ExperimentalForeignApi::class)

package pulse.apm

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.SIGSEGV
import platform.posix._exit
import platform.posix.fork
import platform.posix.getpid
import platform.posix.raise
import platform.posix.time
import platform.posix.waitpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Crash capture can only be shown to work by actually crashing, so this forks a child, arms the
 * handler in it and kills it with SIGSEGV. The parent then reads back what the child's handler
 * managed to write while the process was dying. Writing a record file by hand and parsing it would
 * exercise none of the part that is actually hard.
 */
class CrashReporterTest {

    private var counter = 0
    private fun tempDir(): String = "/tmp/pulse-crash-test-${getpid()}-${time(null)}-${counter++}"

    /** Runs [body] in a forked child and waits for it. Returns the child's raw wait status. */
    private fun inChild(body: () -> Unit): Int = memScoped {
        val pid = fork()
        if (pid == 0) {
            body()
            _exit(0)     // only reached if body did not crash
        }
        val status = alloc<IntVar>()
        waitpid(pid, status.ptr, 0)
        status.value
    }

    @Test
    fun aCrashIsRecordedByTheHandlerAndReadBackAfterwards() {
        val dir = tempDir()

        inChild {
            CrashReporter.install(dir, sessionId = "session-under-test")
            raise(SIGSEGV)
        }

        val pending = CrashReporter.drainPending(dir)
        assertEquals(1, pending.size, "the dying child left no crash record in $dir")
        val crash = pending.single()
        assertEquals("SIGSEGV", crash.name)
        assertEquals("session-under-test", crash.sessionId, "the crash must join its own session")
        assertTrue(crash.timestampMs > 1_600_000_000_000L, "implausible crash timestamp ${crash.timestampMs}")
        assertTrue(crash.message.contains("$SIGSEGV"), crash.message)
    }

    @Test
    fun readingARecordRemovesIt() {
        val dir = tempDir()
        inChild {
            CrashReporter.install(dir, sessionId = "s1")
            raise(SIGSEGV)
        }

        assertEquals(1, CrashReporter.drainPending(dir).size)
        // A crash must be reported exactly once; the record is consumed as it is read.
        assertTrue(CrashReporter.drainPending(dir).isEmpty(), "the record survived a drain")
    }

    @Test
    fun aCleanRunLeavesNothingToReport() {
        val dir = tempDir()
        // install() creates the record file up front, so an exit without a crash must still look
        // like "nothing pending" — an empty file carries no sig= field and is discarded.
        inChild { CrashReporter.install(dir, sessionId = "s1") }
        assertEquals(emptyList(), CrashReporter.drainPending(dir))
    }
}
