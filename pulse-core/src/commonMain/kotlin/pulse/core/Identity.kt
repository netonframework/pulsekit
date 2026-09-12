package pulse.core

/**
 * Identity graph (see the architecture doc). MAU de-dupes on [userId]; one user may have many
 * installations across platforms. A [Session] is shared by analytics/apm/runtime; a connection id
 * ties it to the msgtrans long connection.
 */
data class Identity(
    val projectId: String,
    val installationId: String,
    val deviceId: String,
    var userId: String? = null,
)

/** One session; created on start/foreground, shared across all capabilities. */
class Session(val id: String, val startedAtMs: Long)
