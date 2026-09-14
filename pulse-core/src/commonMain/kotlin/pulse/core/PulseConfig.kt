package pulse.core

/**
 * SDK configuration. Capabilities are opt-in so unused ones can be link-time stripped. Transport
 * points at the Pulse ingest endpoint (msgtrans long connection); batching bounds memory and upload
 * frequency.
 */
data class PulseConfig(
    /** Stable App ID/AppKey created in the Pulse console; it is public identification, not a secret. */
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
    // Pre-encoding memory buffer cap; oldest events drop when full (stability over completeness).
    val bufferCapacity: Int = 4_096,
    /** Durable encoded batches retained across process restarts. */
    val outboxMaxBatches: Long = 4_096,
    val outboxMaxBytes: Long = 32L * 1024 * 1024,
    val outboxMaxAgeMs: Long = 7L * 24 * 60 * 60 * 1_000,
    /** First retry delay; subsequent failures use capped exponential backoff. */
    val retryBaseDelayMs: Long = 1_000,
    val retryMaxDelayMs: Long = 5L * 60 * 1_000,
    /**
     * Where the SDK keeps its SQLite outbox and crash record. Defaults to a per-project directory
     * under Application Support on Apple platforms and the user state directory on Linux.
     */
    val storageDir: String? = null,
    /**
     * The host build's own identity, used for the startup update check. Left null the check is
     * skipped entirely — a host that manages its own updates should not be asked about them.
     *
     * [buildNumber] must be the platform's monotonic counter (CFBundleVersion on iOS, versionCode
     * on Android), not a display version: the server compares integers precisely so the comparison
     * rule cannot drift between the two sides.
     */
    val platform: String? = null,
    val packageName: String? = null,
    val buildNumber: Long = 0,
    /** Display version, e.g. "1.4.2"; reported alongside the build number for readability. */
    val appVersion: String? = null,
) {
    /** Preferred product terminology; [projectId] remains the stored name for source compatibility. */
    val appId: String get() = projectId
}
