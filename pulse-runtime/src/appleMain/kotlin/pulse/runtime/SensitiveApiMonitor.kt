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
    /**
     * True when the selector is a class method (`+foo`) rather than an instance method.
     *
     * The photo library's entry points are class methods: `+[PHPhotoLibrary authorizationStatus]`
     * is how an app asks whether it may read the user's photos at all, and there is no instance
     * involved.
     */
    val isClassMethod: Boolean = false,
    /**
     * Extracts a short, non-identifying detail from the receiver, recorded alongside the call and
     * used to separate observations that are worth telling apart — a request to two different
     * hosts is two findings, not one with a count of two.
     */
    val detailOf: ((COpaquePointer?) -> String?)? = null,
) {
    enum class Shape {
        /** `id (*)(id, SEL)` — a getter such as -[UIPasteboard string]. */
        Getter,
        /** `void (*)(id, SEL)` — an action such as -[CLLocationManager startUpdatingLocation]. */
        Action,
        /** `id (*)(id, SEL, id)` — one object argument, such as +[PHPhotoLibrary requestAuthorization:]. */
        Getter1,
    }
}

/** `id (*)(id, SEL)`. */
private typealias GetterImp = CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>
/** `void (*)(id, SEL)`. */
private typealias ActionImp = CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>
/** `id (*)(id, SEL, id)`. */
private typealias Getter1Imp = CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> COpaquePointer?>

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

private fun callGetter1(
    imp: COpaquePointer,
    self: COpaquePointer?,
    cmd: COpaquePointer?,
    arg: COpaquePointer?,
): COpaquePointer? = imp.reinterpret<Getter1Imp>().invoke(self, cmd, arg)

/**
 * Observes calls to sensitive Objective-C APIs and attributes them to the image that made them.
 *
 * How it works: the method's implementation is replaced with one that records the call, works out
 * the caller's image from the return addresses, and then chains to the original. Behaviour is
 * unchanged — the hook observes, it never blocks or alters a result.
 *
 * Two things to know about the mechanism, both learned by measuring rather than assuming:
 *
 * 1. Only calls that dispatch through the Objective-C method implementation are seen. This covers
 *    the Objective-C system APIs used by many Objective-C and Swift SDKs, but not pure Swift,
 *    C/C++, Network.framework or raw socket calls. Kotlin/Native's *own* bindings may bypass the
 *    swizzle, so a test must verify ordinary Objective-C dispatch rather than assume coverage.
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

        // Photo library. These are class methods — asking whether the app may read the user's
        // photos does not involve an instance — which is why class-method hooking exists at all.
        SensitiveApi(
            "PHPhotoLibrary", "authorizationStatus", "photo_library_status_read",
            isClassMethod = true,
        ),
        SensitiveApi(
            "PHPhotoLibrary", "requestAuthorization:", "photo_library_permission_request",
            shape = SensitiveApi.Shape.Getter1, isClassMethod = true,
        ),

        // Network. Hooking -[NSURLSessionTask resume] rather than the many dataTaskWith… factory
        // methods: every one of those funnels into a task that must be resumed to do anything, so
        // one zero-argument selector covers the whole surface — and the receiver carries the
        // destination, which the factory arguments would only give for some of the overloads.
        SensitiveApi(
            "NSURLSessionTask", "resume", "network_request",
            shape = SensitiveApi.Shape.Action,
            instanceAccessor = URL_SESSION_TASK_ACCESSOR,
            detailOf = { self -> NetworkDetail.ofTask(self) },
        ),
    )

    /**
     * Marker telling [install] to resolve NSURLSessionTask's concrete class from a real task.
     *
     * Like UIPasteboard it is a class cluster, but unlike UIPasteboard there is no class method
     * returning a canonical instance — a task has to be created. Creating one performs no network
     * I/O; only resume() does, and the probe task is never resumed.
     */
    internal const val URL_SESSION_TASK_ACCESSOR = "__probe_url_session_task"

    /** One observed (api, caller) pair. */
    data class Observation(
        val eventName: String,
        val className: String,
        val selector: String,
        val callerImage: String?,
        /** Short non-identifying label, such as the host a request went to. */
        val detail: String?,
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
    private val originalGetters1 = mutableMapOf<Long, COpaquePointer>()

    /**
     * APIs whose class was not loaded yet when install ran.
     *
     * Frameworks load lazily: Photos is not in the process until something touches it, so an
     * install at launch — which is where it has to be, to catch what an SDK does at startup — will
     * find several classes missing. They are retried on the polling tick instead of being lost,
     * which is the only arrangement that satisfies both halves of the problem.
     */
    private val pending = mutableListOf<SensitiveApi>()

    /** Selectors that were successfully hooked, for reporting what is actually being watched. */
    val watching: List<SensitiveApi> get() = bySelector.values.toList()

    /**
     * Install the hooks. Idempotent.
     *
     * A class that is not present in this build is skipped silently: an app without CoreTelephony
     * linked is not an error, it simply cannot make that call.
     */
    fun install(apis: List<SensitiveApi> = DEFAULT_APIS): Int {
        pending.clear()
        return hook(apis)
    }

    /**
     * Retry the APIs whose framework had not loaded at install time. Returns how many were newly
     * hooked, so a caller can report a watch list that grew.
     */
    fun installPending(): Int {
        if (pending.isEmpty()) return 0
        val retry = pending.toList()
        pending.clear()
        return hook(retry)
    }

    private fun hook(apis: List<SensitiveApi>): Int {
        var hooked = 0
        for (api in apis) {
            // Not loaded yet rather than absent: keep it for the next attempt. A build that
            // genuinely never links the framework simply retries forever at no cost.
            val declared = objc_getClass(api.className) as? ObjCClass
            if (declared == null) {
                pending.add(api)
                continue
            }
            // Hook the class that actually implements the method for real instances, which for a
            // class cluster is not the one the header advertises.
            val accessor = api.instanceAccessor
            val cls = when {
                accessor == null -> declared
                accessor == URL_SESSION_TASK_ACCESSOR -> probeUrlSessionTaskClass() ?: declared
                else -> implementingClass(declared, accessor) ?: declared
            }
            val sel = sel_registerName(api.selector) ?: continue
            // A class method lives on the metaclass; class_getClassMethod finds it there.
            val method = if (api.isClassMethod) class_getClassMethod(cls, sel)
            else class_getInstanceMethod(cls, sel)
            if (method == null) {
                pending.add(api)
                continue
            }
            val key = sel.rawValue.toLong()
            // Already hooked: skip. Swizzling twice would record our own replacement as "the
            // original" and every call would then recurse into itself — the failure mode is a
            // stack overflow on the user's device, not a missing event.
            if (bySelector.containsKey(key)) continue
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
                SensitiveApi.Shape.Getter1 -> {
                    val old = method_setImplementation(method, getter1Hook.reinterpret()) ?: continue
                    originalGetters1[key] = old
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
    private fun record(api: SensitiveApi, self: COpaquePointer?, nowMs: Long) {
        val caller = CallerAttribution.callerImage(skip = 2)
        // A detail separates observations that are genuinely different — two hosts are two
        // findings, not one with a count of two. Failures are swallowed: a detail that cannot be
        // read is a missing label, never a reason to disturb the call being observed.
        val detail = try {
            api.detailOf?.invoke(self)
        } catch (_: Throwable) {
            null
        }
        val key = "${api.eventName}|${caller ?: "?"}|${detail ?: ""}"
        while (!lock.compareAndSet(0, 1)) { }
        try {
            val existing = observations[key]
            if (existing == null) {
                observations[key] = Observation(api.eventName, api.className, api.selector, caller, detail, 1, nowMs)
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

    /**
     * The concrete class behind NSURLSessionTask, found by creating a task and never resuming it.
     *
     * Creating a task is inert — no connection is opened until resume() — so this costs nothing
     * observable and avoids hardcoding a private class name.
     */
    @OptIn(kotlinx.cinterop.BetaInteropApi::class)
    private fun probeUrlSessionTaskClass(): ObjCClass? = try {
        val url = platform.Foundation.NSURL.URLWithString("https://127.0.0.1/") ?: return null
        val task = platform.Foundation.NSURLSession.sharedSession.dataTaskWithURL(url)
        object_getClass(task) as? ObjCClass
    } catch (_: Throwable) {
        null
    }

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private val getterHook = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?> { self, cmd ->
        val key = cmd?.rawValue?.toLong()
        val api = if (key == null) null else bySelector[key]
        if (api != null) record(api, self, nowMs())
        // Chain to the original. If it is missing, install went wrong; returning null beats
        // calling an arbitrary pointer.
        val original = if (key == null) null else originalGetters[key]
        if (original == null) null else callGetter(original, self, cmd)
    }

    private val getter1Hook = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?> { self, cmd, arg ->
        val key = if (cmd == null) null else cmd.rawValue.toLong()
        val api = if (key == null) null else bySelector[key]
        if (api != null) record(api, self, nowMs())
        val original = if (key == null) null else originalGetters1[key]
        if (original == null) null else callGetter1(original, self, cmd, arg)
    }

    private val actionHook = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { self, cmd ->
        val key = cmd?.rawValue?.toLong()
        val api = if (key == null) null else bySelector[key]
        if (api != null) record(api, self, nowMs())
        val original = if (key == null) null else originalActions[key]
        if (original != null) callAction(original, self, cmd)
    }
}
