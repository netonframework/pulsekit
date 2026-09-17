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
)
