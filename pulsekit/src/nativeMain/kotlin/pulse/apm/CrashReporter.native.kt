@file:OptIn(ExperimentalForeignApi::class)

package pulse.apm

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import pulse.posixshim.pulse_list_dir
import pulse.posixshim.pulse_read_file
import pulse.posixshim.pulse_time_seconds
import pulse.posixshim.pulse_write_bytes
import platform.posix.SIGABRT
import platform.posix.SIGBUS
import platform.posix.SIGFPE
import platform.posix.SIGILL
import platform.posix.SIGSEGV
import platform.posix.SIGTRAP
import platform.posix.close
import platform.posix.fsync
import platform.posix.raise
import platform.posix.unlink

/**
 * POSIX signal capture, shared by Apple and Linux — both are POSIX here and the constraint that
 * shapes the design is the same on each: a signal handler may only call async-signal-safe
 * functions. So the handler allocates nothing, formats nothing with printf, and touches no Kotlin
 * object: every buffer it needs is carved out of the native heap by [install] beforehand, and the
 * only calls it makes are open/write/close/signal/raise/time.
 *
 * It writes one small record and then restores the default disposition and re-raises, so the OS
 * still sees a normal crash and the platform's own reporter still gets it.
 */
actual object CrashReporter {

    // Everything the handler touches, preallocated. Raw pointers, never Kotlin strings.
    /**
     * The record file is opened by [install] and the descriptor kept, so the handler never calls
     * open() at all — one fewer thing that can fail or allocate on a corrupted stack.
     */
    private var recordFd: Int = -1
    private var sessionBuf: CPointer<ByteVar>? = null
    private var sessionLen: Int = 0
    private var scratch: CPointer<ByteVar>? = null      // integer formatting, 32 bytes
    private var kSig: CPointer<ByteVar>? = null
    private var kTs: CPointer<ByteVar>? = null
    private var kSession: CPointer<ByteVar>? = null
    private var kNewline: CPointer<ByteVar>? = null
    private var kSlide: CPointer<ByteVar>? = null
    private var kFrames: CPointer<ByteVar>? = null
    private var kComma: CPointer<ByteVar>? = null
    /** Frame addresses are written into this; allocated up front like everything else. */
    private var frameBuf: CPointer<COpaquePointerVar>? = null
    private var installed = false

    private const val RECORD_NAME = CRASH_RECORD_NAME

    /**
     * Frames captured per crash. Deep enough to reach past the runtime's own frames into
     * application code, shallow enough that the write stays one small syscall on a dying process.
     */
    private const val MAX_FRAMES = 64

    /** Where the current record file lives; a second install() for the same place is a no-op. */
    private var installedDir: String? = null

    actual fun install(storageDir: String, sessionId: String) {
        // Idempotent per directory, not per process. A forked child (the tests do this; so does
        // any host that re-execs) inherits `installed` from its parent, and silently keeping the
        // parent's descriptor would send its crash record to a directory nobody reads.
        if (installed && installedDir == storageDir) return
        if (installed) {
            if (recordFd >= 0) close(recordFd)
            recordFd = -1
        }
        installedDir = storageDir
        makeDirectory(storageDir)     // 0755; already-exists is the normal case
        // O_TRUNC: this run owns the file. Anything a previous run left must already have been
        // taken by drainPending(), which is why the SDK calls that first.
        recordFd = openRecordFile("$storageDir/$RECORD_NAME")
        if (recordFd < 0) return
        sessionBuf?.let { nativeHeap.free(it.rawValue) }   // re-install: the previous session's buffer
        sessionBuf = cstr(sessionId)
        sessionLen = sessionId.length
        if (!installed) {
            scratch = nativeHeap.allocArray<ByteVar>(32)
            kSig = cstr("sig=")
            kTs = cstr("\nts=")
            kSession = cstr("\nsession=")
            kNewline = cstr("\n")
            kSlide = cstr("\nslide=")
            kFrames = cstr("\nframes=")
            kComma = cstr(",")
            frameBuf = nativeHeap.allocArray<COpaquePointerVar>(MAX_FRAMES)
        }
        installed = true

        for (sig in intArrayOf(SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP)) {
            armCrashSignal(sig, handler)
        }
    }

    /**
     * Async-signal-safe. Kotlin/Native does not formally guarantee that running *any* Kotlin frame
     * from a handler is safe, so this one is kept to pointer reads and libc calls with no
     * allocation, no string creation and no suspension — which is as close to the C idiom as the
     * language allows.
     */
    private val handler = staticCFunction<Int, Unit> { sig ->
        val fd = recordFd
        val scr = scratch
        if (fd >= 0 && scr != null) {
            writeC(fd, kSig)
            writeInt(fd, sig.toLong(), scr)
            writeC(fd, kTs)
            writeInt(fd, pulse_time_seconds(), scr)
            writeC(fd, kSession)
            sessionBuf?.let { pulse_write_bytes(fd, it, sessionLen) }

            // The addresses on their own mean nothing once the process is gone: the same build
            // maps at a different address every launch. The slide is what turns a runtime address
            // back into the static one a symbol table is written against, so it has to be captured
            // here, with the frames, and not reconstructed later.
            writeC(fd, kSlide)
            writeInt(fd, imageSlide(), scr)
            writeC(fd, kFrames)
            val fb = frameBuf
            if (fb != null) {
                val n = captureBacktrace(fb, MAX_FRAMES)
                for (i in 0 until n) {
                    if (i > 0) writeC(fd, kComma)
                    writeInt(fd, fb[i]?.rawValue?.toLong() ?: 0L, scr)
                }
            }
            writeC(fd, kNewline)
            fsync(fd)           // the process is about to die; get the bytes to disk
            close(fd)
        }
        // Let the crash proceed normally: the platform reporter and any debugger still see it.
        restoreCrashSignal(sig)
        raise(sig)
    }

    actual fun drainPending(storageDir: String): List<PendingCrash> {
        val out = ArrayList<PendingCrash>()
        val names = listDirectory(storageDir).filter { it.startsWith(RECORD_NAME) }

        for (name in names) {
            val full = "$storageDir/$name"
            parseRecord(full)?.let { out.add(it) }
            unlink(full)     // read once; a record must never be reported twice
        }
        return out
    }

    private fun parseRecord(path: String): PendingCrash? = parseCrashRecord(readRecord(path), ::signalName)

    /**
     * The whole record, bounded. Read in one piece rather than line by line through a fixed
     * buffer: a frame list or an escaped stack trace is longer than any line buffer worth keeping,
     * and a split line would turn its tail into a bogus field.
     */
    private fun readRecord(path: String): String {
        val out = ByteArray(MAX_CRASH_RECORD_BYTES)
        val n = out.usePinned { pulse_read_file(path, it.addressOf(0), MAX_CRASH_RECORD_BYTES) }
        return if (n <= 0) "" else out.decodeToString(0, n)
    }

    /** Entry names in [path]; empty when it cannot be read. */
    private fun listDirectory(path: String): List<String> {
        var capacity = 4096
        repeat(4) {
            val buffer = ByteArray(capacity)
            val needed = buffer.usePinned { pulse_list_dir(path, it.addressOf(0), capacity) }
            if (needed < 0) return emptyList()
            if (needed <= capacity) return buffer.decodeToString(0, needed).split('\n').filter(String::isNotEmpty)
            capacity = needed + 1024
        }
        return emptyList()
    }


    /** Human-readable in the report; the number stays in the message for anything unmapped. */
    private fun signalName(sig: Int): String = when (sig) {
        SIGSEGV -> "SIGSEGV"
        SIGABRT -> "SIGABRT"
        SIGBUS -> "SIGBUS"
        SIGILL -> "SIGILL"
        SIGFPE -> "SIGFPE"
        SIGTRAP -> "SIGTRAP"
        else -> "SIG$sig"
    }

    // ---- preallocation and signal-safe primitives

    private fun cstr(s: String): CPointer<ByteVar> {
        val bytes = s.encodeToByteArray()
        val p = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
        for (i in bytes.indices) p[i] = bytes[i]
        p[bytes.size] = 0
        return p
    }

    /** write() a preallocated NUL-terminated buffer. No strlen from libc needed: scan the bytes. */
    private fun writeC(fd: Int, p: CPointer<ByteVar>?) {
        if (p == null) return
        var n = 0
        while (p[n] != 0.toByte()) n++
        pulse_write_bytes(fd, p, n)
    }

    /**
     * Format [v] into [scr] and write it. snprintf is not async-signal-safe, so the digits are
     * produced back-to-front and then shifted to the front of the buffer — index writes only, no
     * pointer arithmetic and no allocation.
     */
    private fun writeInt(fd: Int, v: Long, scr: CPointer<ByteVar>) {
        var value = v
        var i = 31
        if (value == 0L) { scr[i--] = '0'.code.toByte() }
        val negative = value < 0
        if (negative) value = -value
        while (value > 0 && i >= 1) {
            scr[i--] = ('0'.code + (value % 10).toInt()).toByte()
            value /= 10
        }
        if (negative && i >= 0) scr[i--] = '-'.code.toByte()
        val start = i + 1
        val len = 32 - start
        for (k in 0 until len) scr[k] = scr[start + k]
        pulse_write_bytes(fd, scr, len)
    }
}

// mode_t is 16-bit on Apple and 32-bit on Linux. The shared native source set is compiled once
// against the commonized libc for publishing, and that compilation cannot express a call whose
// parameter width differs per platform — so the two mode-taking calls live in per-platform files.

/** mkdir(2) with 0755; already-exists is not an error. */
internal expect fun makeDirectory(path: String)

/** open(2) O_WRONLY|O_CREAT|O_TRUNC with 0644, returning the descriptor or -1. */
internal expect fun openRecordFile(path: String): Int

/** Point [sig] at [handler]. */
internal expect fun armCrashSignal(sig: Int, handler: CPointer<CFunction<(Int) -> Unit>>)

/**
 * Hand [sig] back before re-raising it. Async-signal-safe. Apple and Linux restore the default
 * disposition; Android restores the handler that was there before, because that is debuggerd's,
 * and without it the crash leaves no tombstone and never reaches the platform's crash reporting.
 */
internal expect fun restoreCrashSignal(sig: Int)
