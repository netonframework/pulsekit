package pulse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The update check runs during app start, so every path that is not a definite "there is a newer
 * published build" has to resolve to letting the user in. A telemetry SDK that can stop an app from
 * starting is a worse failure than a missed update prompt, and these are the cases where that
 * distinction is decided.
 */
class UpdateCheckTest {

    private fun wire(action: String) = UpdateCheckWireResult(
        action = action, latestVersionName = "1.4.2", latestBuildNumber = 142,
        releaseNotes = "notes", downloadUrl = "https://example.test/app",
    )

    @Test
    fun serverActionsMapToTheThreeStates() {
        assertEquals(UpdateAction.None, wire("none").toInfo().action)
        assertEquals(UpdateAction.Optional, wire("optional").toInfo().action)
        assertEquals(UpdateAction.Forced, wire("forced").toInfo().action)
    }

    @Test
    fun anUnrecognisedActionFailsOpen() {
        // A server that grows a fourth action must not brick older clients: anything unknown is
        // treated as "no update" rather than as something to block on.
        val info = wire("quarantine-the-user").toInfo()
        assertEquals(UpdateAction.None, info.action)
        assertFalse(info.isBlocking)
    }

    @Test
    fun onlyForcedBlocks() {
        assertFalse(wire("none").toInfo().isBlocking)
        assertFalse(wire("optional").toInfo().isBlocking)
        assertTrue(wire("forced").toInfo().isBlocking)
    }

    @Test
    fun hasUpdateSeparatesSomethingToShowFromSomethingToEnforce() {
        assertFalse(wire("none").toInfo().hasUpdate)
        assertTrue(wire("optional").toInfo().hasUpdate)
        assertTrue(wire("forced").toInfo().hasUpdate)
    }

    @Test
    fun theDetailsSurviveTheMapping() {
        val info = wire("optional").toInfo()
        assertEquals("1.4.2", info.latestVersionName)
        assertEquals(142L, info.latestBuildNumber)
        assertEquals("notes", info.releaseNotes)
        assertEquals("https://example.test/app", info.downloadUrl)
    }

    @Test
    fun theDefaultIsNoUpdate() {
        // What a host sees when no platform is configured, or the check could not be made at all.
        val info = UpdateInfo()
        assertEquals(UpdateAction.None, info.action)
        assertFalse(info.hasUpdate)
        assertFalse(info.isBlocking)
    }
}
