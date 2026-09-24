package pulse.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import pulse.db.PulseDatabase

/** Durable iOS/Android-ready outbox backed by SQLDelight and the platform SQLite library. */
class SqlDelightEventOutbox(
    storageDir: String,
    private val maxBatches: Long = 4_096,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1_000,
) : EventOutbox {
    init {
        require(maxBatches > 0) { "maxBatches must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxAgeMs > 0) { "maxAgeMs must be positive" }
    }

    private val driver = run {
        ensureDirectories(storageDir)
        NativeSqliteDriver(
            schema = PulseDatabase.Schema,
            name = "pulse-outbox.db",
            onConfiguration = { config ->
                config.copy(extendedConfig = config.extendedConfig.copy(basePath = storageDir))
            },
        )
    }
    private val queries = PulseDatabase(driver).pulseOutboxQueries

    override fun enqueue(batchId: String, payload: ByteArray, compression: Int, eventCount: Int, nowMs: Long) {
        queries.transaction {
            queries.enqueue(batchId, payload, compression.toLong(), eventCount.toLong(), nowMs, nowMs)
            enforceBounds()
        }
    }

    override fun oldestDue(nowMs: Long): OutboxBatch? = queries.oldestDue(nowMs) { sequence, batchId,
        payload, compression, eventCount, createdAt, nextAttemptAt, attemptCount ->
        OutboxBatch(sequence, batchId, payload, compression.toInt(), eventCount.toInt(), createdAt,
            nextAttemptAt, attemptCount.toInt())
    }.executeAsOneOrNull()

    override fun acknowledge(sequence: Long) { queries.acknowledge(sequence) }
    override fun markRetry(sequence: Long, nextAttemptAtMs: Long) { queries.markRetry(nextAttemptAtMs, sequence) }
    override fun prune(nowMs: Long) { queries.deleteExpired(nowMs - maxAgeMs) }
    override fun hasPending(): Boolean = queries.countBatches().executeAsOne() > 0
    override fun close() = driver.close()

    private fun enforceBounds() {
        queries.deleteExpired(nowMillis() - maxAgeMs)
        val count = queries.countBatches().executeAsOne()
        if (count > maxBatches) queries.deleteOldest(count - maxBatches)
        var bytes = queries.totalPayloadBytes().executeAsOne()
        while (bytes > maxBytes && queries.countBatches().executeAsOne() > 1) {
            queries.deleteOldest(1)
            bytes = queries.totalPayloadBytes().executeAsOne()
        }
    }

    private fun ensureDirectories(path: String) {
        if (path.isEmpty()) return
        var cursor = if (path.startsWith('/')) 1 else 0
        while (cursor <= path.length) {
            val slash = path.indexOf('/', cursor).let { if (it < 0) path.length else it }
            val part = path.substring(0, slash)
            if (part.isNotEmpty()) makeDirectory(part) // EEXIST is expected on restart.
            if (slash == path.length) break
            cursor = slash + 1
        }
    }
}

/** Create one directory level; already-exists is not an error. Implemented per platform. */
internal expect fun makeDirectory(path: String)
