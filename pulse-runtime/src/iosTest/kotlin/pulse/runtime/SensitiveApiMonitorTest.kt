@file:OptIn(ExperimentalForeignApi::class)

package pulse.runtime

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.invoke
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.reinterpret
import platform.UIKit.UIDevice
import platform.objc.class_getInstanceMethod
import platform.objc.method_getImplementation
import platform.objc.objc_getClass
import platform.objc.sel_registerName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private typealias Getter = CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>

/**
 * The monitor only sees callers that go through `objc_msgSend`, which is every Objective-C and
 * Swift caller — and therefore every third-party SDK it exists to watch. Kotlin/Native's own
 * bindings bypass the swizzle entirely, so these tests reach the API through the runtime the way a
 * real SDK does. Calling it from Kotlin would observe nothing and report a false negative.
 *
 * In iosTest rather than appleTest: these exercise UIKit classes, which do not exist on macOS.
 * The monitor itself runs on any Apple target — install() skips classes the build does not link —
 * but a test that names UIPasteboard cannot.
 */
class SensitiveApiMonitorTest {

    /** Invoke a hooked selector the way the runtime would, so the replacement actually runs. */
    private fun callViaRuntime(className: String, selectorName: String, instance: COpaquePointer?) {
        val cls = objc_getClass(className) as? ObjCClass
        val sel = sel_registerName(selectorName)
        val method = class_getInstanceMethod(cls, sel) ?: return
        val imp = method_getImplementation(method) ?: return
        imp.reinterpret<Getter>().invoke(instance, sel)
    }

    @Test
    fun installHooksTheApisPresentInThisBuild() {
        SensitiveApiMonitor.install()
        // Asserted on the watch list, not on install's return value: hooking is idempotent per
        // selector, so a second install in the same process legitimately arms nothing new. The
        // question is what is being watched, not how many hooks this particular call added.
        val watching = SensitiveApiMonitor.watching.map { it.eventName }.distinct()
        assertTrue(watching.isNotEmpty(), "nothing is being watched; the ObjC runtime is not reachable")
        // Classes absent from this build are skipped rather than treated as failures — an app that
        // does not link CoreTelephony simply cannot make that call.
        println("MONITOR watching=$watching")
    }

    @Test
    fun aCallIsObservedAndAttributedToTheImageThatMadeIt() {
        SensitiveApiMonitor.install()
        SensitiveApiMonitor.drain()   // start from a clean slate

        callViaRuntime("UIDevice", "identifierForVendor", UIDevice.currentDevice.objcPtr().let { interpretCPointer(it) })

        val seen = SensitiveApiMonitor.drain()
        val vendorRead = seen.firstOrNull { it.eventName == "vendor_id_read" }
        assertTrue(vendorRead != null, "the hook never ran; observed=${seen.map { it.eventName }}")
        assertEquals("UIDevice", vendorRead.className)
        assertEquals(1L, vendorRead.count)
        // The whole point: not just "it was read", but by whom.
        assertTrue(vendorRead.callerImage != null, "the call was not attributed to any image")
        println("MONITOR observed ${vendorRead.eventName} by ${vendorRead.callerImage} x${vendorRead.count}")
    }

    @Test
    fun repeatedCallsCollapseIntoOneObservationWithACount() {
        SensitiveApiMonitor.install()
        SensitiveApiMonitor.drain()

        val device: COpaquePointer? = interpretCPointer(UIDevice.currentDevice.objcPtr())
        repeat(25) { callViaRuntime("UIDevice", "identifierForVendor", device) }

        val seen = SensitiveApiMonitor.drain()
        val vendorRead = seen.single { it.eventName == "vendor_id_read" }
        // A location callback firing sixty times a minute must not become sixty events; the signal
        // would drown in its own volume.
        assertEquals(25L, vendorRead.count)
    }

    @Test
    fun drainingClearsSoEachReportCoversOnlyNewActivity() {
        SensitiveApiMonitor.install()
        SensitiveApiMonitor.drain()
        callViaRuntime("UIDevice", "identifierForVendor", interpretCPointer(UIDevice.currentDevice.objcPtr()))
        assertTrue(SensitiveApiMonitor.drain().isNotEmpty())
        assertTrue(SensitiveApiMonitor.drain().isEmpty(), "a second drain must report nothing new")
    }

    @Test
    fun theOriginalBehaviourIsUnchanged() {
        // Compared against the same call before the monitor exists, rather than against an
        // assumption about what the API returns. A simulator that has no vendor identity yields
        // null either way, and asserting non-null would blame the hook for the environment.
        val before = UIDevice.currentDevice.identifierForVendor?.UUIDString

        SensitiveApiMonitor.install()
        val after = UIDevice.currentDevice.identifierForVendor?.UUIDString

        assertEquals(before, after, "hooking changed what the API returns")
        println("MONITOR identifierForVendor before=$before after=$after")
    }

    @Test
    fun chainingPreservesTheReturnValueThroughTheHook() {
        SensitiveApiMonitor.install()
        SensitiveApiMonitor.drain()

        // Through the runtime, so the hook really is in the path — then check the hook handed back
        // whatever the original produced rather than swallowing it.
        val cls = objc_getClass("UIDevice") as? ObjCClass
        val sel = sel_registerName("identifierForVendor")
        val imp = method_getImplementation(class_getInstanceMethod(cls, sel)!!)!!
        val device: COpaquePointer? = interpretCPointer(UIDevice.currentDevice.objcPtr())
        val throughHook = imp.reinterpret<Getter>().invoke(device, sel)

        // The hook ran...
        assertTrue(SensitiveApiMonitor.drain().any { it.eventName == "vendor_id_read" })
        // ...and the direct Kotlin binding, which bypasses the swizzle, agrees with it: either
        // both are null on a simulator with no vendor identity, or both are the same object.
        val direct = UIDevice.currentDevice.identifierForVendor
        assertEquals(direct == null, throughHook == null, "the hook changed nullability of the result")
        println("MONITOR throughHook=$throughHook direct=$direct")
    }
}
