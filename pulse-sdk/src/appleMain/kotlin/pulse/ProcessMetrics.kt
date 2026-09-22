@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pulse

import pulse.procmetrics.pulse_phys_footprint
import pulse.procmetrics.pulse_process_start_ms

/**
 * Process facts the kernel already keeps, read directly rather than approximated.
 *
 * Both could be guessed at from inside the app — take a timestamp in main(), count your own
 * allocations — and both guesses would be wrong in the direction that matters: main() runs after
 * dyld and the runtime have already spent the time being measured, and an allocation counter never
 * sees what the OS actually charges the process for.
 */

/** When the kernel started this process, epoch ms, or 0 if it cannot be read. */
internal fun processStartMillis(): Long = pulse_process_start_ms()

/** Physical footprint in bytes — the number iOS jetsams on — or 0 if unavailable. */
internal fun residentMemoryBytes(): Long = pulse_phys_footprint()
