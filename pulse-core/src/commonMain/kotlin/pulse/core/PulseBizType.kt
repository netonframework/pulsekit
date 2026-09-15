package pulse.core

/**
 * Pulse application message types carried in msgtrans' one-byte `biz_type` header field.
 *
 * These values are wire ABI. Existing values must never be reused or renumbered. A response uses
 * the same biz type and message id as its request, so response types do not consume another value.
 * Values 1..127 are client-to-server requests; 128..255 are reserved for server-to-client
 * requests. Both peers must answer every Request with a [PulseResponse].
 */
object PulseBizType {
    /** Upload one identity envelope containing an ordered array of telemetry events. */
    const val EVENT_BATCH_UPLOAD: Int = 1

    /** Ask whether the current platform/package/build has a published update. */
    const val APP_UPDATE_CHECK: Int = 2
}
