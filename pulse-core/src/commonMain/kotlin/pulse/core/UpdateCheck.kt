package pulse.core

import kotlinx.serialization.Serializable

/**
 * What the app should do about its own version, as decided by the server.
 *
 * Three states rather than a pair of booleans: "current", "a newer build exists" and "a newer build
 * exists and this one may no longer be used" are genuinely three outcomes, and two flags would
 * admit a fourth combination that means nothing.
 */
@Serializable
enum class UpdateAction {
    /** Nothing to do. Also what an unknown app, a disabled app or a failed check resolves to. */
    None,

    /** A newer published build exists; the host may prompt and the user may decline. */
    Optional,

    /** A newer published build is mandatory; the host must not let the user into business features. */
    Forced,
}

/** The server's answer to a startup update check. */
@Serializable
data class UpdateInfo(
    val action: UpdateAction = UpdateAction.None,
    val latestVersionName: String? = null,
    val latestBuildNumber: Long? = null,
    val releaseNotes: String = "",
    val downloadUrl: String? = null,
) {
    /** True when the host must block its business features until the user updates. */
    val isBlocking: Boolean get() = action == UpdateAction.Forced

    /** True when there is something worth showing the user at all. */
    val hasUpdate: Boolean get() = action != UpdateAction.None
}

/**
 * What the client tells the server about itself. The platform and package name come from the build;
 * the build number is the host's own monotonic counter (CFBundleVersion on iOS, versionCode on
 * Android), which is what the server compares against.
 */
@Serializable
data class UpdateCheckRequest(
    val platform: String,
    val appKey: String? = null,
    val packageName: String? = null,
    val buildNumber: Long = 0,
)

/**
 * The wire shape the server answers with. Kept separate from [UpdateInfo] so the server's string
 * action codes can change without touching the enum the host programs against.
 */
@Serializable
internal data class UpdateCheckWireResult(
    val action: String = "none",
    val latestVersionName: String? = null,
    val latestBuildNumber: Long? = null,
    val releaseNotes: String = "",
    val downloadUrl: String? = null,
) {
    fun toInfo(): UpdateInfo = UpdateInfo(
        action = when (action) {
            "forced" -> UpdateAction.Forced
            "optional" -> UpdateAction.Optional
            // Anything unrecognised is treated as "no update": a telemetry SDK must never be the
            // reason an app refuses to start, so an unknown code fails open.
            else -> UpdateAction.None
        },
        latestVersionName = latestVersionName,
        latestBuildNumber = latestBuildNumber,
        releaseNotes = releaseNotes,
        downloadUrl = downloadUrl,
    )
}
