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
    /** Stable across retries of one durable outbox row. */
    val batchId: String? = null,
)

/**
 * The identity fields the server needs, and only those. [Identity] is the mutable client-side
 * holder; this is its wire projection, so adding client-only state to one does not change the
 * contract of the other.
 */
@Serializable
data class WireIdentity(
    /** Stable App ID/AppKey issued by the Pulse console. Kept as projectId on wire for compatibility. */
    val projectId: String,
    val installationId: String,
    val deviceId: String,
    /** Null until the host calls identify(); MAU de-dupes on it when present. */
    val userId: String? = null,
    /**
     * The build this session is running. Carried on the identity rather than per event because it
     * is a property of the run, and because a crash is unreadable without it: symbols are stored
     * per build, so a stack with no build number cannot be resolved against anything.
     */
    val appVersion: String? = null,
    val buildNumber: Long = 0,
    /** Runtime package identity used by the server's per-App package policy. */
    val platform: String? = null,
    val packageName: String? = null,
)

/** The wire projection of this identity. */
fun Identity.toWire(): WireIdentity = WireIdentity(projectId, installationId, deviceId, userId)

/** The wire projection, including the build identity the config carries. */
fun Identity.toWire(config: PulseConfig): WireIdentity =
    WireIdentity(
        projectId = projectId,
        installationId = installationId,
        deviceId = deviceId,
        userId = userId,
        appVersion = config.appVersion,
        buildNumber = config.buildNumber,
        platform = config.platform,
        packageName = config.packageName,
    )
