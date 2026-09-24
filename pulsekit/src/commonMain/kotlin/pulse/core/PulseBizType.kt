package pulse.core

/**
 * Pulse application message types carried in msgtrans' one-byte `biz_type` header field.
 *
 * These values are the wire ABI for Pulse protocol version 2 and later. A response uses
 * the same biz type and message id as its request, so response types do not consume another value.
 * Direction is part of the application contract rather than encoded in the number range. Both
 * peers must answer every Request with a [PulseResponse].
 */
object PulseBizType {
    /** Invalid/default value. It must never be sent as an application request. */
    const val RESERVED: Int = 0

    /** Mandatory first request: identify the client, advertise capabilities and establish policy. */
    const val CLIENT_CONNECT: Int = 1

    /** Keep an admitted client routable and reconcile its applied configuration revision. */
    const val CLIENT_HEARTBEAT: Int = 2

    /** Pull the current sampling, audit, collection and endpoint policy. */
    const val CLIENT_CONFIG_PULL: Int = 3

    /** Upload one or more high-priority crash reports through the crash processing pipeline. */
    const val CLIENT_CRASH_BATCH_UPLOAD: Int = 4

    /** Upload one identity envelope containing an ordered array of telemetry events. */
    const val CLIENT_EVENT_BATCH_UPLOAD: Int = 5

    /** Ask whether the current platform/package/build has a published update. */
    const val CLIENT_APP_UPDATE_CHECK: Int = 6

    /** Bidirectional routed control request; individual commands live in the payload route. */
    const val BIDIRECTIONAL_CONTROL_RPC: Int = 7

}
