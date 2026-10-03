package pulse

// Test setup that differs between native and the JVM.

/** Where tests put disposable directories. */
internal expect fun testTempRoot(): String

/** Give the outbox a SQLite driver where the platform has none of its own (a plain JVM). */
internal expect fun installTestSqlite()

/** Remove [path] and everything in it. */
internal expect fun deleteTestDirectory(path: String)

/**
 * Crash capture is armed for [storageDir]: native opens the signal handler's record file up front,
 * the JVM installs its uncaught-exception handler.
 */
internal expect fun crashCaptureReady(storageDir: String): Boolean

/**
 * Whether this platform can list the images loaded into the process: always on native (dyld,
 * /proc); on the JVM only where /proc exists (Linux, Android), not on a macOS JVM.
 */
internal expect fun platformListsImages(): Boolean
