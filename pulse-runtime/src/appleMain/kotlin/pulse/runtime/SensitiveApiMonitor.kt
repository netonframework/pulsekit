@file:OptIn(ExperimentalForeignApi::class)

package pulse.runtime

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.ExperimentalForeignApi
// invoke on a CPointer<CFunction<..>> is an extension; without this import the compiler
// silently falls back to DeepRecursiveFunction.invoke and the error is unrecognisable.
import kotlinx.cinterop.invoke
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import platform.objc.class_getClassMethod
import platform.objc.class_getInstanceMethod
import platform.objc.method_getImplementation
import platform.objc.object_getClass
import platform.objc.method_setImplementation
import platform.objc.objc_getClass
import platform.objc.sel_registerName

/**
 * One sensitive API worth knowing about, and the neutral name it reports under.
 *
 * The name states what happened — `pasteboard_read`, `advertising_id_read` — and nothing about
 * whether it was acceptable. Same rule as the rest of PulseKit: the client describes, the server
 * decides. A shipped binary should not carry its vendor's opinion of anyone's SDK.
 */
data class SensitiveApi(
    val className: String,
    val selector: String,
    val eventName: String,
    /** Only zero-argument shapes are hooked; see [SensitiveApiMonitor]. */
    val shape: Shape = Shape.Getter,
    /**
     * Name of a zero-argument class method returning the canonical instance, when the public class
     * is a facade over a private one.
     *
     * Several UIKit classes are class clusters: `UIPasteboard.generalPasteboard` is not a
     * UIPasteboard but a private subclass that implements the methods itself, so replacing the
     * facade's implementation changes nothing for real instances — measured, not assumed. Given an
     * accessor, install resolves the real class from a live instance at runtime. The private class
     * is never named here; it is whatever the accessor happens to return.
     */
    val instanceAccessor: String? = null,
) {
    enum class Shape {
        /** `id (*)(id, SEL)` — a getter such as -[UIPasteboard string]. */
        Getter,
        /** `void (*)(id, SEL)` — an action such as -[CLLocationManager startUpdatingLocation]. */
        Action,
    }
}

/** `id (*)(id, SEL)`. */
private typealias GetterImp = CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>
/** `void (*)(id, SEL)`. */
private typealias ActionImp = CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>

/**
 * Chaining helpers.
 *
 * Written as top-level functions rather than inline in the hooks: inside a `staticCFunction`
 * lambda the compiler cannot resolve `CPointer<CFunction<..>>.invoke` and silently reaches for
 * `DeepRecursiveFunction.invoke` instead. Explicit types here make the intended overload the only
 * candidate.
 */
private fun callGetter(imp: COpaquePointer, self: COpaquePointer?, cmd: COpaquePointer?): COpaquePointer? =
    imp.reinterpret<GetterImp>().invoke(self, cmd)

private fun callAction(imp: COpaquePointer, self: COpaquePointer?, cmd: COpaquePointer?) {
    imp.reinterpret<ActionImp>().invoke(self, cmd)
}

/**
 * Observes calls to sensitive Objective-C APIs and attributes them to the image that made them.
 *
 * How it works: the method's implementation is replaced with one that records the call, works out
 * the caller's image from the return addresses, and then chains to the original. Behaviour is
 * unchanged — the hook observes, it never blocks or alters a result.
 *
 * Two things to know about the mechanism, both learned by measuring rather than assuming:
 *
 * 1. Only callers going through `objc_msgSend` are seen. That is every Objective-C and Swift
 *    caller, which is every third-party SDK this exists to watch. Kotlin/Native's *own* bindings
 *    bypass the swizzle, so a test that calls the API from Kotlin observes nothing and would
 *    report a false negative — tests must call through the runtime, as a real SDK does.
 * 2. Only zero-argument selectors are hooked. An IMP's signature has to match the method's
 *    exactly, and getting that wrong corrupts the stack rather than failing cleanly. The
 *    zero-argument shapes already cover the identifier reads and the start-collecting calls that
 *    matter; adding an argument shape means adding it deliberately, per signature.
 *
 * Reporting is deduplicated per (api, caller): one event the first time an image touches an API,
 * with a running count. A location callback firing sixty times a minute must not become sixty
 * events, or the signal drowns in its own volume.
 */
object SensitiveApiMonitor {

    /**
     * The default watch list.
     *
     * Deliberately small and all public API. These are the calls that identify a user or their
     * device, or start collecting their location — the ones a privacy review actually asks about.
     */
    val DEFAULT_APIS: List<SensitiveApi> = listOf(
        SensitiveApi("UIPasteboard", "string", "pasteboard_read", instanceAccessor = "generalPasteboard"),
        SensitiveApi("UIPasteboard", "strings", "pasteboard_read", instanceAccessor = "generalPasteboard"),
        SensitiveApi("ASIdentifierManager", "advertisingIdentifier", "advertising_id_read"),
        SensitiveApi("UIDevice", "identifierForVendor", "vendor_id_read"),
        SensitiveApi("CTTelephonyNetworkInfo", "subscriberCellularProvider", "carrier_info_read"),
        SensitiveApi("CLLocationManager", "startUpdatingLocation", "location_start", SensitiveApi.Shape.Action),
        SensitiveApi("CLLocationManager", "requestAlwaysAuthorization", "location_permission_request", SensitiveApi.Shape.Action),
        SensitiveApi("WKWebView", "userAgent", "user_agent_read"),
    )

    /** One observed (api, caller) pair. */
    data class Observation(
        val eventName: String,
        val className: String,
        val selector: String,
        val callerImage: String?,
        var count: Long,
        val firstSeenMs: Long,
    )

    // Written from hooks that run on whatever thread the caller is on, read by drain().
    private val observations = mutableMapOf<String, Observation>()
    private val lock = kotlin.concurrent.AtomicInt(0)

    /** Selector pointer -> which API it belongs to, so one hook can serve every getter. */
    private val bySelector = mutableMapOf<Long, SensitiveApi>()
    private val originalGetters = mutableMapOf<Long, COpaquePointer>()
    private val originalActions = mutableMapOf<Long, COpaquePointer>()

    private var installed = false

    /** Selectors that were successfully hooked, for reporting what is actually being watched. */
    val watching: List<SensitiveApi> get() = bySelector.values.toList()

    /**
     * Install the hooks. Idempotent.
     *
     * A class that is not present in this build is skipped silently: an app without CoreTelephony
     * linked is not an error, it simply cannot make that call.
     */
    fun install(apis: List<SensitiveApi> = DEFAULT_APIS): Int {
        if (installed) return bySelector.size
        installed = true
        var hooked = 0
        for (api in apis) {
            val declared = objc_getClass(api.className) as? ObjCClass ?: continue
            // Hook the class that actually implements the method for real instances, which for a
            // class cluster is not the one the header advertises.
            val cls = api.instanceAccessor?.let { implementingClass(declared, it) } ?: declared
            val sel = sel_registerName(api.selector) ?: continue
            val method = class_getInstanceMethod(cls, sel) ?: continue
            val key = sel.rawValue.toLong()
            bySelector[key] = api
            when (api.shape) {
                SensitiveApi.Shape.Getter -> {
                    val old = method_setImplementation(method, getterHook.reinterpret()) ?: continue
                    originalGetters[key] = old
                }
                SensitiveApi.Shape.Action -> {
                    val old = method_setImplementation(method, actionHook.reinterpret()) ?: continue
                    originalActions[key] = old
                }
            }
            hooked++
        }
        return hooked
    }

    /** Take everything observed so far and clear it, so each drain reports only new activity. */
    fun drain(): List<Observation> {
        while (!lock.compareAndSet(0, 1)) { /* spin; contention here is microseconds */ }
        try {
            val out = observations.values.map { it.copy() }
            observations.clear()
            return out
        } finally {
            lock.value = 0
        }
    }

    /**
     * Record one call. Called from a hook on the caller's thread, so it is kept to a map update
     * under a short spin lock — no allocation-heavy work, no I/O, and never anything that could
     * call back into the API being hooked.
     */
    private fun record(api: SensitiveApi, nowMs: Long) {
        val caller = CallerAttribution.callerImage(skip = 2)
        val key = "${api.eventName}|${caller ?: "?"}"
        while (!lock.compareAndSet(0, 1)) { }
        try {
            val existing = observations[key]
            if (existing == null) {
                observations[key] = Observation(api.eventName, api.className, api.selector, caller, 1, nowMs)
            } else {
                existing.count++
            }
        } finally {
            lock.value = 0
        }
    }

    /**
     * Ask [declared] for its canonical instance via [accessor] and return that object's real class.
     *
     * The accessor is invoked through its own implementation pointer rather than through a
     * variadic `objc_msgSend`, whose signature cannot be expressed from Kotlin. Returns null if
     * anything is missing, and the caller falls back to the declared class.
     */
    private fun implementingClass(declared: ObjCClass, accessor: String): ObjCClass? {
        val sel = sel_registerName(accessor) ?: return null
        val method = class_getClassMethod(declared, sel) ?: return null
        val imp = method_getImplementation(method) ?: return null
        // A Class is itself an object, so its pointer is what the accessor expects as `self`.
        val classPtr: COpaquePointer? = interpretCPointer(declared.objcPtr())
        val instance = callGetter(imp, classPtr, sel) ?: return null
        return object_getClass(interpretObjCPointer<Any>(instance.rawValue)) as? ObjCClass
    }

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private val getterHook = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?> { self, cmd ->
        val key = cmd?.rawValue?.toLong()
        val api = if (key == null) null else bySelector[key]
        if (api != null) record(api, nowMs())
        // Chain to the original. If it is missing, install went wrong; returning null beats
        // calling an arbitrary pointer.
        val original = if (key == null) null else originalGetters[key]
        if (original == null) null else callGetter(original, self, cmd)
    }

    private val actionHook = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { self, cmd ->
        val key = cmd?.rawValue?.toLong()
        val api = if (key == null) null else bySelector[key]
        if (api != null) record(api, nowMs())
        val original = if (key == null) null else originalActions[key]
        if (original != null) callAction(original, self, cmd)
    }
}
