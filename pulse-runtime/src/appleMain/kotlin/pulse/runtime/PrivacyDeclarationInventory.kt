@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pulse.runtime

import platform.Foundation.NSBundle

/** Static privacy declarations present in the final Apple bundle, reported without their prose. */
internal object PrivacyDeclarationInventory {

    fun capture(): Map<String, Any?> {
        val main = NSBundle.mainBundle
        val frameworks = NSBundle.allFrameworks
        val manifestBundles = buildList {
            if (main.pathsForResourcesOfType("xcprivacy", null).isNotEmpty()) add("main")
            for (candidate in frameworks) {
                val bundle = candidate as? NSBundle ?: continue
                if (bundle.pathsForResourcesOfType("xcprivacy", null).isNotEmpty()) {
                    add(bundle.bundleIdentifier ?: bundle.bundlePath.substringAfterLast('/'))
                }
            }
        }.distinct().sorted()
        return summarize(
            info = main.infoDictionary.orEmpty(),
            bundleId = main.bundleIdentifier,
            manifestBundles = manifestBundles,
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

    private const val MAX_ITEMS = 64
    private const val MAX_VALUE_LENGTH = 191
}

/** Report declarations from the final signed/packaged app, independently of runtime API calls. */
fun Runtime.reportPrivacyDeclarations() {
    recordBehavior("privacy_declarations", attributes = PrivacyDeclarationInventory.capture())
}
