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
    /**
     * Where the SDK keeps its few on-disk artefacts (currently only the crash record). Defaults to
     * a per-project directory under the system temp dir; a host with its own sandbox layout should
     * point this at a directory it controls and that survives restarts.
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
)
