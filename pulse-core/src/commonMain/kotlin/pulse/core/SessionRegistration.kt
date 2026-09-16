package pulse.core

import kotlinx.serialization.Serializable

/**
 * First request on every Pulse connection. It binds the durable SDK identity to the transport
 * before uploads, update checks, heartbeats or server-initiated requests are allowed.
 *
 * App ID is public identification rather than a secret. This handshake prevents accidental and
 * cross-App mixing on a live connection; transport encryption/cryptographic attestation remains a
 * separate deployment concern.
 */
@Serializable
data class SessionRegisterRequest(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val appId: String,
    val deviceId: String,
    val installationId: String,
    val platform: String,
    val deviceType: String,
    val packageName: String? = null,
    val appVersion: String? = null,
    val buildNumber: Long = 0,
    val sdkVersion: String = SDK_VERSION,
    val capabilities: List<String> = emptyList(),
) {
    companion object {
        const val CURRENT_PROTOCOL_VERSION: Int = 1
        const val SDK_VERSION: String = "1.0.0"
    }
}

@Serializable
data class SessionRegisterResult(
    val registered: Boolean,
    val protocolVersion: Int,
    val connectionId: String,
    val serverTimeMs: Long,
    val configRevision: Long = 0,
    val heartbeatIntervalMs: Long = 60_000,
)

fun sessionRegisterRequest(config: PulseConfig, identity: Identity): SessionRegisterRequest {
    val platform = config.platform?.trim()?.takeIf(String::isNotEmpty) ?: "unknown"
    return SessionRegisterRequest(
        appId = config.projectId,
        deviceId = identity.deviceId,
        installationId = identity.installationId,
        platform = platform,
        deviceType = config.deviceType?.trim()?.takeIf(String::isNotEmpty) ?: platform,
        packageName = config.packageName,
        appVersion = config.appVersion,
        buildNumber = config.buildNumber,
        capabilities = buildList {
            if (config.analytics) add("analytics")
            if (config.apm) add("apm")
            if (config.runtime) add("runtime_audit")
            add("batch_upload")
            add("compression:${config.uploadCompression.name.lowercase()}")
            add("bidirectional_request")
        },
    )
}
