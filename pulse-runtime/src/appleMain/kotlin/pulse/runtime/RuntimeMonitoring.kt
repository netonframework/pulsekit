package pulse.runtime

import pulse.core.EventKind
import pulse.core.EventSource
import pulse.core.toEventAttributes

/**
 * Bridges the Apple-only [SensitiveApiMonitor] into the platform-neutral [Runtime] capability.
 *
 * Kept out of [Runtime] itself so the common surface stays free of anything that only exists on
 * one platform: an Android implementation will hook different machinery entirely, and it should
 * not have to satisfy an interface shaped around the Objective-C runtime.
 */

/**
 * Arm the hooks and report what this build ended up watching.
 *
 * The install event is not debug output: which selectors were hooked depends on which frameworks
 * the host actually links, so without it a silent monitor is indistinguishable from a monitor that
 * found nothing to report. "We watched these six APIs and saw nothing" and "we watched nothing"
 * are very different answers to a privacy review.
 */
fun Runtime.installSensitiveApiMonitor(apis: List<SensitiveApi> = SensitiveApiMonitor.DEFAULT_APIS): Int {
    SensitiveApiMonitor.install(apis)
    return reportMonitorInstallation()
}

/**
 * Report what the monitor ended up watching. Separate from arming it because the two happen at
 * different moments: hooks go in as early as possible, while reporting needs a client that only
 * exists once the pipeline is up.
 */
fun Runtime.reportMonitorInstallation(): Int {
    val hooked = SensitiveApiMonitor.watching.size
    recordBehavior(
        name = "runtime_monitor_installed",
        attributes = mapOf(
            "hooked_count" to hooked,
            "watching" to SensitiveApiMonitor.watching.map { it.eventName }.distinct().joinToString(","),
        ),
    )
    return hooked
}

/**
 * Turn everything observed since the last call into events, one per (api, caller).
 *
 * Reported on a timer rather than per call. A pasteboard read inside a table-view cell or a
 * location callback at 1 Hz would otherwise produce an event per invocation, and the cost of that
 * lands on the user's battery and data plan before it ever reaches the server.
 *
 * The event carries what happened and who did it, and no judgement about either — the server
 * decides what any of it means.
 */
fun Runtime.reportSensitiveApiObservations(): Int {
    val observations = SensitiveApiMonitor.drain()
    for (o in observations) {
        recordBehavior(
            name = o.eventName,
            module = o.callerImage,
            attributes = mapOf(
                "api_class" to o.className,
                "api_selector" to o.selector,
                // Count, not one event per call: frequency is information, volume is noise.
                "call_count" to o.count,
                "first_seen_ms" to o.firstSeenMs,
            ),
        )
    }
    return observations.size
}
