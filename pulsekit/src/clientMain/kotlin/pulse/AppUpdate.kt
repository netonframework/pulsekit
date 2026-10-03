package pulse

import pulse.core.UpdateAction
import pulse.core.UpdateInfo

/**
 * The update check's answer, in the shape an Apple host sees it.
 *
 * This exists because the Objective-C exporter names a class after its Kotlin package: the core
 * type surfaces to Swift as `Pulse_coreUpdateInfo`, which is not an API to hand a host app. Types
 * declared in the framework's root package keep their short name, so this one arrives as
 * `AppUpdate` / `AppUpdateAction`.
 */
data class AppUpdate(
    val action: AppUpdateAction,
    val latestVersionName: String?,
    val latestBuildNumber: Long,
    val releaseNotes: String,
    val downloadUrl: String?,
) {
    /** The host must not let the user into business features until they update. */
    val isBlocking: Boolean get() = action == AppUpdateAction.Forced

    /** There is something worth showing the user at all. */
    val hasUpdate: Boolean get() = action != AppUpdateAction.None
}

/** Three states, because "current", "newer exists" and "newer required" are genuinely distinct. */
enum class AppUpdateAction { None, Optional, Forced }

/**
 * 0 rather than null for a missing build number: Objective-C cannot express a nullable Long
 * without boxing it into an NSNumber, and a host comparing against 0 is clearer than one
 * unwrapping an optional that only appears when there is no update anyway.
 */
internal fun UpdateInfo.toAppUpdate(): AppUpdate = AppUpdate(
    action = when (action) {
        UpdateAction.Forced -> AppUpdateAction.Forced
        UpdateAction.Optional -> AppUpdateAction.Optional
        UpdateAction.None -> AppUpdateAction.None
    },
    latestVersionName = latestVersionName,
    latestBuildNumber = latestBuildNumber ?: 0L,
    releaseNotes = releaseNotes,
    downloadUrl = downloadUrl,
)
