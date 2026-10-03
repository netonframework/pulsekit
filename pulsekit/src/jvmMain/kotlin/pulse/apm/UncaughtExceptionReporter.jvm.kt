package pulse.apm

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicReference

private class RecordTarget(val path: String, val sessionId: String)

/** Where the next record goes; replaced by each start, read on whichever thread is dying. */
private val target = AtomicReference<RecordTarget?>(null)

/**
 * Java's counterpart of NSSetUncaughtExceptionHandler. An uncaught exception ends an Android
 * process without any signal, so this is the only place it can be recorded: the record is written
 * for the next launch, then the exception goes to the handler that was installed before this one
 * (on Android the framework's, which ends the process). A start that finds PulseKit's handler
 * still in place only moves the record to its own directory and session; one that finds another
 * handler installed since (the host's, another SDK's) puts PulseKit's in front of it again.
 */
actual fun installUncaughtExceptionReporter(storageDir: String, sessionId: String) {
    // ".exception", as on iOS: drainPending() reads every file named after the crash record.
    target.set(RecordTarget("$storageDir/$CRASH_RECORD_NAME.exception", sessionId))
    val current = Thread.getDefaultUncaughtExceptionHandler()
    if (current is PulseUncaughtHandler) return
    Thread.setDefaultUncaughtExceptionHandler(PulseUncaughtHandler(current))
}

private class PulseUncaughtHandler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { writeRecord(error) } catch (_: Throwable) { }
        if (previous != null) {
            previous.uncaughtException(thread, error)
        } else {
            // What the JVM does with no handler at all. Not ThreadGroup.uncaughtException: that
            // would look up the default handler again, which is this one.
            System.err.print("Exception in thread \"${thread.name}\" ")
            error.printStackTrace()
        }
    }
}

/** The iOS record's fields, plus the Java stack trace as text: a Java frame already names its code. */
internal fun writeRecord(error: Throwable) {
    val destination = target.get() ?: return
    val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    val record = buildString {
        append("name=").append(oneLine(error.javaClass.name, 191)).append('\n')
        append("message=").append(oneLine(error.message.orEmpty(), 500)).append('\n')
        append("ts=").append(System.currentTimeMillis() / 1000).append('\n')
        append("session=").append(destination.sessionId).append('\n')
        append("slide=0\n")
        append("frames=\n")
        append("stack=").append(escapeRecordValue(trace.take(MAX_STACK_CHARS))).append('\n')
    }
    // Best effort, like the iOS writer: if this fails the process still ends the same way.
    File(destination.path).writeText(record)
}

private fun oneLine(value: String, limit: Int): String =
    value.replace('\n', ' ').replace('\r', ' ').take(limit)

/** Enough for a deep trace with a few causes; records are read back with a 64 KiB bound. */
private const val MAX_STACK_CHARS = 16 * 1024
