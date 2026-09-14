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

    /** This SDK's own image, resolved once so its frames can be skipped. */
    private val selfImage: String? by lazy { imageOf(staticCFunction<Int, Int> { it }.rawValue.toLong()) }

    /**
     * The image that called into a hook, skipping our own frames.
     *
     * [skip] starts past the hook itself. Frames are walked rather than a fixed index taken,
     * because the number of frames between the hook and the real caller is not constant: the
     * runtime's message dispatch may or may not appear depending on architecture and optimisation.
     */
    fun callerImage(skip: Int = 1, depth: Int = 8): String? = memScoped {
        val frames = allocArray<COpaquePointerVar>(depth)
        val n = backtrace(frames, depth)

        // Preferred answer: the nearest frame belonging to some image other than this SDK. That is
        // the third-party framework that made the call, which is the whole question being asked.
        for (i in skip until n) {
            val image = frames[i]?.rawValue?.toLong()?.let { imageOf(it) } ?: continue
            if (image != selfImage) return image
        }

        // Fallback: everything on the stack is in one image. That happens when the SDK is linked
        // statically into the host rather than shipped as its own framework — and in tests, where
        // the whole binary is one image. Reporting the host is correct there: the call really was
        // made from inside it. Returning null instead would make attribution look broken whenever
        // the SDK is statically linked, which is a supported way to ship it.
        for (i in skip until n) {
            val image = frames[i]?.rawValue?.toLong()?.let { imageOf(it) } ?: continue
            return image
        }
        null
    }

    /** Last path component of the image containing [address], or null if it resolves to nothing. */
    fun imageOf(address: Long): String? = memScoped {
        val info = alloc<Dl_info>()
        val ptr = kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.CPointed>(
            kotlinx.cinterop.nativeNullPtr + address,
        )
        if (dladdr(ptr, info.ptr) == 0) return null
        info.dli_fname?.toKString()?.substringAfterLast('/')
    }
}
