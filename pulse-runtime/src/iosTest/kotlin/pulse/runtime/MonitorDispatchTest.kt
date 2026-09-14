@file:OptIn(ExperimentalForeignApi::class, kotlin.native.concurrent.ObsoleteWorkersApi::class)

package pulse.runtime

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import platform.UIKit.UIDevice
import platform.Foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun aNetworkRequestIsObservedWithItsDestination() {
        SensitiveApiMonitor.install()
        SensitiveApiMonitor.drain()

        // A task that goes nowhere: the point is that resume() was called, not that it succeeded.
        // Hooking resume rather than the factory methods means every NSURLSession caller is seen,
        // whichever overload it used.
        val url = platform.Foundation.NSURL.URLWithString("https://api.example.test/v1/users?token=secret123#frag")!!
        platform.Foundation.NSURLSession.sharedSession.dataTaskWithURL(url).resume()

        val seen = SensitiveApiMonitor.drain()
        val request = seen.firstOrNull { it.eventName == "network_request" }
        println("DISPATCH network observed=${seen.map { it.eventName }} detail=${request?.detail}")
        assertTrue(request != null, "a network request was not observed")
        assertTrue(request.detail != null, "the request had no destination recorded")
        // The destination is recorded; the credential in the query string is not. A telemetry
        // store that accumulates tokens is a liability, not a feature.
        assertTrue(request.detail.contains("api.example.test"), request.detail)
        assertTrue(!request.detail.contains("secret123"), "the query string was stored: ${request.detail}")
        assertTrue(!request.detail.contains("frag"), "the fragment was stored: ${request.detail}")
    }

    @Test
    fun networkEvidenceIncludesShapeButNeverValues() {
        SensitiveApiMonitor.install()
        SensitiveApiMonitor.drain()

        val url = platform.Foundation.NSURL.URLWithString(
            "https://ads.example.test/collect?device_id=private-device&token=secret-token",
        )!!
        val request = NSMutableURLRequest.requestWithURL(url)
        request.HTTPMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField = "Content-Type")
        request.setValue("Bearer private-token", forHTTPHeaderField = "Authorization")
        request.HTTPBody = NSData()
        platform.Foundation.NSURLSession.sharedSession.dataTaskWithRequest(request).resume()

        val detail = SensitiveApiMonitor.drain()
            .single { it.eventName == "network_request" }.detail.orEmpty()
        assertTrue(detail.startsWith("POST https://ads.example.test/collect"), detail)
        assertTrue(detail.contains("query_keys=device_id,token"), detail)
        assertTrue(detail.contains("Authorization"), detail)
        assertTrue(detail.contains("Content-Type"), detail)
        assertTrue(detail.contains("content_type=application/json"), detail)
        assertTrue(detail.contains("body_bytes=0"), detail)
        assertTrue(!detail.contains("private-device"), detail)
        assertTrue(!detail.contains("secret-token"), detail)
        assertTrue(!detail.contains("Bearer"), detail)
    }

    @Test
    fun twoHostsAreTwoObservations() {
        SensitiveApiMonitor.install()
        SensitiveApiMonitor.drain()

        for (host in listOf("https://a.example.test/x", "https://b.example.test/y")) {
            platform.Foundation.NSURLSession.sharedSession
                .dataTaskWithURL(platform.Foundation.NSURL.URLWithString(host)!!).resume()
        }

        val requests = SensitiveApiMonitor.drain().filter { it.eventName == "network_request" }
        // Talking to two different endpoints is two findings, not one with a count of two —
        // "this SDK contacted an unknown host" is the question being asked.
        assertEquals(2, requests.size, "hosts were collapsed: ${requests.map { it.detail }}")
    }

    @Test
    fun thePhotoLibraryStatusCheckIsObserved() {
        SensitiveApiMonitor.install()
        SensitiveApiMonitor.drain()

        // A class method, and the first thing anything touching the user's photos has to call.
        platform.Photos.PHPhotoLibrary.authorizationStatus()

        val seen = SensitiveApiMonitor.drain()
        println("DISPATCH photos observed=${seen.map { it.eventName }}")
        assertTrue(
            seen.any { it.eventName == "photo_library_status_read" },
            "a photo library access check was not observed; observed=${seen.map { it.eventName }}",
        )
    }

    @Test
    fun installingTwiceDoesNotChainTheHookToItself() {
        // Swizzling the same selector twice would store the replacement as "the original", so
        // every later call would recurse into itself and overflow the stack on a user's device.
        // Re-installing has to be a no-op per selector.
        SensitiveApiMonitor.install()
        SensitiveApiMonitor.install()
        SensitiveApiMonitor.install()
        SensitiveApiMonitor.drain()

        UIDevice.currentDevice.identifierForVendor

        val seen = SensitiveApiMonitor.drain()
        val read = seen.single { it.eventName == "vendor_id_read" }
        // One call, recorded once — not once per install.
        assertEquals(1L, read.count)
    }
}
