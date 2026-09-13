package pulse.apm

/**
 * A crash recorded by the previous run of the process, read back on the next launch.
 *
 * Why it works this way: a signal handler runs on a corrupted or unknown stack and may only call
 * async-signal-safe functions. Allocating, taking locks, resuming coroutines or doing network I/O
 * from there is undefined behaviour — a "flush the batch over the socket before we die" handler
 * deadlocks or crashes again often enough to lose the very report it was written for.
 *
 * So the handler does the one thing it safely can: write(2) a short record to a file that was
 * opened in advance. The next launch reads those files, reports them as normal [EventKind.Crash]
 * events through the ordinary pipeline, and deletes them. Delivery is therefore one launch late,
 * which is what every production crash reporter does.
 */
data class PendingCrash(
    /** Signal name, or the exception class for a language-level crash. */
    val name: String,
    val message: String,
    /** epoch ms recorded at crash time. */
    val timestampMs: Long,
    /** Session the crashed run belonged to, so the crash joins its other events server-side. */
    val sessionId: String,
)

/**
 * Platform crash capture. [install] arms the handlers and must be called once, early, before the
 * app can crash; [drainPending] returns and clears whatever the previous run left behind.
 *
 * Not installed by default — the SDK entry point wires it when apm is enabled, so a host that
 * wants its own crash reporter is not fighting for the same signals.
 */
expect object CrashReporter {
    /**
     * Arm the handlers. [sessionId] is stamped into any record written, and [storageDir] is where
     * records are kept — the caller owns the directory so tests can point it somewhere disposable.
     */
    fun install(storageDir: String, sessionId: String)

    /** Crashes recorded by earlier runs. Reading them removes them. */
    fun drainPending(storageDir: String): List<PendingCrash>
}
