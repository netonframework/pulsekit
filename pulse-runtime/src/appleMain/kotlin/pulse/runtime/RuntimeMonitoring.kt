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
    val health = SensitiveApiMonitor.health()
    val watching = SensitiveApiMonitor.watching
    recordBehavior(
        name = "runtime_monitor_installed",
        attributes = mapOf(
            "expected_count" to health.expectedCount,
            "hooked_count" to health.hookedCount,
            "pending_count" to health.pending.size,
            "watching" to watching.map { "${it.className}.${it.selector}" }.distinct().joinToString(","),
            "pending" to health.pending.map { "${it.className}.${it.selector}" }.joinToString(","),
        ),
    )
    return health.hookedCount
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
/**
 * Retry hooks that could not be installed yet and report the watch list if it grew.
 *
 * Called on the same tick as the rest of the runtime polling. A framework that loads ten seconds
 * into a session is hookable ten seconds into a session, and saying so is worth an event: the
 * difference between "we watched and saw nothing" and "we were not watching" is the whole answer
 * to a privacy review.
 */
fun Runtime.retryPendingHooks(): Int {
    val added = SensitiveApiMonitor.installPending()
    if (added > 0) reportMonitorInstallation()
    return added
}

fun Runtime.reportSensitiveApiObservations(): Int {
    val observations = SensitiveApiMonitor.drain()
    for (o in observations) {
        recordBehavior(
            name = o.eventName,
            module = o.callerImage,
            attributes = buildMap {
                put("api_class", o.className)
                put("api_selector", o.selector)
                // Count, not one event per call: frequency is information, volume is noise.
                put("call_count", o.count)
                put("first_seen_ms", o.firstSeenMs)
                put("last_seen_ms", o.lastSeenMs)
                // Present only when the API has something worth naming — the destination of a
                // network request, for instance. Query strings and fragments are already stripped
                // by the time it gets here; see NetworkDetail.
                o.detail?.let { put("detail", it) }
            },
        )
    }
    return observations.size
}
