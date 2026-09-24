@file:OptIn(ExperimentalForeignApi::class)

package pulse.apm

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * No backtrace on Linux. glibc has one, but it is not bound in Kotlin/Native's posix klib and it
 * is not async-signal-safe on the first call (it dlopen's libgcc). Linux is this SDK's server and
 * development host rather than a crash-reporting target, so the honest answer is no frames.
 */
internal actual fun captureBacktrace(buffer: CPointer<COpaquePointerVar>, max: Int): Int = 0

internal actual fun imageSlide(): Long = 0
