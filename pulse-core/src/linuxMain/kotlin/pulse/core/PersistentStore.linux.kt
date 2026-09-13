@file:OptIn(ExperimentalForeignApi::class)

package pulse.core

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fprintf
import platform.posix.getenv
import platform.posix.mkdir

/**
 * A `key=value` file under `$XDG_STATE_HOME` (or `~/.local/state`). Linux is the server/dev host
 * for this SDK rather than an end-user device, so this exists to keep the ids stable across
 * restarts of a long-lived process, not to identify a person.
 *
 * Rewrites the whole file on put; it holds two keys, so there is nothing to optimise.
 */
actual object PersistentStore {

    private fun dir(): String {
        val state = getenv("XDG_STATE_HOME")?.toKString()?.takeIf { it.isNotEmpty() }
        val base = state ?: (getenv("HOME")?.toKString()?.plus("/.local/state") ?: "/tmp")
        mkdir(base, 493u)          // 0755; already-exists is fine and is the common case
        val d = "$base/pulsekit"
        mkdir(d, 493u)
        return d
    }

    private fun path(): String = dir() + "/ids"

    private fun readAll(): MutableMap<String, String> = memScoped {
        val out = LinkedHashMap<String, String>()
        val f = fopen(path(), "r") ?: return out
        val buf = allocArray<ByteVar>(1024)
        while (fgets(buf, 1024, f) != null) {
            val line = buf.toKString().trimEnd('\n')
            val i = line.indexOf('=')
            if (i > 0) out[line.substring(0, i)] = line.substring(i + 1)
        }
        fclose(f)
        out
    }

    actual fun get(key: String): String? = readAll()[key]

    actual fun put(key: String, value: String) {
        val all = readAll()
        all[key] = value
        val f = fopen(path(), "w") ?: return
        for ((k, v) in all) fprintf(f, "%s=%s\n", k, v)
        fclose(f)
    }
}
