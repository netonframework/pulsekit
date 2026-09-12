@file:OptIn(ExperimentalForeignApi::class)

package pulse.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval
import kotlin.random.Random

actual fun nowMillis(): Long = memScoped {
    val tv = alloc<timeval>()
    gettimeofday(tv.ptr, null)
    tv.tv_sec.toLong() * 1000L + tv.tv_usec.toLong() / 1000L
}

private const val HEX = "0123456789abcdef"

/** Random 128-bit id rendered as 32 hex chars. Not RFC-4122 formatted; uniqueness is what matters. */
actual fun newId(): String {
    val sb = StringBuilder(32)
    repeat(32) { sb.append(HEX[Random.nextInt(16)]) }
    return sb.toString()
}
