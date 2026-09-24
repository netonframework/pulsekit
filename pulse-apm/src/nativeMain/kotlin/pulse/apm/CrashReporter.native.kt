@file:OptIn(ExperimentalForeignApi::class)

package pulse.apm

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.posix.SIGABRT
import platform.posix.SIGBUS
import platform.posix.SIGFPE
import platform.posix.SIGILL
import platform.posix.SIGSEGV
import platform.posix.SIGTRAP
import platform.posix.SIG_DFL
import platform.posix.close
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fsync
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.opendir
import platform.posix.raise
import platform.posix.readdir
import platform.posix.signal
import platform.posix.time
import platform.posix.unlink
import platform.posix.write

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

    private const val RECORD_NAME = "crash.record"

    /**
     * Frames captured per crash. Deep enough to reach past the runtime's own frames into
     * application code, shallow enough that the write stays one small syscall on a dying process.
     */
    private const val MAX_FRAMES = 64

    actual fun install(storageDir: String, sessionId: String) {
        if (installed) return
        makeDirectory(storageDir)     // 0755; already-exists is the normal case
        // O_TRUNC: this run owns the file. Anything a previous run left must already have been
        // taken by drainPending(), which is why the SDK calls that first.
        recordFd = openRecordFile("$storageDir/$RECORD_NAME")
        if (recordFd < 0) return
        sessionBuf = cstr(sessionId)
        sessionLen = sessionId.length
        scratch = nativeHeap.allocArray<ByteVar>(32)
        kSig = cstr("sig=")
        kTs = cstr("\nts=")
        kSession = cstr("\nsession=")
        kNewline = cstr("\n")
        kSlide = cstr("\nslide=")
        kFrames = cstr("\nframes=")
        kComma = cstr(",")
        frameBuf = nativeHeap.allocArray<COpaquePointerVar>(MAX_FRAMES)
        installed = true

        for (sig in intArrayOf(SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP)) {
            signal(sig, handler)
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
            writeInt(fd, time(null).toLong(), scr)
            writeC(fd, kSession)
            sessionBuf?.let { write(fd, it, sessionLen.toULong()) }

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
        signal(sig, SIG_DFL)
        raise(sig)
    }

    actual fun drainPending(storageDir: String): List<PendingCrash> {
        val out = ArrayList<PendingCrash>()
        val dir = opendir(storageDir) ?: return out
        val names = ArrayList<String>()
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry[0].d_name.toKString()
            if (name.startsWith(RECORD_NAME)) names.add(name)
        }
        closedir(dir)

        for (name in names) {
            val full = "$storageDir/$name"
            parseRecord(full)?.let { out.add(it) }
            unlink(full)     // read once; a record must never be reported twice
        }
        return out
    }

    private fun parseRecord(path: String): PendingCrash? = memScoped {
        val f = fopen(path, "r") ?: return null
        val buf = allocArray<ByteVar>(1024)
        val fields = HashMap<String, String>()
        while (fgets(buf, 1024, f) != null) {
            val line = buf.toKString().trim()
            val i = line.indexOf('=')
            if (i > 0) fields[line.substring(0, i)] = line.substring(i + 1)
        }
        fclose(f)
        // Two producers write here. The signal handler writes sig=; the uncaught-exception
        // reporter writes name=/message= instead, because an exception knows what it was and
        // "SIGABRT" is the least useful way to say NSInvalidArgumentException.
        val sig = fields["sig"]?.toIntOrNull()
        val name = fields["name"] ?: sig?.let(::signalName) ?: return null
        val message = fields["message"]?.takeIf { it.isNotBlank() }
            ?: sig?.let { "process terminated by signal $it" }
            ?: name
        val ts = fields["ts"]?.toLongOrNull() ?: 0L
        PendingCrash(
            name = name,
            message = message,
            timestampMs = ts * 1000L,
            sessionId = fields["session"].orEmpty(),
            imageSlide = fields["slide"]?.toLongOrNull() ?: 0L,
            frames = fields["frames"]?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList(),
        )
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
        write(fd, p, n.toULong())
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
        write(fd, scr, len.toULong())
    }
}

// mode_t is 16-bit on Apple and 32-bit on Linux. The shared native source set is compiled once
// against the commonized libc for publishing, and that compilation cannot express a call whose
// parameter width differs per platform — so the two mode-taking calls live in per-platform files.

/** mkdir(2) with 0755; already-exists is not an error. */
internal expect fun makeDirectory(path: String)

/** open(2) O_WRONLY|O_CREAT|O_TRUNC with 0644, returning the descriptor or -1. */
internal expect fun openRecordFile(path: String): Int
