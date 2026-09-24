@file:OptIn(ExperimentalForeignApi::class)

package pulse.apm

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.mkdir
import platform.posix.open

internal actual fun makeDirectory(path: String) {
    mkdir(path, 493.convert())
}

internal actual fun openRecordFile(path: String): Int =
    open(path, O_WRONLY or O_CREAT or O_TRUNC, 420) // vararg mode: a plain Int is accepted on both platforms
