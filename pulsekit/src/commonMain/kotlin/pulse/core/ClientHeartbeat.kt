package pulse.core

import kotlinx.serialization.Serializable

/** Periodic liveness request on an admitted Pulse connection. */
@Serializable
data class ClientHeartbeatRequest(
    val connectionId: String,
    val clientTimeMs: Long,
    val sdkVersion: String = ClientConnectRequest.SDK_VERSION,
)

@Serializable
data class ClientHeartbeatResult(
    val accepted: Boolean,
    val serverTimeMs: Long,
    val configRevision: Long = 0,
    val heartbeatIntervalMs: Long = 60_000,
    /** Same meaning as on the connect result; repeated here so a policy change reaches a
     * long-lived connection without waiting for it to drop. */
    val collectKinds: List<String> = emptyList(),
)
