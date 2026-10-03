package pulse.apm

import pulse.core.newId
import pulse.testTempRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The JVM's crash capture: an exception nobody caught, recorded for the next launch. */
class UncaughtExceptionReporterTest {

    @Test
    fun anUncaughtExceptionIsRecordedAndThePreviousHandlerStillRuns() {
        val dir = "${testTempRoot()}/pulse-jvm-crash-${newId()}"
        File(dir).mkdirs()
        var seenByPrevious: Throwable? = null
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seenByPrevious = e }
        try {
            installUncaughtExceptionReporter(dir, "jvm-session")
            val boom = IllegalStateException("boom\nsecond line", RuntimeException("cause 😀"))
            val thread = Thread({ throw boom }, "crasher")
            thread.start()
            thread.join()
            assertSame(boom, seenByPrevious, "the handler installed before PulseKit's must still get the exception")

            val crash = CrashReporter.drainPending(dir).single()
            assertEquals("java.lang.IllegalStateException", crash.name)
            assertEquals("boom second line", crash.message)
            assertEquals("jvm-session", crash.sessionId)
            val stack = assertNotNull(crash.stack)
            assertTrue(stack.startsWith("java.lang.IllegalStateException: boom\nsecond line"), stack)
            assertTrue("Caused by: java.lang.RuntimeException: cause 😀" in stack, stack)
            assertTrue(CrashReporter.drainPending(dir).isEmpty(), "a record is reported exactly once")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
            File(dir).deleteRecursively()
        }
    }
}
