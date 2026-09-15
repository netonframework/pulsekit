package pulse

import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLifecycleFlushObserverTest {
    @Test
    fun invokesFlushOnBackgroundAndStopsAfterClose() {
        var calls = 0
        val observer = AppLifecycleFlushObserver { calls++ }

        NSNotificationCenter.defaultCenter.postNotificationName(
            UIApplicationDidEnterBackgroundNotification,
            `object` = null,
        )
        assertEquals(1, calls)

        observer.close()
        NSNotificationCenter.defaultCenter.postNotificationName(
            UIApplicationDidEnterBackgroundNotification,
            `object` = null,
        )
        assertEquals(1, calls)
    }
}
