@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pulse.runtime

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSMutableData
import platform.Foundation.NSPropertyListImmutable
import platform.Foundation.NSPropertyListSerialization
import platform.posix.EINTR
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.open
import platform.posix.pread
import platform.posix.memcpy

/** Final app-signing declarations read from the main executable's embedded code signature. */
internal object SigningDeclarationInventory {

    data class Snapshot(
        val status: String,
        val entitlements: Map<String, Any?>,
    )

    fun capture(): Snapshot {
        val path = NSBundle.mainBundle.executablePath ?: return Snapshot("no_executable", emptyMap())
        val signature = readCodeSignature(path) ?: return Snapshot("not_signed", emptyMap())
        val xml = extractXmlEntitlements(signature)
            ?: return Snapshot(if (hasDerEntitlements(signature)) "der_only" else "no_entitlements", emptyMap())
        return Snapshot("present", parsePropertyList(xml).orEmpty())
    }

    fun evidence(snapshot: Snapshot): Map<String, Any?> {
        val values = snapshot.entitlements
        val keys = values.keys.sorted().take(MAX_ITEMS)
        return buildMap {
            put("signature_status", snapshot.status)
            put("entitlement_count", values.size)
            put("entitlement_keys", keys.joinToString(","))
            knownValue(values, "com.apple.developer.team-identifier")?.let { put("team_identifier", it) }
            knownValue(values, "application-identifier")?.let { put("application_identifier", it) }
            knownValue(values, "aps-environment")?.let { put("aps_environment", it) }
            knownValue(values, "get-task-allow")?.let { put("get_task_allow", it) }
            knownValue(values, "com.apple.developer.associated-domains")?.let { put("associated_domains", it) }
            knownValue(values, "com.apple.security.application-groups")?.let { put("application_groups", it) }
            knownValue(values, "keychain-access-groups")?.let { put("keychain_access_groups", it) }
        }
    }

    private fun knownValue(values: Map<String, Any?>, key: String): String? = when (val value = values[key]) {
        is String -> value.take(MAX_VALUE_LENGTH)
        is Boolean -> value.toString()
        is List<*> -> value.mapNotNull { it as? String }.take(MAX_ITEMS)
            .joinToString(",") { it.take(MAX_VALUE_LENGTH) }
        else -> null
    }

    private fun readCodeSignature(path: String): ByteArray? {
        val fd = open(path, O_RDONLY)
        if (fd < 0) return null
        try {
            val header = readAt(fd, 0, MACH_HEADER_64_SIZE) ?: return null
            if (u32le(header, 0) != MH_MAGIC_64) return null
            val commandCount = u32le(header, 16).toInt()
            val commandBytes = u32le(header, 20).toInt()
            if (commandCount !in 1..MAX_LOAD_COMMANDS || commandBytes !in 8..MAX_LOAD_COMMAND_BYTES) return null
            val commands = readAt(fd, MACH_HEADER_64_SIZE.toLong(), commandBytes) ?: return null
            var offset = 0
            repeat(commandCount) {
                if (offset + 8 > commands.size) return null
                val command = u32le(commands, offset)
                val size = u32le(commands, offset + 4).toInt()
                if (size < 8 || offset + size > commands.size) return null
                if (command == LC_CODE_SIGNATURE && size >= 16) {
                    val dataOffset = u32le(commands, offset + 8).toLong()
                    val dataSize = u32le(commands, offset + 12).toInt()
                    if (dataSize !in 12..MAX_SIGNATURE_BYTES) return null
                    return readAt(fd, dataOffset, dataSize)
                }
                offset += size
            }
            return null
        } finally {
            close(fd)
        }
    }

    private fun readAt(fd: Int, offset: Long, length: Int): ByteArray? {
        if (length <= 0) return null
        val bytes = ByteArray(length)
        var consumed = 0
        while (consumed < length) {
            val read = bytes.usePinned { pinned ->
                pread(
                    fd,
                    pinned.addressOf(consumed),
                    (length - consumed).convert(),
                    offset + consumed,
                )
            }
            if (read < 0 && errno == EINTR) continue
            if (read <= 0) return null
            consumed += read.toInt()
        }
        return bytes
    }

    internal fun extractXmlEntitlements(signature: ByteArray): ByteArray? {
        if (signature.size < 12 || u32be(signature, 0) != CSMAGIC_EMBEDDED_SIGNATURE) return null
        val length = u32be(signature, 4).toInt()
        val count = u32be(signature, 8).toInt()
        if (length !in 12..signature.size || count !in 0..MAX_BLOB_COUNT || 12 + count * 8 > length) return null
        repeat(count) { index ->
            val entry = 12 + index * 8
            if (u32be(signature, entry) != CSSLOT_ENTITLEMENTS) return@repeat
            val blobOffset = u32be(signature, entry + 4).toInt()
            if (blobOffset < 0 || blobOffset + 8 > length) return null
            if (u32be(signature, blobOffset) != CSMAGIC_EMBEDDED_ENTITLEMENTS) return null
            val blobLength = u32be(signature, blobOffset + 4).toInt()
            if (blobLength !in 9..MAX_ENTITLEMENTS_BYTES || blobOffset + blobLength > length) return null
            var end = blobOffset + blobLength
            while (end > blobOffset + 8 && signature[end - 1] == 0.toByte()) end--
            return signature.copyOfRange(blobOffset + 8, end)
        }
        return null
    }

    private fun hasDerEntitlements(signature: ByteArray): Boolean {
        if (signature.size < 12 || u32be(signature, 0) != CSMAGIC_EMBEDDED_SIGNATURE) return false
        val length = u32be(signature, 4).toInt().coerceAtMost(signature.size)
        val count = u32be(signature, 8).toInt()
        if (count !in 0..MAX_BLOB_COUNT || 12 + count * 8 > length) return false
        return (0 until count).any { u32be(signature, 12 + it * 8) == CSSLOT_DER_ENTITLEMENTS }
    }

    internal fun parsePropertyList(xml: ByteArray): Map<String, Any?>? {
        val data = NSMutableData().apply { setLength(xml.size.toULong()) }
        xml.usePinned { pinned ->
            memcpy(data.mutableBytes, pinned.addressOf(0), xml.size.convert())
        }
        val raw = NSPropertyListSerialization.propertyListWithData(
            data = data,
            options = NSPropertyListImmutable,
            format = null,
            error = null,
        ) as? Map<*, *> ?: return null
        return raw.entries.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }.toMap()
    }

    private fun u32le(bytes: ByteArray, offset: Int): UInt =
        (bytes[offset].toInt() and 0xff).toUInt() or
            ((bytes[offset + 1].toInt() and 0xff).toUInt() shl 8) or
            ((bytes[offset + 2].toInt() and 0xff).toUInt() shl 16) or
            ((bytes[offset + 3].toInt() and 0xff).toUInt() shl 24)

    private fun u32be(bytes: ByteArray, offset: Int): UInt =
        ((bytes[offset].toInt() and 0xff).toUInt() shl 24) or
            ((bytes[offset + 1].toInt() and 0xff).toUInt() shl 16) or
            ((bytes[offset + 2].toInt() and 0xff).toUInt() shl 8) or
            (bytes[offset + 3].toInt() and 0xff).toUInt()

    private const val MACH_HEADER_64_SIZE = 32
    private const val MH_MAGIC_64 = 0xfeedfacfU
    private const val LC_CODE_SIGNATURE = 0x1dU
    private const val CSMAGIC_EMBEDDED_SIGNATURE = 0xfade0cc0U
    private const val CSMAGIC_EMBEDDED_ENTITLEMENTS = 0xfade7171U
    private const val CSSLOT_ENTITLEMENTS = 5U
    private const val CSSLOT_DER_ENTITLEMENTS = 7U
    private const val MAX_LOAD_COMMANDS = 4_096
    private const val MAX_LOAD_COMMAND_BYTES = 4 * 1024 * 1024
    private const val MAX_SIGNATURE_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTITLEMENTS_BYTES = 1024 * 1024
    private const val MAX_BLOB_COUNT = 1_024
    private const val MAX_ITEMS = 64
    private const val MAX_VALUE_LENGTH = 256
}

fun Runtime.reportSigningDeclarations() {
    val snapshot = SigningDeclarationInventory.capture()
    recordBehavior("signing_declarations", attributes = SigningDeclarationInventory.evidence(snapshot))
}
