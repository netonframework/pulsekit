@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pulse.runtime

import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSPropertyListImmutable
import platform.Foundation.NSPropertyListSerialization

/** Static privacy declarations present in the final Apple bundle, reported without their prose. */
internal object PrivacyDeclarationInventory {

    data class Snapshot(
        val declarations: Map<String, Any?>,
        val manifests: List<Manifest>,
    )

    data class Manifest(
        val bundle: String,
        val tracking: Boolean,
        val trackingDomains: List<String>,
        val collectedData: List<String>,
        val accessedApis: List<String>,
    )

    fun capture(): Snapshot {
        val main = NSBundle.mainBundle
        val manifests = findManifests(main)
        return Snapshot(
            declarations = summarize(
                info = main.infoDictionary.orEmpty(),
                bundleId = main.bundleIdentifier,
                manifestBundles = manifests.map(Manifest::bundle),
            ),
            manifests = manifests,
        )
    }

    /**
     * Scan the installed app rather than only [NSBundle.allFrameworks]. CocoaPods SDKs commonly
     * put their manifest in a nested resource bundle, which isn't itself a loaded framework and
     * would otherwise be invisible (SDWebImage is one real example in the demo app).
     */
    private fun findManifests(main: NSBundle): List<Manifest> {
        val root = main.bundlePath
        val relativePaths = NSFileManager.defaultManager.subpathsAtPath(root)
            .orEmpty()
            .map { it.toString() }
            .filter { it.substringAfterLast('/') == "PrivacyInfo.xcprivacy" }
            .sorted()
            .take(MAX_ITEMS)
        return relativePaths.mapNotNull { relative ->
            readManifest("$root/$relative", manifestOwner(main, relative))
        }
    }

    private fun manifestOwner(main: NSBundle, relativePath: String): String {
        val components = relativePath.split('/').dropLast(1)
        var prefix = ""
        var owner: String? = null
        for (component in components) {
            prefix = if (prefix.isEmpty()) component else "$prefix/$component"
            if (component.endsWith(".framework") || component.endsWith(".bundle")) {
                owner = NSBundle.bundleWithPath("${main.bundlePath}/$prefix")?.bundleIdentifier
                    ?: component.substringBeforeLast('.')
            }
        }
        return owner ?: "main"
    }

    private fun readManifest(path: String, name: String): Manifest? {
        val data = NSFileManager.defaultManager.contentsAtPath(path) ?: return null
        val plist = NSPropertyListSerialization.propertyListWithData(
            data = data,
            options = NSPropertyListImmutable,
            format = null,
            error = null,
        ) as? Map<*, *> ?: return null
        return parseManifest(name, plist)
    }

    internal fun parseManifest(bundle: String, plist: Map<*, *>): Manifest {
        val domains = strings(plist["NSPrivacyTrackingDomains"])
            .map { it.lowercase().take(MAX_VALUE_LENGTH) }
        val collected = dictionaries(plist["NSPrivacyCollectedDataTypes"]).mapNotNull { item ->
            val type = item["NSPrivacyCollectedDataType"]?.toString() ?: return@mapNotNull null
            val purposes = strings(item["NSPrivacyCollectedDataTypePurposes"])
            buildString {
                append(type.take(MAX_VALUE_LENGTH))
                append(":linked=").append(item["NSPrivacyCollectedDataTypeLinked"] == true)
                append(":tracking=").append(item["NSPrivacyCollectedDataTypeTracking"] == true)
                if (purposes.isNotEmpty()) append(":purposes=").append(purposes.joinToString("+"))
            }.take(MAX_DETAIL_LENGTH)
        }.distinct().sorted().take(MAX_ITEMS)
        val apis = dictionaries(plist["NSPrivacyAccessedAPITypes"]).mapNotNull { item ->
            val type = item["NSPrivacyAccessedAPIType"]?.toString() ?: return@mapNotNull null
            val reasons = strings(item["NSPrivacyAccessedAPITypeReasons"])
            "$type:${reasons.joinToString("+")}".take(MAX_DETAIL_LENGTH)
        }.distinct().sorted().take(MAX_ITEMS)
        return Manifest(
            bundle = bundle.take(MAX_VALUE_LENGTH),
            tracking = plist["NSPrivacyTracking"] == true,
            trackingDomains = domains.distinct().sorted().take(MAX_ITEMS),
            collectedData = collected,
            accessedApis = apis,
        )
    }

    /** Kept pure so the extraction rules can be tested without manufacturing an application bundle. */
    internal fun summarize(
        info: Map<Any?, *>,
        bundleId: String?,
        manifestBundles: List<String>,
    ): Map<String, Any?> {
        val keys = info.keys.map(Any?::toString)
        val usageDescriptions = keys.filter { it.endsWith("UsageDescription") }.distinct().sorted()
        val backgroundModes = strings(info["UIBackgroundModes"])
        val urlSchemeCount = (info["CFBundleURLTypes"] as? List<*>)
            .orEmpty()
            .sumOf { entry ->
                val dictionary = entry as? Map<*, *> ?: return@sumOf 0
                strings(dictionary["CFBundleURLSchemes"]).size
            }

        return buildMap {
            bundleId?.let { put("bundle_id", it.take(MAX_VALUE_LENGTH)) }
            put("usage_description_count", usageDescriptions.size)
            put("usage_description_keys", usageDescriptions.take(MAX_ITEMS).joinToString(","))
            put("background_modes", backgroundModes.take(MAX_ITEMS).joinToString(","))
            put("url_scheme_count", urlSchemeCount)
            put("privacy_manifest_count", manifestBundles.size)
            put("privacy_manifest_bundles", manifestBundles.take(MAX_ITEMS).joinToString(","))
        }
    }

    private fun strings(value: Any?): List<String> = (value as? List<*>)
        .orEmpty()
        .mapNotNull { it?.toString()?.take(MAX_VALUE_LENGTH) }
        .distinct()
        .sorted()

    private fun dictionaries(value: Any?): List<Map<*, *>> = (value as? List<*>)
        .orEmpty()
        .mapNotNull { it as? Map<*, *> }

    private const val MAX_ITEMS = 64
    private const val MAX_VALUE_LENGTH = 191
    private const val MAX_DETAIL_LENGTH = 480
}

/** Report declarations from the final signed/packaged app, independently of runtime API calls. */
fun Runtime.reportPrivacyDeclarations() {
    val snapshot = PrivacyDeclarationInventory.capture()
    recordBehavior("privacy_declarations", attributes = snapshot.declarations)
    for (manifest in snapshot.manifests) {
        recordBehavior(
            "privacy_manifest",
            module = manifest.bundle,
            attributes = mapOf(
                "tracking" to manifest.tracking,
                "tracking_domains" to manifest.trackingDomains.joinToString(","),
                "collected_data" to manifest.collectedData.joinToString(";"),
                "accessed_apis" to manifest.accessedApis.joinToString(";"),
            ),
        )
    }
}
