package pulse.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostLifecycleTest {

    @Test
    fun aListenerAddedWhileActiveLearnsWhenTheAppBecameActive() {
        HostLifecycle.dispatchBackground()
        val before = System.currentTimeMillis()
        HostLifecycle.dispatchActive()
        Thread.sleep(20)
        val seen = mutableListOf<Pair<String, Long>>()
        val late = HostLifecycle.Listener({ seen += "active" to it }, { seen += "background" to it })
        HostLifecycle.add(late)
        try {
            assertEquals(1, seen.size, "the late listener must be told the app is active, once")
            val (kind, at) = seen.single()
            assertEquals("active", kind)
            assertTrue(at in before..before + 15, "replayed with the original time, not the time of adding ($at)")
            HostLifecycle.dispatchBackground()
            assertEquals("background", seen.last().first)
        } finally {
            HostLifecycle.remove(late)
        }
    }

    @Test
    fun aListenerAddedInTheBackgroundHearsNothingUntilTheNextTransition() {
        HostLifecycle.dispatchBackground()
        val seen = mutableListOf<String>()
        val listener = HostLifecycle.Listener({ seen += "active" }, { seen += "background" })
        HostLifecycle.add(listener)
        try {
            assertTrue(seen.isEmpty())
            HostLifecycle.dispatchActive()
            assertEquals(listOf("active"), seen)
        } finally {
            HostLifecycle.remove(listener)
            HostLifecycle.dispatchBackground()
        }
    }
}
