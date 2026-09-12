package pulse.core

/**
 * SDK configuration. Capabilities are opt-in so unused ones can be link-time stripped. Transport
 * points at the Pulse ingest endpoint (msgtrans long connection); batching bounds memory and upload
 * frequency.
 */
data class PulseConfig(
    val projectId: String,
    val host: String,
    val port: Int = 9600,
    val analytics: Boolean = true,
    val apm: Boolean = true,
    val runtime: Boolean = false,
    // Batch flush triggers.
    val batchMaxEvents: Int = 64,
    val batchMaxBytes: Int = 64 * 1024,
    val flushIntervalMs: Long = 5_000,
    // Local ring buffer cap; oldest events drop when full (stability over completeness).
    val bufferCapacity: Int = 4_096,
)
