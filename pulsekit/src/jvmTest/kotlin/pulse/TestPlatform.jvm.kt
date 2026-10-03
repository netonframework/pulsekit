package pulse

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

internal actual fun testTempRoot(): String = System.getProperty("java.io.tmpdir").trimEnd('/')

/** The JDBC driver stands in for the one Android supplies through its host environment. */
internal actual fun installTestSqlite() {
    pulse.core.testSqliteDriver = { schema, path -> JdbcSqliteDriver("jdbc:sqlite:$path", Properties(), schema) }
}

internal actual fun deleteTestDirectory(path: String) {
    File(path).deleteRecursively()
}

internal actual fun crashCaptureReady(storageDir: String): Boolean = Thread.getDefaultUncaughtExceptionHandler() != null

internal actual fun platformListsImages(): Boolean = File("/proc/self/maps").canRead()
