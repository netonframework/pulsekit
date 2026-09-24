@file:OptIn(ExperimentalForeignApi::class)

package pulse.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval
import platform.posix.mkdir
import kotlinx.cinterop.convert

actual fun nowMillis(): Long = memScoped {
    val tv = alloc<timeval>()
    gettimeofday(tv.ptr, null)
    tv.tv_sec.toLong() * 1000L + tv.tv_usec.toLong() / 1000L
}

/** mkdir(2) with 0755. mode_t is 16-bit on Apple and 32-bit on Linux, hence per platform. */
internal actual fun makeDirectory(path: String) {
    mkdir(path, 493.convert())
}
