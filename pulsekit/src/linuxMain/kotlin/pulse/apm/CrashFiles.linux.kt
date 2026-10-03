@file:OptIn(ExperimentalForeignApi::class)

package pulse.apm

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.SIG_DFL
import platform.posix.mkdir
import platform.posix.open
import platform.posix.signal

internal actual fun makeDirectory(path: String) {
    mkdir(path, 493.convert())
}

internal actual fun openRecordFile(path: String): Int =
    open(path, O_WRONLY or O_CREAT or O_TRUNC, 420) // vararg mode: a plain Int is accepted on both platforms

internal actual fun armCrashSignal(sig: Int, handler: CPointer<CFunction<(Int) -> Unit>>) {
    signal(sig, handler)
}

internal actual fun restoreCrashSignal(sig: Int) {
    signal(sig, SIG_DFL)
}
