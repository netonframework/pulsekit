package pulse.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import pulse.host.PulseHost
import java.io.File
import java.util.Properties

actual fun nowMillis(): Long = System.currentTimeMillis()

internal actual fun makeDirectory(path: String) {
    File(path).mkdir()
}

/**
 * `<storageRoot>/PulseKit/<projectId>`: on Android the app's files directory, the counterpart of
 * iOS's `Application Support/PulseKit/<projectId>`. Without a host (a server JVM, tests),
 * `~/.pulsekit`.
 */
actual fun defaultPulseStorageDir(projectId: String): String = "${pulseRoot()}/$projectId"

/**
 * The host's install-scoped store — SharedPreferences on Android, the counterpart of iOS's
 * NSUserDefaults. Without a host, a properties file in the SDK's root directory.
 */
actual object PersistentStore {
    actual fun get(key: String): String? {
        val host = PulseHost.environment
        return if (host != null) host.getValue(key) else fileGet(key)
    }

    actual fun put(key: String, value: String) {
        val host = PulseHost.environment
        if (host != null) host.putValue(key, value) else filePut(key, value)
    }

    private val lock = Any()

    private fun file() = File(pulseRoot(), "ids.properties")

    private fun load(): Properties = Properties().also { p ->
        val f = file()
        if (f.isFile) f.inputStream().use(p::load)
    }

    private fun fileGet(key: String): String? = synchronized(lock) { load().getProperty(key) }

    /** Through a temporary file and a rename, so a crash mid-write keeps the old ids. */
    private fun filePut(key: String, value: String) = synchronized(lock) {
        val p = load()
        p.setProperty(key, value)
        val target = file()
        val temporary = File(target.path + ".tmp")
        temporary.outputStream().use { p.store(it, null) }
        if (!temporary.renameTo(target)) { target.delete(); temporary.renameTo(target) }
    }
}

private fun pulseRoot(): String {
    val base = PulseHost.environment?.storageRoot ?: File(System.getProperty("user.home") ?: ".", ".pulsekit").path
    return File(base, "PulseKit").also { it.mkdirs() }.path
}

/**
 * The host's SQLite driver: the Android framework's, through [pulse.host.HostEnvironment]. A JVM
 * without a host has no SQLite of its own; the SDK then keeps its outbox in memory (Pulse.start
 * falls back when this throws).
 */
internal actual fun openOutboxDriver(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    storageDir: String,
    name: String,
): SqlDriver {
    val path = "$storageDir/$name"
    testSqliteDriver?.let { return it(schema, path) }
    val host = PulseHost.environment ?: throw IllegalStateException("no SQLite on this JVM: no host environment registered")
    return host.openSqliteDriver(schema, path)
}

/** Tests on a plain JVM supply a JDBC driver here. */
internal var testSqliteDriver: ((SqlSchema<QueryResult.Value<Unit>>, String) -> SqlDriver)? = null
