package pulse.apm

/**
 * No-op: on Linux there is no runtime-level uncaught-exception hook that is not already covered by
 * the signal handlers, and a Kotlin exception escaping main() aborts through SIGABRT with a usable
 * backtrace.
 */
actual fun installUncaughtExceptionReporter(storageDir: String, sessionId: String) = Unit
