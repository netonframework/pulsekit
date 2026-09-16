@file:OptIn(ExperimentalForeignApi::class)

package pulse.runtime

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.darwin.backtrace
import platform.posix.Dl_info
import platform.posix.dladdr

/**
 * Works out which loaded image made a call.
 *
 * This is what makes "which SDK read the clipboard" answerable rather than just "the clipboard was
 * read". Walk the return addresses, map each to its image with dladdr, and report the first one
 * that is neither this SDK nor the OS's dispatch machinery.
 */
internal object CallerAttribution {

    /**
     * A bounded, ASLR-independent description of the code that reached a monitored API.
     *
     * [symbol] is present when the final binary kept a symbol for the nearest caller. Stripped
     * release binaries often do not, so [imageOffset] and [stackFingerprint] are the durable
     * fallback: both use offsets from each Mach-O image base rather than process addresses.
     */
    data class CallSite(
        val image: String?,
        val symbol: String?,
        val imageOffset: Long?,
        val stackFingerprint: String?,
    )

    private data class Frame(
        val image: String,
        val symbol: String?,
        val imageOffset: Long,
    )

    /** This SDK's own image, resolved once so its frames can be skipped. */
    private val selfImage: String? by lazy { imageOf(staticCFunction<Int, Int> { it }.rawValue.toLong()) }

    /**
     * The image that called into a hook, skipping our own frames.
     *
     * [skip] starts past the hook itself. Frames are walked rather than a fixed index taken,
     * because the number of frames between the hook and the real caller is not constant: the
     * runtime's message dispatch may or may not appear depending on architecture and optimisation.
     */
    fun caller(skip: Int = 1, depth: Int = 12): CallSite = memScoped {
        val frames = allocArray<COpaquePointerVar>(depth)
        val n = backtrace(frames, depth)

        val useful = ArrayList<Frame>(n)
        for (i in skip until n) {
            val address = frames[i]?.rawValue?.toLong() ?: continue
            frameOf(address)?.let(useful::add)
        }

        // Preferred answer: the nearest frame belonging to some image other than this SDK. That is
        // the third-party framework that made the call, which is the whole question being asked.
        val first = useful.firstOrNull { it.image != selfImage } ?: useful.firstOrNull()
        if (first == null) return CallSite(null, null, null, null)

        val fingerprintFrames = useful.asSequence()
            .filter { it.image != selfImage }
            .take(MAX_FINGERPRINT_FRAMES)
            .toList()
            .ifEmpty { listOf(first) }
        CallSite(
            image = first.image,
            symbol = first.symbol?.take(MAX_SYMBOL_LENGTH),
            imageOffset = first.imageOffset,
            stackFingerprint = fingerprint(fingerprintFrames),
        )
    }

    fun callerImage(skip: Int = 1, depth: Int = 12): String? = caller(skip, depth).image

    /** Last path component of the image containing [address], or null if it resolves to nothing. */
    fun imageOf(address: Long): String? = memScoped {
        val info = alloc<Dl_info>()
        val ptr = kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.CPointed>(
            kotlinx.cinterop.nativeNullPtr + address,
        )
        if (dladdr(ptr, info.ptr) == 0) return null
        info.dli_fname?.toKString()?.substringAfterLast('/')
    }

    private fun frameOf(address: Long): Frame? = memScoped {
        val info = alloc<Dl_info>()
        val ptr = kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.CPointed>(
            kotlinx.cinterop.nativeNullPtr + address,
        )
        if (dladdr(ptr, info.ptr) == 0) return null
        val path = info.dli_fname?.toKString() ?: return null
        val base = info.dli_fbase?.rawValue?.toLong() ?: return null
        Frame(
            image = path.substringAfterLast('/'),
            symbol = info.dli_sname?.toKString(),
            imageOffset = (address - base).coerceAtLeast(0),
        )
    }

    /** FNV-1a over normalized frames. Raw process addresses never leave the device. */
    private fun fingerprint(frames: List<Frame>): String {
        var hash = 0xcbf29ce484222325UL
        for (frame in frames) {
            for (c in frame.image) {
                hash = hash xor c.code.toULong()
                hash *= 0x100000001b3UL
            }
            var offset = frame.imageOffset.toULong()
            repeat(8) {
                hash = hash xor (offset and 0xffUL)
                hash *= 0x100000001b3UL
                offset = offset shr 8
            }
        }
        return hash.toString(16).padStart(16, '0')
    }

    private const val MAX_FINGERPRINT_FRAMES = 8
    private const val MAX_SYMBOL_LENGTH = 191
}
