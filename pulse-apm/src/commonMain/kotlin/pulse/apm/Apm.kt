package pulse.apm

import pulse.core.EventKind
import pulse.core.toEventAttributes
import pulse.core.PulseClient

/**
 * APM capability (U-APM / Bugly class): errors, crashes, ANR, startup and network performance.
 * Automatic crash/signal capture needs platform glue (platform-apple / platform-android) and is a
 * later step; this is the reporting surface the collectors feed, plus manual reporting for hosts.
 *
 * Crashes must be flushed synchronously before the process dies — [reportCrashBlocking] emits and
 * asks the caller to flush (the SDK entry wires that to PulseClient.flushOnce on the crash path).
 */
class Apm(private val client: PulseClient) {

    fun recordError(name: String, message: String, stack: String? = null, attributes: Map<String, Any?> = emptyMap()) =
        client.emit(EventKind.Error, name, (attributes + mapOf("message" to message, "stack" to stack)).toEventAttributes())

    fun recordCrash(name: String, message: String, stack: String?, attributes: Map<String, Any?> = emptyMap()) =
        client.emit(EventKind.Crash, name, (attributes + mapOf("message" to message, "stack" to stack)).toEventAttributes())

    /** A performance sample, e.g. startup time or a network span (millis). */
    fun recordPerformance(name: String, durationMs: Long, attributes: Map<String, Any?> = emptyMap()) =
        client.emit(EventKind.Performance, name, (attributes + mapOf("duration_ms" to durationMs)).toEventAttributes())

    fun recordNetwork(url: String, status: Int, durationMs: Long, attributes: Map<String, Any?> = emptyMap()) =
        client.emit(EventKind.Network, "http_request",
            (attributes + mapOf("url" to url, "status" to status, "duration_ms" to durationMs)).toEventAttributes())
}
