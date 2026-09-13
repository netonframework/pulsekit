package pulse.core

import kotlinx.serialization.Serializable

/**
 * What actually goes on the wire: the events plus the identity they belong to.
 *
 * The identity is sent once per batch, not once per event. It is the same for every event in a
 * batch by construction, and repeating a device id across sixty-four events is pure overhead on a
 * mobile uplink.
 *
 * Without this the server only ever saw a session id, so a persisted device id had no effect on
 * anything: DAU counted sessions and crash-per-device could not be computed at all.
 */
@Serializable
data class EventBatch(
    val identity: WireIdentity,
    val events: List<Event>,
)

/**
 * The identity fields the server needs, and only those. [Identity] is the mutable client-side
 * holder; this is its wire projection, so adding client-only state to one does not change the
 * contract of the other.
 */
@Serializable
data class WireIdentity(
    val projectId: String,
    val installationId: String,
    val deviceId: String,
    /** Null until the host calls identify(); MAU de-dupes on it when present. */
    val userId: String? = null,
)

/** The wire projection of this identity. */
fun Identity.toWire(): WireIdentity = WireIdentity(projectId, installationId, deviceId, userId)
