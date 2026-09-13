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
        client.emit(
            EventKind.Runtime,
            "module_inventory",
            mapOf(
                "module_count" to modules.size,
                // A stable digest of the whole set: the server can spot two installs that differ
                // without the client uploading every path.
                "inventory_digest" to inventoryDigest(baseline),
            ).toEventAttributes(),
        )
        return modules
    }

    /**
     * Re-read the loader's table and report images that were not in the baseline, one event each.
     * New images after start are the interesting signal; the baseline is not re-sent.
     *
     * Returns what was newly seen, and folds it into the baseline so each image reports once.
     */
    fun scan(): List<LoadedModule> {
        val current = loadedModules()
        val fresh = current.filter { it.path !in baseline }
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
