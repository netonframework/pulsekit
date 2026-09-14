package pulse.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class PrivacyDeclarationInventoryTest {

    @Test
    fun reportsDeclarationNamesAndCountsWithoutDescriptionTextOrUrlSchemes() {
        val info: Map<Any?, *> = mapOf(
            "NSCameraUsageDescription" to "a secret-bearing localized sentence",
            "NSPhotoLibraryUsageDescription" to "photos",
            "UIBackgroundModes" to listOf("fetch", "remote-notification"),
            "CFBundleURLTypes" to listOf(
                mapOf("CFBundleURLSchemes" to listOf("private-callback", "second")),
            ),
        )

        val evidence = PrivacyDeclarationInventory.summarize(
            info, "com.example.app", listOf("main", "com.vendor.ads"),
        )

        assertEquals(2, evidence["usage_description_count"])
        assertEquals(
            "NSCameraUsageDescription,NSPhotoLibraryUsageDescription",
            evidence["usage_description_keys"],
        )
        assertEquals("fetch,remote-notification", evidence["background_modes"])
        assertEquals(2, evidence["url_scheme_count"])
        assertEquals(2, evidence["privacy_manifest_count"])
        assertEquals("main,com.vendor.ads", evidence["privacy_manifest_bundles"])
        check(evidence.values.none { it.toString().contains("secret-bearing") })
        check(evidence.values.none { it.toString().contains("private-callback") })
    }

    @Test
    fun parsesPrivacyManifestSemanticsWithItsSourceBundle() {
        val manifest = PrivacyDeclarationInventory.parseManifest(
            "com.vendor.ads",
            mapOf(
                "NSPrivacyTracking" to true,
                "NSPrivacyTrackingDomains" to listOf("TRACK.EXAMPLE.COM"),
                "NSPrivacyCollectedDataTypes" to listOf(
                    mapOf(
                        "NSPrivacyCollectedDataType" to "NSPrivacyCollectedDataTypeEmailAddress",
                        "NSPrivacyCollectedDataTypeLinked" to true,
                        "NSPrivacyCollectedDataTypeTracking" to false,
                        "NSPrivacyCollectedDataTypePurposes" to listOf("NSPrivacyCollectedDataTypePurposeAnalytics"),
                    ),
                ),
                "NSPrivacyAccessedAPITypes" to listOf(
                    mapOf(
                        "NSPrivacyAccessedAPIType" to "NSPrivacyAccessedAPICategoryUserDefaults",
                        "NSPrivacyAccessedAPITypeReasons" to listOf("CA92.1"),
                    ),
                ),
            ),
        )

        assertEquals("com.vendor.ads", manifest.bundle)
        assertEquals(true, manifest.tracking)
        assertEquals(listOf("track.example.com"), manifest.trackingDomains)
        assertEquals(
            listOf("NSPrivacyAccessedAPICategoryUserDefaults:CA92.1"),
            manifest.accessedApis,
        )
        assertEquals(
            listOf("NSPrivacyCollectedDataTypeEmailAddress:linked=true:tracking=false:purposes=NSPrivacyCollectedDataTypePurposeAnalytics"),
            manifest.collectedData,
        )
    }
}
