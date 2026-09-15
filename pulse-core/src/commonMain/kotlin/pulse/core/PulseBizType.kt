package pulse.core

/**
 * Pulse application message types carried in msgtrans' one-byte `biz_type` header field.
 *
 * These values are wire ABI. Existing values must never be reused or renumbered. A response uses
 * the same biz type and message id as its request, so response types do not consume another value.
 * Direction is part of the application contract rather than encoded in the number range. Both
 * peers must answer every Request with a [PulseResponse].
 */
object PulseBizType {
    /** Invalid/default value. It must never be sent as an application request. */
    const val RESERVED: Int = 0

    /** Upload one identity envelope containing an ordered array of telemetry events. */
    const val EVENT_BATCH_UPLOAD: Int = 1

    /** Ask whether the current platform/package/build has a published update. */
    const val APP_UPDATE_CHECK: Int = 2

    /** Register and authenticate a connection so the server can address this SDK instance. */
    const val SESSION_REGISTER: Int = 3

    /** Upload one or more high-priority crash reports through the crash processing pipeline. */
    const val CRASH_BATCH_UPLOAD: Int = 4

    /** Pull the current sampling, audit and collection policy when its revision changes. */
    const val CONFIG_PULL: Int = 5

    /** Keep a registered mobile connection alive and report its current configuration revision. */
    const val HEARTBEAT: Int = 6

    /** Bidirectional routed control request; individual commands live in the payload route. */
    const val CONTROL_RPC: Int = 7

}
