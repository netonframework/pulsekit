package pulse.core

import kotlin.random.Random

// nowMillis() is implemented per platform (Platform.apple.kt / Platform.linux.kt): timeval's
// field widths differ between Apple and Linux, and the shared native source set is compiled once
// against the commonized libc, which cannot express that.

private const val HEX = "0123456789abcdef"

/** Random 128-bit id rendered as 32 hex chars. Not RFC-4122 formatted; uniqueness is what matters. */
actual fun newId(): String {
    val sb = StringBuilder(32)
    repeat(32) { sb.append(HEX[Random.nextInt(16)]) }
    return sb.toString()
}
