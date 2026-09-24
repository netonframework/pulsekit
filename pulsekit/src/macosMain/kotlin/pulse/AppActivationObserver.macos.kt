package pulse

import platform.Foundation.NSNotificationCenter
import platform.AppKit.NSApplicationDidBecomeActiveNotification
import platform.AppKit.NSApplicationDidResignActiveNotification

/**
 * macOS has no backgrounding in the iOS sense; resigning active is the closest equivalent and is
 * the point at which buffered events should be committed, so it plays the same role here.
 */
internal actual class AppActivationObserver actual constructor(
    onActive: () -> Unit,
    onBackground: () -> Unit,
) {
    private val center = NSNotificationCenter.defaultCenter
    private val activeToken = center.addObserverForName(
        name = NSApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = null,
    ) { onActive() }
    private val inactiveToken = center.addObserverForName(
        name = NSApplicationDidResignActiveNotification,
        `object` = null,
        queue = null,
    ) { onBackground() }

    actual fun close() {
        center.removeObserver(activeToken)
        center.removeObserver(inactiveToken)
    }
}
