@file:OptIn(ExperimentalForeignApi::class)

package pulse.apm

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Fill [buffer] with up to [max] return addresses and return how many were written.
 *
 * Called from a signal handler, so the implementation must be async-signal-safe and must not
 * allocate. Platforms that cannot do that safely return 0 rather than risk a second fault while
 * handling the first — a crash report with no stack is still a crash report.
 */
internal expect fun captureBacktrace(buffer: CPointer<COpaquePointerVar>, max: Int): Int

/** The main image's ASLR slide, or 0 where the platform does not expose one. */
internal expect fun imageSlide(): Long
