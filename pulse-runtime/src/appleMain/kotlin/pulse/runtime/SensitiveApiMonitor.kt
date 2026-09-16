@file:OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

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
import platform.objc.class_getSuperclass
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
    /** Exact Objective-C ABI shape used by the replacement IMP; see [SensitiveApiMonitor]. */
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
        /** `void (*)(id, SEL, id)` — one object-sized argument, commonly a completion block. */
        Action1,
        /** `void (*)(id, SEL, id, id)` — two object-sized arguments. */
        Action2,
        /** `void (*)(id, SEL, id, id, id)` — three object-sized arguments. */
        Action3,
        /** `void (*)(id, SEL, NSUInteger, id)` — options/entity plus a completion block. */
        ActionUInt1,
    }
}

/** `id (*)(id, SEL)`. */
private typealias GetterImp = CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>
/** `void (*)(id, SEL)`. */
private typealias ActionImp = CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>
/** `id (*)(id, SEL, id)`. */
private typealias Getter1Imp = CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> COpaquePointer?>
/** `void (*)(id, SEL, id)`. */
private typealias Action1Imp = CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>
/** `void (*)(id, SEL, id, id)`. */
private typealias Action2Imp = CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>
/** `void (*)(id, SEL, id, id, id)`. */
private typealias Action3Imp = CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>
/** `void (*)(id, SEL, NSUInteger, id)`. */
private typealias ActionUInt1Imp = CFunction<(COpaquePointer?, COpaquePointer?, ULong, COpaquePointer?) -> Unit>

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

private fun callAction1(imp: COpaquePointer, self: COpaquePointer?, cmd: COpaquePointer?, arg: COpaquePointer?) {
    imp.reinterpret<Action1Imp>().invoke(self, cmd, arg)
}

private fun callAction2(
    imp: COpaquePointer,
    self: COpaquePointer?,
    cmd: COpaquePointer?,
    first: COpaquePointer?,
    second: COpaquePointer?,
) {
    imp.reinterpret<Action2Imp>().invoke(self, cmd, first, second)
}

private fun callAction3(
    imp: COpaquePointer,
    self: COpaquePointer?,
    cmd: COpaquePointer?,
    first: COpaquePointer?,
    second: COpaquePointer?,
    third: COpaquePointer?,
) {
    imp.reinterpret<Action3Imp>().invoke(self, cmd, first, second, third)
}

private fun callActionUInt1(
    imp: COpaquePointer,
    self: COpaquePointer?,
    cmd: COpaquePointer?,
    value: ULong,
    completion: COpaquePointer?,
) {
    imp.reinterpret<ActionUInt1Imp>().invoke(self, cmd, value, completion)
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
 * 1. Only calls that dispatch through the Objective-C method implementation are seen. This covers
 *    the Objective-C system APIs used by many Objective-C and Swift SDKs, but not pure Swift,
 *    C/C++, Network.framework or raw socket calls. Kotlin/Native's *own* bindings may bypass the
 *    swizzle, so a test must verify ordinary Objective-C dispatch rather than assume coverage.
 * 2. Every selector is assigned an explicit ABI shape. An IMP's signature has to match the
 *    method exactly, and getting that wrong corrupts the stack rather than failing cleanly. New
 *    argument/return layouts are added deliberately and tested instead of going through a
 *    variadic forwarding function.
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
        SensitiveApi("CLLocationManager", "requestWhenInUseAuthorization", "location_permission_request", SensitiveApi.Shape.Action),
        SensitiveApi("CLLocationManager", "startMonitoringSignificantLocationChanges", "location_monitoring_start", SensitiveApi.Shape.Action),
        SensitiveApi("WKWebView", "userAgent", "user_agent_read"),

        // Reads from stores commonly probed by injected analytics/advertising SDKs. Values and
        // keys are deliberately not recorded; the evidence says which store API was touched and
        // which image made the call.
        SensitiveApi("NSUserDefaults", "objectForKey:", "user_defaults_read", SensitiveApi.Shape.Getter1),
        SensitiveApi("NSUserDefaults", "stringForKey:", "user_defaults_read", SensitiveApi.Shape.Getter1),
        SensitiveApi("NSUserDefaults", "dataForKey:", "user_defaults_read", SensitiveApi.Shape.Getter1),
        SensitiveApi("NSUserDefaults", "setObject:forKey:", "user_defaults_write", SensitiveApi.Shape.Action2),
        SensitiveApi("NSHTTPCookieStorage", "cookies", "http_cookie_read"),
        SensitiveApi("NSHTTPCookieStorage", "cookiesForURL:", "http_cookie_read", SensitiveApi.Shape.Getter1),

        // Photo library. These are class methods — asking whether the app may read the user's
        // photos does not involve an instance — which is why class-method hooking exists at all.
        SensitiveApi(
            "PHPhotoLibrary", "authorizationStatus", "photo_library_status_read",
            isClassMethod = true,
        ),
        SensitiveApi(
            "PHPhotoLibrary", "requestAuthorization:", "photo_library_permission_request",
            shape = SensitiveApi.Shape.Action1, isClassMethod = true,
        ),
        SensitiveApi(
            "PHPhotoLibrary", "requestAuthorizationForAccessLevel:handler:", "photo_library_permission_request",
            shape = SensitiveApi.Shape.ActionUInt1, isClassMethod = true,
        ),

        // Permission entry points. Completion blocks are forwarded unchanged. The monitor never
        // invokes these methods and therefore never creates a permission prompt itself.
        SensitiveApi(
            "AVCaptureDevice", "requestAccessForMediaType:completionHandler:", "camera_microphone_permission_request",
            shape = SensitiveApi.Shape.Action2, isClassMethod = true,
        ),
        SensitiveApi(
            "AVAudioSession", "requestRecordPermission:", "microphone_permission_request",
            shape = SensitiveApi.Shape.Action1, instanceAccessor = "sharedInstance",
        ),
        SensitiveApi(
            "CNContactStore", "requestAccessForEntityType:completionHandler:", "contacts_permission_request",
            shape = SensitiveApi.Shape.ActionUInt1,
        ),
        SensitiveApi(
            "ATTrackingManager", "requestTrackingAuthorizationWithCompletionHandler:", "tracking_permission_request",
            shape = SensitiveApi.Shape.Action1, isClassMethod = true,
        ),
        SensitiveApi(
            "UNUserNotificationCenter", "requestAuthorizationWithOptions:completionHandler:", "notification_permission_request",
            // Do not call +currentNotificationCenter merely to discover its private concrete
            // class: it raises an Objective-C exception in tools/tests without an application
            // bundle, and Objective-C exceptions cannot be caught by Kotlin. Swizzling the public
            // class remains passive and is the safe default.
            shape = SensitiveApi.Shape.ActionUInt1,
        ),
        SensitiveApi(
            "EKEventStore", "requestAccessToEntityType:completion:", "calendar_reminders_permission_request",
            shape = SensitiveApi.Shape.ActionUInt1,
        ),
        SensitiveApi(
            "EKEventStore", "requestFullAccessToEventsWithCompletion:", "calendar_permission_request",
            shape = SensitiveApi.Shape.Action1,
        ),
        SensitiveApi(
            "EKEventStore", "requestFullAccessToRemindersWithCompletion:", "reminders_permission_request",
            shape = SensitiveApi.Shape.Action1,
        ),
        SensitiveApi(
            "HKHealthStore", "requestAuthorizationToShareTypes:readTypes:completion:", "health_permission_request",
            shape = SensitiveApi.Shape.Action3,
        ),

        // Web content bridges are a common exfiltration path for injected plugins. This records
        // bridge installation/evaluation, never the script text or message contents.
        SensitiveApi(
            "WKUserContentController", "addScriptMessageHandler:name:", "webview_message_bridge_installed",
            shape = SensitiveApi.Shape.Action2,
        ),
        SensitiveApi(
            "WKUserContentController", "addUserScript:", "webview_script_injected",
            shape = SensitiveApi.Shape.Action1,
        ),
        SensitiveApi(
            "WKWebView", "evaluateJavaScript:completionHandler:", "webview_javascript_evaluated",
            shape = SensitiveApi.Shape.Action2,
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
        /** Nearest retained symbol in the calling plugin, when the release binary exposes one. */
        val callerSymbol: String?,
        /** ASLR-independent offset from the caller image base; useful when symbols are stripped. */
        val callsiteOffset: Long?,
        /** Stable hash of normalized image+offset frames, never a list of raw process addresses. */
        val stackFingerprint: String?,
        /** Bounded normalized frames used to explain the plugin's internal call path. */
        val callPath: String?,
        /** Short non-identifying label, such as the host a request went to. */
        val detail: String?,
        var count: Long,
        val firstSeenMs: Long,
        var lastSeenMs: Long = firstSeenMs,
    )

    /** What the SDK intended to watch versus what the current process could actually arm. */
    data class Health(
        val expectedCount: Int,
        val hookedCount: Int,
        val pending: List<SensitiveApi>,
    )

    private data class InstalledHook(
        val api: SensitiveApi,
        /** The concrete class whose method table was changed. */
        val ownerClass: Long,
        val selector: Long,
        val original: COpaquePointer,
    )

    // Written from hooks that run on whatever thread the caller is on, read by drain().
    private val observations = mutableMapOf<String, Observation>()
    private val lock = kotlin.concurrent.AtomicInt(0)

    /**
     * Selector -> installed hooks. A selector is process-global and is not a method identity:
     * unrelated classes routinely expose the same selector (for example Photos and ATT both use
     * `requestAuthorizationWithCompletionHandler:`). Keeping only one entry silently misattributes
     * the second class and chains to the wrong IMP. Resolution therefore also checks the receiver's
     * concrete class/superclass chain.
     */
    private val installedBySelector = mutableMapOf<Long, MutableList<InstalledHook>>()
    private var expected = emptyList<SensitiveApi>()

    /**
     * APIs whose class was not loaded yet when install ran.
     *
     * Frameworks load lazily: Photos is not in the process until something touches it, so an
     * install at launch — which is where it has to be, to catch what an SDK does at startup — will
     * find several classes missing. They are retried on the polling tick instead of being lost,
     * which is the only arrangement that satisfies both halves of the problem.
     */
    private val pending = mutableListOf<SensitiveApi>()

    /** APIs successfully hooked, for reporting what is actually being watched. */
    val watching: List<SensitiveApi>
        get() = withLock { installedBySelector.values.flatten().map { it.api } }

    fun health(): Health = withLock {
        Health(
            expectedCount = expected.distinctBy { it.className to it.selector }.size,
            hookedCount = installedBySelector.values.sumOf { it.size },
            pending = pending.distinctBy { it.className to it.selector },
        )
    }

    /**
     * Install the hooks. Idempotent.
     *
     * A class that is not present in this build is skipped silently: an app without CoreTelephony
     * linked is not an error, it simply cannot make that call.
     */
    fun install(apis: List<SensitiveApi> = DEFAULT_APIS): Int {
        withLock {
            expected = apis.toList()
            pending.clear()
        }
        return hook(apis)
    }

    /**
     * Retry the APIs whose framework had not loaded at install time. Returns how many were newly
     * hooked, so a caller can report a watch list that grew.
     */
    fun installPending(): Int {
        val retry = withLock {
            if (pending.isEmpty()) return 0
            pending.toList().also { pending.clear() }
        }
        return hook(retry)
    }

    private fun hook(apis: List<SensitiveApi>): Int {
        var hooked = 0
        for (api in apis) {
            // Not loaded yet rather than absent: keep it for the next attempt. A build that
            // genuinely never links the framework simply retries forever at no cost.
            val declared = objc_getClass(api.className) as? ObjCClass
            if (declared == null) {
                addPending(api)
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
                addPending(api)
                continue
            }
            val selectorKey = sel.rawValue.toLong()
            val classKey = cls.objcPtr().toLong()
            val installed = withLock {
                val existing = installedBySelector[selectorKey]
                    ?.any { it.ownerClass == classKey && it.api.isClassMethod == api.isClassMethod }
                    ?: false
                if (existing) return@withLock false

                // Keep the registry lock across swizzling and registration. Once the replacement
                // is visible another thread may enter it immediately; it will wait briefly here
                // rather than observe a hook with no original IMP to chain to.
                val old = when (api.shape) {
                    SensitiveApi.Shape.Getter -> method_setImplementation(method, getterHook.reinterpret())
                    SensitiveApi.Shape.Action -> method_setImplementation(method, actionHook.reinterpret())
                    SensitiveApi.Shape.Getter1 -> method_setImplementation(method, getter1Hook.reinterpret())
                    SensitiveApi.Shape.Action1 -> method_setImplementation(method, action1Hook.reinterpret())
                    SensitiveApi.Shape.Action2 -> method_setImplementation(method, action2Hook.reinterpret())
                    SensitiveApi.Shape.Action3 -> method_setImplementation(method, action3Hook.reinterpret())
                    SensitiveApi.Shape.ActionUInt1 -> method_setImplementation(method, actionUInt1Hook.reinterpret())
                } ?: return@withLock false
                installedBySelector.getOrPut(selectorKey) { mutableListOf() }
                    .add(InstalledHook(api, classKey, selectorKey, old))
                true
            }
            if (installed) hooked++
        }
        return hooked
    }

    private fun addPending(api: SensitiveApi) = withLock {
        if (pending.none { it.className == api.className && it.selector == api.selector }) pending.add(api)
    }

    private inline fun <T> withLock(block: () -> T): T {
        while (!lock.compareAndSet(0, 1)) { /* hook contention is intentionally very short */ }
        try {
            return block()
        } finally {
            lock.value = 0
        }
    }

    private fun resolve(self: COpaquePointer?, cmd: COpaquePointer?): InstalledHook? {
        if (self == null || cmd == null) return null
        val selectorKey = cmd.rawValue.toLong()
        return withLock {
            val candidates = installedBySelector[selectorKey] ?: return@withLock null
            val selfKey = self.rawValue.toLong()
            candidates.firstOrNull { it.api.isClassMethod && it.ownerClass == selfKey }
                ?: candidates.firstOrNull { !it.api.isClassMethod && receiverIsKindOf(self, it.ownerClass) }
        }
    }

    private fun receiverIsKindOf(self: COpaquePointer, ownerClass: Long): Boolean {
        var cls = object_getClass(interpretObjCPointer<Any>(self.rawValue)) as? ObjCClass
        while (cls != null) {
            if (cls.objcPtr().toLong() == ownerClass) return true
            cls = class_getSuperclass(cls)
        }
        return false
    }

    /** Take everything observed so far and clear it, so each drain reports only new activity. */
    fun drain(): List<Observation> = withLock {
        observations.values.map { it.copy() }.also { observations.clear() }
    }

    /**
     * Record one call. Called from a hook on the caller's thread, so it is kept to a map update
     * under a short spin lock — no allocation-heavy work, no I/O, and never anything that could
     * call back into the API being hooked.
     */
    private fun record(api: SensitiveApi, self: COpaquePointer?, nowMs: Long) {
        val caller = CallerAttribution.caller(skip = 2)
        // Calls made by PulseKit itself eventually unwind into Foundation/libdispatch. Those
        // system frames are not plugin evidence. If no other non-system image exists in the
        // captured path, suppress the observation rather than inventing an unattributed finding.
        if (caller.image == null) return
        // A detail separates observations that are genuinely different — two hosts are two
        // findings, not one with a count of two. Failures are swallowed: a detail that cannot be
        // read is a missing label, never a reason to disturb the call being observed.
        val detail = try {
            api.detailOf?.invoke(self)
        } catch (_: Throwable) {
            null
        }
        // Keep distinct internal plugin entry points distinct. An ad SDK reading IDFV during
        // startup and reading it again immediately before upload are different actions even
        // though they end at the same system selector.
        val key = "${api.eventName}|${caller.image}|${caller.symbol ?: ""}|" +
            "${caller.stackFingerprint ?: ""}|${detail ?: ""}"
        withLock {
            val existing = observations[key]
            if (existing == null) {
                observations[key] = Observation(
                    api.eventName, api.className, api.selector, caller.image, caller.symbol,
                    caller.imageOffset, caller.stackFingerprint, caller.callPath, detail, 1, nowMs, nowMs,
                )
            } else {
                existing.count++
                existing.lastSeenMs = nowMs
            }
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
        val hook = resolve(self, cmd)
        if (hook != null) record(hook.api, self, nowMs())
        // Chain to the original. If it is missing, install went wrong; returning null beats
        // calling an arbitrary pointer.
        if (hook == null) null else callGetter(hook.original, self, cmd)
    }

    private val getter1Hook = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?> { self, cmd, arg ->
        val hook = resolve(self, cmd)
        if (hook != null) record(hook.api, self, nowMs())
        if (hook == null) null else callGetter1(hook.original, self, cmd, arg)
    }

    private val actionHook = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { self, cmd ->
        val hook = resolve(self, cmd)
        if (hook != null) {
            record(hook.api, self, nowMs())
            callAction(hook.original, self, cmd)
        }
    }

    private val action1Hook = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> { self, cmd, arg ->
        val hook = resolve(self, cmd)
        if (hook != null) {
            record(hook.api, self, nowMs())
            callAction1(hook.original, self, cmd, arg)
        }
    }

    private val action2Hook = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> {
            self, cmd, first, second ->
        val hook = resolve(self, cmd)
        if (hook != null) {
            record(hook.api, self, nowMs())
            callAction2(hook.original, self, cmd, first, second)
        }
    }

    private val action3Hook = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> {
            self, cmd, first, second, third ->
        val hook = resolve(self, cmd)
        if (hook != null) {
            record(hook.api, self, nowMs())
            callAction3(hook.original, self, cmd, first, second, third)
        }
    }

    private val actionUInt1Hook = staticCFunction<COpaquePointer?, COpaquePointer?, ULong, COpaquePointer?, Unit> {
            self, cmd, value, completion ->
        val hook = resolve(self, cmd)
        if (hook != null) {
            record(hook.api, self, nowMs())
            callActionUInt1(hook.original, self, cmd, value, completion)
        }
    }
}
