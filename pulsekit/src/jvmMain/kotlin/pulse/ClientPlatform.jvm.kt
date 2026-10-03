package pulse

import pulse.host.HostLifecycle
import pulse.host.PulseHost
import pulse.runtime.Runtime
import pulse.runtime.reportHostMonitorInstallation
import pulse.runtime.reportHostPrivacyDeclarations
import pulse.runtime.reportHostSigningDeclarations

// The JVM side of the client seams. Everything the framework knows comes from the registered
// [pulse.host.HostEnvironment]; without one (a plain JVM) the SDK still runs and reports what it can.

/** The host's platform name ("android"); "jvm" without a host. */
internal actual val hostPlatform: String get() = PulseHost.environment?.platform ?: "jvm"

/** Unknown here: the Android platform layer reads its PackageInfo and passes the values in. */
internal actual fun hostBuild(): HostBuild = HostBuild(null, 0L, null)

/** A daemon thread: the SDK must never keep a VM alive. It ends when its reactor does. */
internal actual fun startSdkThread(name: String, body: () -> Unit): () -> Unit {
    val thread = Thread(body, name)
    thread.isDaemon = true
    thread.start()
    return {}
}

internal actual fun sleepMillis(millis: Long) = Thread.sleep(millis)

internal actual fun processStartMillis(): Long = PulseHost.environment?.processStartMillis() ?: 0L

internal actual fun residentMemoryBytes(): Long = PulseHost.environment?.residentMemoryBytes() ?: 0L

internal actual fun dispatchToMainThread(block: () -> Unit) {
    PulseHost.environment?.postToMainThread(Runnable { block() })
}

/** Only with a host that has a main thread; a ping nobody can answer would read as a freeze. */
internal actual fun canObserveMainThread(): Boolean = PulseHost.environment?.hasMainThread == true

/**
 * Nothing to arm. iOS replaces Objective-C method implementations; the Android equivalent would be
 * ART method hooking, which PulseKit deliberately does not do. The declarations are still reported,
 * so "watched nothing" is explicit.
 */
internal actual fun armRuntimeMonitor() = Unit

internal actual fun Runtime.reportRuntimeDeclarations() {
    reportHostMonitorInstallation()
    reportHostPrivacyDeclarations()
    reportHostSigningDeclarations()
}

/** Late-loaded bundled libraries; there are no hook observations to drain. */
internal actual fun Runtime.pollRuntimeObservations() {
    scan()
}

internal actual fun currentDeviceType(): String =
    (PulseHost.environment?.deviceType?.trim()?.takeIf(String::isNotEmpty) ?: System.getProperty("os.name") ?: "JVM").take(64)

internal actual class AppActivationObserver actual constructor(
    onActive: (observedAt: Long) -> Unit,
    onBackground: (observedAt: Long) -> Unit,
) {
    private val listener = HostLifecycle.Listener(onActive, onBackground).also(HostLifecycle::add)

    actual fun close() = HostLifecycle.remove(listener)
}

internal actual class AppLifecycleFlushObserver actual constructor(onBackground: () -> Unit) {
    private val listener = HostLifecycle.Listener({}, { onBackground() }).also(HostLifecycle::add)

    actual fun close() = HostLifecycle.remove(listener)
}
