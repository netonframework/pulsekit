package pulse

import platform.Foundation.NSNotificationCenter
import pulse.core.nowMillis
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

/**
 * didBecomeActive rather than didFinishLaunching: the app being on screen and able to respond is
 * what a user calls "started", and it is the only one of the two that also fires on a return from
 * the background, which is what makes the same observer usable for session shape.
 */
internal actual class AppActivationObserver actual constructor(
    onActive: (observedAt: Long) -> Unit,
    onBackground: (observedAt: Long) -> Unit,
) {
    private val center = NSNotificationCenter.defaultCenter
    private val activeToken = center.addObserverForName(
        name = UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = null,
    ) { onActive(nowMillis()) }
    private val backgroundToken = center.addObserverForName(
        name = UIApplicationDidEnterBackgroundNotification,
        `object` = null,
        queue = null,
    ) { onBackground(nowMillis()) }

    actual fun close() {
        center.removeObserver(activeToken)
        center.removeObserver(backgroundToken)
    }
}
