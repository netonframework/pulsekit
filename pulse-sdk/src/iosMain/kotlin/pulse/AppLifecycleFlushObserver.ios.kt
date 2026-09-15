package pulse

import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

internal actual class AppLifecycleFlushObserver actual constructor(onBackground: () -> Unit) {
    private val center = NSNotificationCenter.defaultCenter
    private val token = center.addObserverForName(
        name = UIApplicationDidEnterBackgroundNotification,
        `object` = null,
        queue = null,
    ) { onBackground() }

    actual fun close() {
        center.removeObserver(token)
    }
}
