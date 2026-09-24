@file:OptIn(ExperimentalForeignApi::class)

package pulse.apm

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.backtrace
import pulse.dyld._dyld_get_image_vmaddr_slide

/**
 * Apple's backtrace(3), which its own manual page documents as async-signal-safe — the reason this
 * can run inside the handler at all rather than being reconstructed afterwards from nothing.
 */
internal actual fun captureBacktrace(buffer: CPointer<COpaquePointerVar>, max: Int): Int =
    backtrace(buffer, max)

/**
 * Image 0 is the main executable, and its slide is what every frame in the app's own code is
 * offset by. Frames from system libraries have a different slide and will not resolve against the
 * app's symbols; that is expected and is why the server keeps symbols per image.
 */
internal actual fun imageSlide(): Long = _dyld_get_image_vmaddr_slide(0u).toLong()
