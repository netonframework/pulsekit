package pulse.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pulse.host.PulseHost

/**
 * What the installed package declares, as the platform layer reads it (on Android from
 * PackageManager) and hands over as JSON through [pulse.host.HostEnvironment.factsJson].
 */
@Serializable
internal data class HostFacts(
    val packageName: String? = null,
    /** ApplicationInfo.sourceDir, the installed base APK; its directory is the install root. */
    val sourceDir: String? = null,
    val targetSdk: Int = 0,
    val minSdk: Int = 0,
    /** FLAG_DEBUGGABLE, the counterpart of the get-task-allow entitlement. */
    val debuggable: Boolean = false,
    /** The installing package (store, MDM, adb / sideload), when the platform says. */
    val installer: String? = null,
    /** uses-permission entries of the final merged manifest. */
    val requestedPermissions: List<String> = emptyList(),
    /** The subset granted when the facts were read. */
    val grantedPermissions: List<String> = emptyList(),
    /** "present", "not_signed" or "unavailable". */
    val signingStatus: String = "unavailable",
    /** SHA-256 of each current signing certificate, lowercase hex. */
    val signers: List<String> = emptyList(),
    /** Whether the signing key has been rotated (APK Signature Scheme v3 lineage). */
    val signingKeyRotated: Boolean = false,
)

private val factsJson = Json { ignoreUnknownKeys = true }

/** The host's facts, or empty ones when there is no host or its JSON cannot be read. */
internal fun hostFacts(): HostFacts {
    val text = try { PulseHost.environment?.factsJson() } catch (_: Exception) { null } ?: return HostFacts()
    return try { factsJson.decodeFromString(HostFacts.serializer(), text) } catch (_: Exception) { HostFacts() }
}

// The Android counterparts of the iOS declaration events, under the same names so the console's
// evidence views read both platforms. The client states facts; the server judges them.

/**
 * `runtime_monitor_installed` with every count at zero: no API hooks on this platform (see
 * armRuntimeMonitor), and saying so is the point — "watched nothing" must not read as "saw nothing".
 */
internal fun Runtime.reportHostMonitorInstallation() = recordBehavior(
    name = "runtime_monitor_installed",
    attributes = mapOf(
        "expected_count" to 0,
        "hooked_count" to 0,
        "pending_count" to 0,
        "observation_capacity" to 0,
        "dropped_observations_total" to 0L,
        "watching" to "",
        "pending" to "",
    ),
)

/** `privacy_declarations`: requested permissions (the counterpart of UsageDescription keys) and grants. */
internal fun Runtime.reportHostPrivacyDeclarations() {
    val facts = hostFacts()
    val requested = facts.requestedPermissions.distinct().sorted()
    val granted = facts.grantedPermissions.distinct().sorted()
    recordBehavior(
        "privacy_declarations",
        attributes = buildMap {
            facts.packageName?.let { put("package_name", it.take(MAX_VALUE_LENGTH)) }
            put("target_sdk", facts.targetSdk)
            put("min_sdk", facts.minSdk)
            put("permission_count", requested.size)
            put("permission_keys", requested.take(MAX_ITEMS).joinToString(","))
            put("granted_permission_count", granted.size)
            put("granted_permission_keys", granted.take(MAX_ITEMS).joinToString(","))
        },
    )
}

/** `signing_declarations`: who signed the installed package and how it got there. */
internal fun Runtime.reportHostSigningDeclarations() {
    val facts = hostFacts()
    val signers = facts.signers.distinct()
    recordBehavior(
        "signing_declarations",
        attributes = buildMap {
            put("signature_status", facts.signingStatus)
            put("signer_count", signers.size)
            put("signer_sha256", signers.take(MAX_ITEMS).joinToString(","))
            put("signing_key_rotated", facts.signingKeyRotated)
            put("debuggable", facts.debuggable)
            facts.installer?.let { put("installer_package", it.take(MAX_VALUE_LENGTH)) }
            facts.packageName?.let { put("application_identifier", it.take(MAX_VALUE_LENGTH)) }
        },
    )
}

private const val MAX_ITEMS = 64
private const val MAX_VALUE_LENGTH = 191
