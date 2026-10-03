package pulse

import pulse.runtime.Runtime

/**
 * What the host-app SDK needs from its platform, and nothing more. [PulseSDK] and
 * [AutoInstrumentation] are shared line for line between iOS/macOS (Kotlin/Native) and Android (the
 * JVM); everything that genuinely differs between UIKit and the Android framework sits behind these
 * declarations.
 */

/**
 * Run [body] — the SDK's reactor — on a new thread named [name]. Returns the thread's stop request,
 * called once the reactor has been told to stop; it never interrupts [body].
 */
internal expect fun startSdkThread(name: String, body: () -> Unit): () -> Unit

/** The host build's own identity as the OS reports it; what the update check compares against. */
internal class HostBuild(val packageName: String?, val buildNumber: Long, val appVersion: String?)

/**
 * This app's package, monotonic build counter and display version, so a host does not have to
 * pass them. Unknown parts are null / 0. On the JVM the platform layer passes them itself.
 */
internal expect fun hostBuild(): HostBuild

/** Block the calling (host) thread for about [millis] ms; only the bounded waits use this. */
internal expect fun sleepMillis(millis: Long)

/** The platform name sent on registration and on every batch: "ios" or "android". */
internal expect val hostPlatform: String

/** When the kernel started this process, epoch ms, or 0 if it cannot be read. */
internal expect fun processStartMillis(): Long

/** The process's memory footprint in bytes as the OS accounts for it, or 0 if unavailable. */
internal expect fun residentMemoryBytes(): Long

/** Run [block] on the app's main (UI) thread, asynchronously. */
internal expect fun dispatchToMainThread(block: () -> Unit)

/** Whether [dispatchToMainThread] can currently reach the main thread at all. */
internal expect fun canObserveMainThread(): Boolean

/**
 * Arm whatever runtime observation this platform has, synchronously and before any I/O: launch is
 * exactly when an advertising or analytics SDK reads the identifiers it came for.
 */
internal expect fun armRuntimeMonitor()

/**
 * Report, once per start, what the monitor ended up watching and what the final signed build
 * declares. Needs the client, so it runs after [Pulse.start].
 */
internal expect fun Runtime.reportRuntimeDeclarations()

/** One runtime polling tick: late-loaded modules and whatever the monitor observed since. */
internal expect fun Runtime.pollRuntimeObservations()
