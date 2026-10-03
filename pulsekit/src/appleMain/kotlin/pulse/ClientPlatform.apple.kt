@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package pulse

import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import pulse.runtime.Runtime
import pulse.runtime.SensitiveApiMonitor
import pulse.runtime.reportMonitorInstallation
import pulse.runtime.reportObjectiveCMethodInventory
import pulse.runtime.reportPrivacyDeclarations
import pulse.runtime.reportSensitiveApiObservations
import pulse.runtime.reportSigningDeclarations
import pulse.runtime.retryPendingHooks

internal actual val hostPlatform: String = "ios"

internal actual fun hostBuild(): HostBuild {
    val bundle = platform.Foundation.NSBundle.mainBundle
    val info = bundle.infoDictionary
    return HostBuild(
        packageName = bundle.bundleIdentifier,
        buildNumber = (info?.get("CFBundleVersion") as? String)?.toLongOrNull() ?: 0L,
        appVersion = info?.get("CFBundleShortVersionString") as? String,
    )
}

internal actual fun startSdkThread(name: String, body: () -> Unit): () -> Unit {
    val worker = Worker.start(name = name)
    worker.execute(TransferMode.SAFE, { body }) { it() }
    return { worker.requestTermination(processScheduledJobs = false) }
}

internal actual fun sleepMillis(millis: Long) {
    platform.posix.usleep((millis * 1000).toUInt())
}

internal actual fun dispatchToMainThread(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue()) { block() }
}

/** The main queue always exists on Apple platforms. */
internal actual fun canObserveMainThread(): Boolean = true

internal actual fun armRuntimeMonitor() {
    SensitiveApiMonitor.install()
}

internal actual fun Runtime.reportRuntimeDeclarations() {
    reportMonitorInstallation()
    reportPrivacyDeclarations()
    reportSigningDeclarations()
    // Inventory does not replace or call arbitrary methods. It reads the ObjC runtime metadata once
    // so unexecuted plugin entry points remain auditable.
    reportObjectiveCMethodInventory()
}

/**
 * Two things are polled: images loaded after launch, which the baseline by definition cannot show,
 * and sensitive API calls, which are collapsed per (api, caller) so a callback firing continuously
 * costs one event per tick instead of thousands.
 */
internal actual fun Runtime.pollRuntimeObservations() {
    retryPendingHooks()
    scan()
    reportSensitiveApiObservations()
}
