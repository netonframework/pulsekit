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
    /**
     * The main image's load slide at crash time. Subtracting it from a frame address gives the
     * static address a symbol table is written against; without it the addresses are meaningless,
     * because ASLR maps the same build somewhere different on every launch.
     */
    val imageSlide: Long = 0,
    /** Return addresses, innermost first. Empty when the platform cannot capture a backtrace. */
    val frames: List<Long> = emptyList(),
    /**
     * A language-level stack trace, when the crash came with one — a Java exception on Android
     * carries its own frames as text. Null for signals and Objective-C exceptions, which report
     * [frames] instead.
     */
    val stack: String? = null,
)

/**
 * The record format keeps one field per line, so a multi-line value is written with backslashes and
 * newlines escaped and read back with [unescapeRecordValue].
 */
internal fun escapeRecordValue(value: String): String =
    value.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n")

internal fun unescapeRecordValue(value: String): String = buildString(value.length) {
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length) {
            when (value[i + 1]) {
                'n' -> { append('\n'); i += 2; continue }
                '\\' -> { append('\\'); i += 2; continue }
            }
        }
        append(c)
        i++
    }
}

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

/** The prefix of every crash record file in a storage directory; readers take all of them. */
internal const val CRASH_RECORD_NAME = "crash.record"

/** Records are read back with this bound (a frame list or an escaped stack trace stays well under it). */
internal const val MAX_CRASH_RECORD_BYTES = 64 * 1024

/**
 * Parse one crash record: `key=value` lines. Two kinds of producer write them. A signal handler
 * writes `sig=`, which [signalName] turns into a name; an uncaught-exception reporter writes
 * `name=` / `message=` (and on the JVM `stack=`), because an exception knows what it was.
 * Null when the record says nothing (an empty file from a run that did not crash).
 */
internal fun parseCrashRecord(text: String, signalName: (Int) -> String): PendingCrash? {
    val fields = HashMap<String, String>()
    for (line in text.split('\n')) {
        val trimmed = line.trim()
        val i = trimmed.indexOf('=')
        if (i > 0) fields[trimmed.substring(0, i)] = trimmed.substring(i + 1)
    }
    val sig = fields["sig"]?.toIntOrNull()
    val name = fields["name"] ?: sig?.let(signalName) ?: return null
    val message = fields["message"]?.takeIf { it.isNotBlank() }
        ?: sig?.let { "process terminated by signal $it" }
        ?: name
    val ts = fields["ts"]?.toLongOrNull() ?: 0L
    return PendingCrash(
        name = name,
        message = message,
        timestampMs = ts * 1000L,
        sessionId = fields["session"].orEmpty(),
        imageSlide = fields["slide"]?.toLongOrNull() ?: 0L,
        frames = fields["frames"]?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList(),
        stack = fields["stack"]?.takeIf { it.isNotBlank() }?.let(::unescapeRecordValue),
    )
}
