package pulse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The point of persisting these ids is that a second launch is recognised as the same device.
 * Before this existed, `Pulse.start` called newId() every time, so every launch looked like a new
 * device — DAU counted launches and crash-per-device was meaningless.
 */
class PersistentIdTest {

    @Test
    fun theDeviceIdIsTheSameOnASecondRead() {
        val first = persistentDeviceId()
        val second = persistentDeviceId()
        assertEquals(first, second, "the device id changed between reads; it is not being persisted")
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun theInstallationIdIsSeparateFromTheDeviceId() {
        // Distinct keys, so a reinstall can be told apart from a new device.
        assertNotEquals(persistentDeviceId(), persistentInstallationId())
    }

    @Test
    fun thestoreRoundTripsAnArbitraryValue() {
        val key = "pulse.test.roundtrip"
        val value = newId()
        PersistentStore.put(key, value)
        assertEquals(value, PersistentStore.get(key))
        // Overwrite must win, otherwise a rotated id would be silently ignored.
        val second = newId()
        PersistentStore.put(key, second)
        assertEquals(second, PersistentStore.get(key))
    }

    @Test
    fun anUnknownKeyIsNull() = assertEquals(null, PersistentStore.get("pulse.test.never.written"))
}
