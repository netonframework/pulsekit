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
import kotlin.test.assertTrue

/**
 * Backtrace capture, which only Apple platforms have here — glibc's is not bound in Kotlin/Native's
 * posix klib and is not async-signal-safe on first call, so Linux deliberately reports no frames.
 * Keeping this out of the shared native tests means the Linux run asserts what Linux actually does
 * rather than failing on a capability it was never given.
 */
class BacktraceCaptureTest {

    private var counter = 0
    private fun tempDir(): String = "/tmp/pulse-bt-test-${getpid()}-${time(null)}-${counter++}"

    private fun inChild(body: () -> Unit): Int = memScoped {
        val pid = fork()
        if (pid == 0) { body(); _exit(0) }
        val status = alloc<IntVar>()
        waitpid(pid, status.ptr, 0)
        status.value
    }

    @Test
    fun theRecordCarriesABacktraceAndTheSlideNeededToUseIt() {
        val dir = tempDir()
        inChild {
            CrashReporter.install(dir, sessionId = "s-frames")
            raise(SIGSEGV)
        }

        val crash = CrashReporter.drainPending(dir).single()
        // Addresses alone are useless: ASLR puts the same build somewhere different every launch,
        // so the slide has to come from the crashing process, not be guessed later.
        assertTrue(crash.imageSlide != 0L, "no image slide recorded; frames cannot be symbolicated")
        assertTrue(crash.frames.isNotEmpty(), "no frames captured from the dying process")
        assertTrue(crash.frames.all { it > 0 }, "frame list contains a null address: ${crash.frames}")
        // The handler is somewhere in the captured stack, so there is more than a single frame.
        assertTrue(crash.frames.size > 1, "expected a real stack, got ${crash.frames.size} frame(s)")
    }
}
