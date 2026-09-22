@file:OptIn(ExperimentalForeignApi::class)

package pulse.apm

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.Foundation.NSException
import platform.Foundation.NSSetUncaughtExceptionHandler
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.time

/**
 * Where the handler writes, and which session it belongs to. Captured at install time because the
 * handler is a static C function and cannot close over anything.
 */
private var recordPath: String? = null
private var recordSession: String = ""

/** Keeps a record on one line; the reader splits on '\n' and on the first '='. */
private fun oneLine(value: String, limit: Int): String =
    value.replace('\n', ' ').replace('\r', ' ').take(limit)

actual fun installUncaughtExceptionReporter(storageDir: String, sessionId: String) {
    // ".exception" rather than a directory of its own: drainPending() picks up everything whose
    // name starts with the crash record's, so one producer more costs nothing on the read side.
    recordPath = "$storageDir/crash.record.exception"
    recordSession = sessionId

    NSSetUncaughtExceptionHandler(
        staticCFunction { exception: NSException? ->
            val path = recordPath ?: return@staticCFunction
            val name = exception?.name ?: "NSException"
            val reason = exception?.reason ?: ""
            // callStackReturnAddresses is the exception's own stack, captured where it was raised.
            // The signal handler's backtrace would show the abort path instead, which is the
            // runtime tearing down rather than the code that threw.
            val frames = exception?.callStackReturnAddresses
                ?.take(64)
                ?.joinToString(",") { it.toString() }
                .orEmpty()

            val record = buildString {
                append("name=").append(oneLine(name, 191)).append('\n')
                append("message=").append(oneLine(reason, 500)).append('\n')
                // Seconds, matching what the signal handler writes; the reader multiplies.
                append("ts=").append(time(null).toLong()).append('\n')
                append("session=").append(recordSession).append('\n')
                // The slide travels with the frames for the same reason it does on the signal
                // path: these are runtime addresses, and without the slide the server cannot turn
                // them back into the static addresses a symbol table is written against.
                append("slide=").append(imageSlide()).append('\n')
                append("frames=").append(frames).append('\n')
            }
            // Written with stdio rather than NSString: the process is moments from abort(), and
            // stdio needs no autorelease pool, no Foundation and no allocation of an ObjC object
            // whose class may itself be the thing that misbehaved.
            //
            // Best effort by design — if the write fails the process still dies the same way and
            // the signal handler's SIGABRT record remains as the fallback report.
            val file = fopen(path, "w") ?: return@staticCFunction
            fputs(record, file)
            fclose(file)
        },
    )
}
