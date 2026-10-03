package pulse.runtime

import java.io.File
import java.io.RandomAccessFile

/**
 * The files mapped into this process, from /proc/self/maps (Android and Linux; a JVM elsewhere
 * reports none). Each path is listed once, at its lowest mapping, which is its load address.
 *
 * The base APK comes first when the host names it: [bundledModules] takes entry 0's directory as
 * the app's install root, the counterpart of the bundle directory on iOS. That root holds the
 * extracted libraries under `lib/<abi>/`, whose GNU build-id is read from the ELF file — the
 * counterpart of LC_UUID. Libraries Android maps straight out of the APK (uncompressed, page
 * aligned) appear as the APK itself; a JVM process cannot tell them apart without the linker.
 */
actual fun loadedModules(): List<LoadedModule> {
    val maps = File("/proc/self/maps")
    if (!maps.canRead()) return emptyList()
    val seen = LinkedHashMap<String, Long>()
    try {
        maps.forEachLine { line ->
            val slash = line.indexOf('/')
            if (slash < 0) return@forEachLine
            val path = line.substring(slash).trim()
            if (path.startsWith("/dev/") || path.endsWith(" (deleted)") || path in seen) return@forEachLine
            seen[path] = line.substringBefore('-').toULongOrNull(16)?.toLong() ?: 0L
        }
    } catch (_: Exception) {
        return emptyList()
    }
    val apk = hostFacts().sourceDir?.takeIf(String::isNotEmpty)
    val root = apk?.substringBeforeLast('/', "")?.takeIf(String::isNotEmpty)
    val out = ArrayList<LoadedModule>(seen.size + 1)
    if (apk != null) out.add(LoadedModule(apk, seen[apk] ?: 0L, null))
    for ((path, address) in seen) {
        if (path == apk) continue
        // Build-ids only for the app's own libraries, as iOS only parses UUIDs inside the bundle.
        val bundled = root != null && path.startsWith("$root/") && path.endsWith(".so")
        out.add(LoadedModule(path, address, if (bundled) elfBuildId(path) else null))
    }
    return out
}

/**
 * The GNU build-id of the ELF file at [path], lowercase hex, or null. Reads only the headers and
 * the PT_NOTE segments, with every offset and size checked against the file.
 */
internal fun elfBuildId(path: String): String? = try {
    RandomAccessFile(path, "r").use { f ->
        val ident = ByteArray(16)
        f.readFully(ident)
        if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() || ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()) return null
        val is64 = ident[4].toInt() == 2
        val little = ident[5].toInt() == 1
        fun u16(at: Long): Int { f.seek(at); val b = ByteArray(2); f.readFully(b); return order(b, little).toInt() }
        fun u32(at: Long): Long { f.seek(at); val b = ByteArray(4); f.readFully(b); return order(b, little) }
        fun u64(at: Long): Long { f.seek(at); val b = ByteArray(8); f.readFully(b); return order(b, little) }
        val phoff = if (is64) u64(0x20) else u32(0x1c)
        val phentsize = u16(if (is64) 0x36L else 0x2aL)
        val phnum = u16(if (is64) 0x38L else 0x2cL)
        if (phnum !in 1..MAX_PHNUM || phentsize < 32) return null
        for (i in 0 until phnum) {
            val ph = phoff + i.toLong() * phentsize
            if (u32(ph) != PT_NOTE) continue
            val offset = if (is64) u64(ph + 0x08) else u32(ph + 0x04)
            val size = if (is64) u64(ph + 0x20) else u32(ph + 0x10)
            if (offset < 0 || size !in 12..MAX_NOTE_BYTES || offset + size > f.length()) continue
            val notes = ByteArray(size.toInt())
            f.seek(offset); f.readFully(notes)
            var p = 0
            while (p + 12 <= notes.size) {
                val nameSize = order(notes.copyOfRange(p, p + 4), little).toInt()
                val descSize = order(notes.copyOfRange(p + 4, p + 8), little).toInt()
                val type = order(notes.copyOfRange(p + 8, p + 12), little)
                val name = p + 12
                val desc = name + ((nameSize + 3) and 3.inv())
                if (nameSize < 0 || descSize < 0 || desc + descSize > notes.size) break
                if (type == NT_GNU_BUILD_ID && nameSize == 4 && notes[name] == 'G'.code.toByte() &&
                    notes[name + 1] == 'N'.code.toByte() && notes[name + 2] == 'U'.code.toByte()
                ) {
                    return notes.copyOfRange(desc, desc + descSize).joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }
                }
                p = desc + ((descSize + 3) and 3.inv())
            }
        }
        null
    }
} catch (_: Exception) {
    null
}

private fun order(bytes: ByteArray, little: Boolean): Long {
    var v = 0L
    for (i in bytes.indices) {
        val b = bytes[if (little) bytes.size - 1 - i else i].toLong() and 0xff
        v = (v shl 8) or b
    }
    return v
}

private const val PT_NOTE = 4L
private const val NT_GNU_BUILD_ID = 3L
private const val MAX_PHNUM = 4096
private const val MAX_NOTE_BYTES = 64L * 1024
