@file:OptIn(ExperimentalForeignApi::class)

package pulse.runtime

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

/**
 * /proc/self/maps rather than dl_iterate_phdr: the callback form needs a staticCFunction and a
 * pinned accumulator, and the file gives the same file-backed mappings with less machinery.
 *
 * Each line is `addr-addr perms offset dev inode  /path`. Only file-backed lines have a path, and
 * one image spans several lines (text/data/bss), so the first mapping of each path wins — that is
 * also the lowest address, which is the load address.
 */
actual fun loadedModules(): List<LoadedModule> = memScoped {
    val f = fopen("/proc/self/maps", "r") ?: return emptyList()
    val buf = allocArray<ByteVar>(8192)
    val seen = LinkedHashMap<String, Long>()
    while (fgets(buf, 8192, f) != null) {
        val line = buf.toKString()
        val path = line.substringAfter(' ', "").let { line.indexOf('/').takeIf { i -> i > 0 } }
            ?.let { line.substring(it).trim() } ?: continue
        if (path.isEmpty() || path.startsWith("/dev/")) continue
        if (seen.containsKey(path)) continue
        val start = line.substringBefore('-').toLongOrNull(16) ?: 0L
        seen[path] = start
    }
    fclose(f)
    seen.map { (path, addr) -> LoadedModule(path, addr) }
}
