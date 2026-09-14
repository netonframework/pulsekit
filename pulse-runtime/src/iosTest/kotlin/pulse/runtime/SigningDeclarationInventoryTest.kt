package pulse.runtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SigningDeclarationInventoryTest {

    @Test
    fun extractsXmlEntitlementsFromAnEmbeddedSignatureSuperblob() {
        val xml = "<plist><dict><key>get-task-allow</key><true/></dict></plist>".encodeToByteArray()
        val blobOffset = 20
        val signature = ByteArray(blobOffset + 8 + xml.size)
        putU32be(signature, 0, 0xfade0cc0U)
        putU32be(signature, 4, signature.size.toUInt())
        putU32be(signature, 8, 1U)
        putU32be(signature, 12, 5U)
        putU32be(signature, 16, blobOffset.toUInt())
        putU32be(signature, blobOffset, 0xfade7171U)
        putU32be(signature, blobOffset + 4, (8 + xml.size).toUInt())
        xml.copyInto(signature, blobOffset + 8)

        val extracted = SigningDeclarationInventory.extractXmlEntitlements(signature)
        assertContentEquals(xml, extracted)
        assertEquals(true, SigningDeclarationInventory.parsePropertyList(extracted!!)?.get("get-task-allow"))
    }

    @Test
    fun rejectsMalformedSignatureOffsetsInsteadOfReadingOutsideTheBlob() {
        val signature = ByteArray(20)
        putU32be(signature, 0, 0xfade0cc0U)
        putU32be(signature, 4, signature.size.toUInt())
        putU32be(signature, 8, 1U)
        putU32be(signature, 12, 5U)
        putU32be(signature, 16, 999U)
        assertNull(SigningDeclarationInventory.extractXmlEntitlements(signature))
    }

    @Test
    fun evidenceKeepsReviewedEntitlementsAndOnlyNamesUnknownOnes() {
        val evidence = SigningDeclarationInventory.evidence(
            SigningDeclarationInventory.Snapshot(
                "present",
                mapOf(
                    "application-identifier" to "TEAM.com.example.app",
                    "aps-environment" to "production",
                    "keychain-access-groups" to listOf("TEAM.com.example.app"),
                    "unreviewed-secret-value" to "must-not-leave-device",
                ),
            ),
        )
        assertEquals("present", evidence["signature_status"])
        assertEquals("TEAM.com.example.app", evidence["application_identifier"])
        assertEquals("production", evidence["aps_environment"])
        assertTrue(evidence["entitlement_keys"].toString().contains("unreviewed-secret-value"))
        assertTrue(evidence.values.none { it == "must-not-leave-device" })
    }

    private fun putU32be(target: ByteArray, offset: Int, value: UInt) {
        target[offset] = (value shr 24).toByte()
        target[offset + 1] = (value shr 16).toByte()
        target[offset + 2] = (value shr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
