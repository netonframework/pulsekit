package pulse.analytics

import pulse.core.EventKind
import pulse.core.toEventAttributes
import pulse.core.PulseClient

/**
 * Analytics capability (U-App class): custom business events, plus the automatic lifecycle events
 * (install / launch / foreground / session). Business code calls [track] / [identify]; lifecycle is
 * emitted by the SDK. All go through the one Event model via [PulseClient].
 */
class Analytics(private val client: PulseClient) {

    /** Emitted once per process start. */
    fun appLaunch(attributes: Map<String, Any?> = emptyMap()) =
        client.emit(EventKind.Analytics, "app_launch", attributes.toEventAttributes())

    /** A new foreground session began. */
    fun sessionStart() {
        client.newSession()
        client.emit(EventKind.Analytics, "session_start")
    }

    /** A business event, e.g. track("purchase", mapOf("amount" to 199)). */
    fun track(name: String, attributes: Map<String, Any?> = emptyMap()) =
        client.emit(EventKind.Analytics, name, attributes.toEventAttributes())

    /** Set/replace the user identity for MAU de-dup and cross-capability joins. */
    fun identify(userId: String) { client.identity.userId = userId }
}

