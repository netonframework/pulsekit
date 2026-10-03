@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pulse

import kotlinx.cinterop.toKString
import platform.posix.getenv

/** `$TMPDIR`, else `/tmp`. */
internal actual fun testTempRoot(): String =
    getenv("TMPDIR")?.toKString()?.trimEnd('/')?.takeIf(String::isNotEmpty) ?: "/tmp"

internal actual fun installTestSqlite() = Unit

internal actual fun deleteTestDirectory(path: String) {
    for (name in listOf("pulse-outbox.db", "pulse-outbox.db-wal", "pulse-outbox.db-shm")) platform.posix.unlink("$path/$name")
    platform.posix.rmdir(path)
}

internal actual fun crashCaptureReady(storageDir: String): Boolean =
    platform.posix.access("$storageDir/crash.record", platform.posix.F_OK) == 0

internal actual fun platformListsImages(): Boolean = true
