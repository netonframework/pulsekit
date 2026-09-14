@file:OptIn(ExperimentalForeignApi::class, kotlin.native.concurrent.ObsoleteWorkersApi::class)

package pulse.runtime

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import platform.UIKit.UIDevice
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two things the earlier tests did not actually establish.
 *
 * The first is whether a normal Objective-C message reaches the replacement at all. Invoking an
 * IMP pointer directly — which is what the earlier test did — proves the pointer is callable, not
 * that `objc_msgSend` dispatches to it. Those are different claims and only the second one matters.
 *
 * The second is whether install and call can happen on different threads, which is the only
 * arrangement that occurs in practice: the SDK arms the hooks on its own reactor thread while the
 * app calls these APIs from the main thread.
 *
 * In iosTest rather than appleTest: these exercise UIKit classes, which do not exist on macOS.
 * The monitor itself runs on any Apple target — install() skips classes the build does not link —
 * but a test that names UIPasteboard cannot.
 */
class MonitorDispatchTest {

    @Test
    fun anOrdinaryObjcMessageReachesTheReplacement() {
        SensitiveApiMonitor.install()
        SensitiveApiMonitor.drain()

        // No IMP pointers, no runtime tricks: the same call any Swift or Objective-C code makes.
        UIDevice.currentDevice.identifierForVendor

        val seen = SensitiveApiMonitor.drain()
        println("DISPATCH same-thread observed=${seen.map { it.eventName }}")
        assertTrue(
            seen.any { it.eventName == "vendor_id_read" },
            "objc_msgSend did not dispatch to the replacement; observed=${seen.map { it.eventName }}",
        )
    }

    @Test
    fun hooksInstalledOnOneThreadAreVisibleToCallersOnAnother() {
        // How it really runs: armed on the SDK's own thread, called from the app's.
        val worker = Worker.start(name = "monitor-install")
        worker.execute(TransferMode.SAFE, { Unit }) { SensitiveApiMonitor.install() }.result
        worker.requestTermination().result

        SensitiveApiMonitor.drain()
        UIDevice.currentDevice.identifierForVendor
        val seen = SensitiveApiMonitor.drain()

        println("DISPATCH cross-thread observed=${seen.map { it.eventName }}")
        assertTrue(
            seen.any { it.eventName == "vendor_id_read" },
            "hooks armed on another thread were invisible here; observed=${seen.map { it.eventName }}",
        )
    }

    @Test
    fun aClipboardReadIsObserved() {
        // The headline case for this whole feature: an SDK quietly reading what the user copied.
        // Worth its own test rather than trusting that hooking one UIKit getter implies the rest —
        // UIPasteboard has a different lifetime and access model from UIDevice.
        SensitiveApiMonitor.install()
        SensitiveApiMonitor.drain()

        val pb = platform.UIKit.UIPasteboard.generalPasteboard
        val actualClass = platform.objc.object_getClass(pb)
        println("DISPATCH pasteboard declared=UIPasteboard actual=${actualClass?.let { platform.objc.class_getName(it)?.toKString() }}")
        val dev = platform.UIKit.UIDevice.currentDevice
        println("DISPATCH device actual=${platform.objc.object_getClass(dev)?.let { platform.objc.class_getName(it)?.toKString() }}")
        pb.string

        val seen = SensitiveApiMonitor.drain()
        println("DISPATCH pasteboard observed=${seen.map { it.eventName }}")
        assertTrue(
            seen.any { it.eventName == "pasteboard_read" },
            "a clipboard read was not observed; observed=${seen.map { it.eventName }}",
        )
    }
}
