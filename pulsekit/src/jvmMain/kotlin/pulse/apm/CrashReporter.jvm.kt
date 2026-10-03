package pulse.apm

import java.io.File

/**
 * The JVM's crash records are written by the uncaught-exception reporter
 * ([installUncaughtExceptionReporter]); there is no signal handler, because a JVM cannot run one.
 * A native crash inside the process (in a host's own .so) therefore leaves only the platform's own
 * report (the Android tombstone), not a PulseKit record.
 */
actual object CrashReporter {

    actual fun install(storageDir: String, sessionId: String) {
        File(storageDir).mkdirs()
    }

    actual fun drainPending(storageDir: String): List<PendingCrash> {
        val records = File(storageDir).listFiles { f -> f.isFile && f.name.startsWith(CRASH_RECORD_NAME) } ?: return emptyList()
        return records.sortedBy { it.name }.mapNotNull { f ->
            val text = try { readBounded(f) } catch (_: Exception) { "" }
            f.delete()                                        // read once; never reported twice
            parseCrashRecord(text) { sig -> "SIG$sig" }
        }
    }

    private fun readBounded(f: File): String = f.inputStream().use { input ->
        val bytes = ByteArray(MAX_CRASH_RECORD_BYTES)
        var total = 0
        while (total < bytes.size) {
            val n = input.read(bytes, total, bytes.size - total)
            if (n <= 0) break
            total += n
        }
        bytes.decodeToString(0, total)
    }
}
