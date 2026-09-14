@file:OptIn(ExperimentalForeignApi::class)

package pulse.runtime

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import pulse.runtime.dyld._dyld_get_image_header
import pulse.runtime.dyld._dyld_get_image_name
import pulse.runtime.dyld._dyld_image_count

/**
 * dyld's own image table: the list the dynamic loader maintains, so it covers everything mapped
 * into the process, including images loaded after start.
 */
actual fun loadedModules(): List<LoadedModule> {
    val count = _dyld_image_count().toInt()
    val out = ArrayList<LoadedModule>(count)
    var appRoot: String? = null
    for (i in 0 until count) {
        val path = _dyld_get_image_name(i.toUInt())?.toKString() ?: continue
        if (appRoot == null) appRoot = path.substringBeforeLast('/', "")
        val header = _dyld_get_image_header(i.toUInt())
        // Hundreds of system images can be mapped. Their UUIDs are OS noise; only parse the
        // executable and images embedded inside this app, which is the auditable surface.
        val uuid = if (path == appRoot || path.startsWith("$appRoot/")) machOUuid(header?.reinterpret()) else null
        out.add(LoadedModule(path, header?.rawValue?.toLong() ?: 0L, uuid))
    }
    return out
}

/** Read LC_UUID directly from dyld's mapped Mach-O header; no filesystem access or private API. */
private fun machOUuid(bytes: CPointer<ByteVar>?): String? {
    bytes ?: return null
    val headerSize = when (readU32Le(bytes, 0)) {
        MH_MAGIC_64 -> 32
        MH_MAGIC -> 28
        else -> return null
    }
    val commandCount = readU32Le(bytes, 16).toInt()
    val commandBytes = readU32Le(bytes, 20).toInt()
    if (commandCount !in 1..MAX_LOAD_COMMANDS || commandBytes !in 8..MAX_LOAD_COMMAND_BYTES) return null

    val end = headerSize + commandBytes
    var offset = headerSize
    repeat(commandCount) {
        if (offset + 8 > end) return null
        val command = readU32Le(bytes, offset)
        val size = readU32Le(bytes, offset + 4).toInt()
        if (size < 8 || offset + size > end) return null
        if (command == LC_UUID && size >= 24) return formatUuid(bytes, offset + 8)
        offset += size
    }
    return null
}

private fun readU32Le(bytes: CPointer<ByteVar>, offset: Int): UInt =
    (bytes[offset.toLong()].toInt() and 0xff).toUInt() or
        ((bytes[(offset + 1).toLong()].toInt() and 0xff).toUInt() shl 8) or
        ((bytes[(offset + 2).toLong()].toInt() and 0xff).toUInt() shl 16) or
        ((bytes[(offset + 3).toLong()].toInt() and 0xff).toUInt() shl 24)

private fun formatUuid(bytes: CPointer<ByteVar>, offset: Int): String {
    val hex = buildString(32) {
        repeat(16) {
            append((bytes[(offset + it).toLong()].toInt() and 0xff).toString(16).padStart(2, '0'))
        }
    }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}

private const val MH_MAGIC = 0xfeedfaceU
private const val MH_MAGIC_64 = 0xfeedfacfU
private const val LC_UUID = 0x1bU
private const val MAX_LOAD_COMMANDS = 4_096
private const val MAX_LOAD_COMMAND_BYTES = 4 * 1024 * 1024
