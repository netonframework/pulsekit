package pulse.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import pulse.db.PulseDatabase

/**
 * Durable outbox backed by SQLDelight: the system SQLite on Apple and Linux, the vendored one on
 * Android. The schema, queries and bounds are the same everywhere; only [openOutboxDriver] differs.
 */
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
        openOutboxDriver(PulseDatabase.Schema, storageDir, OUTBOX_DATABASE_NAME)
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

internal const val OUTBOX_DATABASE_NAME = "pulse-outbox.db"

/**
 * Open (creating or migrating) the SQLite database [name] in [storageDir]. NativeSqliteDriver where
 * the system has SQLite; a driver over the vendored library on Android. Both use WAL journaling and
 * a five second busy timeout, and version the schema with `PRAGMA user_version`.
 */
internal expect fun openOutboxDriver(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    storageDir: String,
    name: String,
): SqlDriver

/** Create one directory level; already-exists is not an error. Implemented per platform. */
internal expect fun makeDirectory(path: String)
