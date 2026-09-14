package pulse.runtime

import pulse.core.EventKind
import pulse.core.EventSource
import pulse.core.PulseClient
import pulse.core.toEventAttributes

/**
 * Runtime capability: what is loaded into the process, what changes after start, and behaviour a
 * host chooses to attribute to a module. Everything reports as [EventKind.Runtime]; the server maps
 * that kind into its security domain (`security_events`), which is where any judgement happens.
 *
 * Naming here stays descriptive on purpose — `module_loaded`, `module_inventory`, `runtime_signal`.
 * The SDK never labels an image as first-party, system or third-party and never emits a verdict,
 * so nothing in a shipped binary reads as security tooling and the classification can be revised
 * server-side without a new release. Same convention as the rest of PulseKit.
 *
 * Opt-in: [pulse.core.PulseConfig.runtime] is false by default, and with it off nothing here is
 * constructed, so the whole capability can be link-time stripped.
 */
class Runtime(private val client: PulseClient) {

    /** Images seen at [baseline]; anything outside it is new. */
    private var baseline: Set<String> = emptySet()

    /**
     * Record the set of images loaded right now and make it the baseline for later [scan] calls.
     * Emits one summary event, not one per image: a process has hundreds of system libraries at
     * start and shipping them all every launch would be noise the server has to pay to store.
     */
    fun captureBaseline(): List<LoadedModule> {
        val modules = loadedModules()
        baseline = modules.mapTo(HashSet()) { it.path }

        // The bundled images are named individually because they are the auditable surface: the
        // app's own frameworks and the third-party SDKs shipped with them. The system libraries
        // are only counted and digested — there are hundreds, they are identical on every install,
        // and uploading them every launch is noise the server pays to store.
        val bundled = bundledModules(modules)
        client.emit(
            EventKind.Runtime,
            "module_inventory",
            mapOf(
                "module_count" to modules.size,
                "bundled_count" to bundled.size,
                // A stable digest of the whole set: the server can spot two installs that differ
                // without the client uploading every path.
                "inventory_digest" to inventoryDigest(baseline),
                // Bounded: a pathological bundle must not turn one event into a megabyte.
                "bundled_modules" to bundled.take(MAX_REPORTED_MODULES).joinToString(",") { it.name },
            ).toEventAttributes(),
        )
        return modules
    }

    /**
     * Re-read the loader's table and report *bundled* images that were not in the baseline, one
     * event each. An image appearing after launch is the interesting signal — an SDK loading a
     * payload it did not ship with is precisely what this is for — but only for images inside the
     * app. The OS lazily loads hundreds of its own dylibs during a normal session and none of them
     * say anything about the app.
     *
     * Returns what was newly seen, and folds it into the baseline so each image reports once.
     */
    fun scan(): List<LoadedModule> {
        val current = loadedModules()
        // Bundled images only, exactly as the baseline does. Reported without this filter, a real
        // iOS launch produced 418 events in one session — every system dylib the OS happens to
        // load lazily. None of it is auditable surface, all of it is storage the server pays for,
        // and it buries the one line that would have mattered.
        val bundledPaths = bundledModules(current).mapTo(HashSet()) { it.path }
        val fresh = current.filter { it.path !in baseline && it.path in bundledPaths }
        if (fresh.isEmpty()) return emptyList()
        for (m in fresh) {
            client.emit(
                EventKind.Runtime,
                "module_loaded",
                mapOf("path" to m.path, "load_address" to m.loadAddress).toEventAttributes(),
                EventSource(module = m.name),
            )
        }
        baseline = baseline + fresh.map { it.path }
        return fresh
    }

    /**
     * A behaviour the host wants attributed to a module — a sensitive API reached, a permission
     * used, whatever the host instruments. [name] and [attributes] come from the host, so this
     * carries no built-in notion of what is sensitive.
     *
     * Callers are responsible for data minimization: attributes must stay metadata (no request
     * bodies, tokens or keychain content), which is the same rule the server enforces on ingest.
     */
    fun recordBehavior(name: String, module: String? = null, attributes: Map<String, Any?> = emptyMap()) =
        client.emit(EventKind.Runtime, name, attributes.toEventAttributes(), EventSource(module = module))

    private companion object {
        /**
         * Cap on named images in one inventory event. Real bundles hold tens of frameworks; a
         * number far past that is a sign of something wrong, and truncating is better than
         * emitting an event the transport has to fight to deliver.
         */
        const val MAX_REPORTED_MODULES = 100

        /** FNV-1a over the sorted paths: order-independent, cheap, and stable across launches. */
        fun inventoryDigest(paths: Set<String>): String {
            var hash = 0xcbf29ce484222325UL
            for (p in paths.sorted()) {
                for (c in p) {
                    hash = hash xor c.code.toULong()
                    hash *= 0x100000001b3UL
                }
                hash = hash xor 0x2fUL
                hash *= 0x100000001b3UL
            }
            return hash.toString(16).padStart(16, '0')
        }
    }
}
