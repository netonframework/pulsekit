package pulse.apm

/**
 * Language-level crash capture, the counterpart to [CrashReporter]'s signal handlers.
 *
 * An uncaught Objective-C exception does reach the signal handler eventually — the runtime calls
 * abort() and SIGABRT is caught — but by then the only thing left is "process terminated by signal
 * 6", which says nothing about what went wrong. The exception itself knows its class and reason,
 * and this runs while that is still true.
 *
 * Unlike a signal handler this one is not running on a corrupted stack, so it is allowed to
 * allocate; it still writes a record for the *next* launch to upload rather than trying to reach
 * the network from a process that is one frame from dying.
 */
expect fun installUncaughtExceptionReporter(storageDir: String, sessionId: String)
