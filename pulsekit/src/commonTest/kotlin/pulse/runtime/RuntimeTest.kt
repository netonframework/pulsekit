package pulse.runtime

import kotlinx.coroutines.test.runTest
import pulse.core.Event
import pulse.core.EventKind
import pulse.core.EventSink
import pulse.core.Identity
import pulse.core.JsonEventCodec
import pulse.core.PulseClient
import pulse.core.PulseConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Captures what the client would have put on the wire. */
private class CapturingSink : EventSink {
    val events = mutableListOf<Event>()
    override suspend fun send(batch: ByteArray) { events += JsonEventCodec.decode(batch).events }
    override suspend fun close() {}
}

class RuntimeTest {

    private fun clientWith(sink: EventSink, scope: kotlinx.coroutines.CoroutineScope) = PulseClient(
        PulseConfig(projectId = "t", host = "127.0.0.1", runtime = true, batchMaxEvents = 1000),
        Identity("t", "i", "d"), scope, sink,
    )

    @Test
    fun baselineReportsOneSummaryAndBundledArtifactEvidence() = runTest {
        val sink = CapturingSink()
        val client = clientWith(sink, this)
        val runtime = Runtime(client)

        val modules = runtime.captureBaseline()
        client.flushOnce()

        val summaries = sink.events.filter { it.name == "module_inventory" }
        assertEquals(1, summaries.size, "the baseline must have exactly one inventory summary")
        val e = summaries.single()
        assertEquals(EventKind.Runtime, e.kind)
        assertEquals("module_inventory", e.name)
        assertEquals(modules.size.toString(), e.attributes["module_count"]?.toString()?.trim('"'))
        assertTrue(modules.size > 1, "expected a real process inventory, got ${modules.size}")
        val bundled = bundledModules(modules).take(100)
        assertEquals(bundled.size, sink.events.count { it.name == "module_artifact" })
        assertTrue(sink.events.filter { it.name == "module_artifact" }.all { it.source.module != null })
    }

    @Test
    fun scanReportsNothingWhenNothingChanged() = runTest {
        val sink = CapturingSink()
        val client = clientWith(sink, this)
        val runtime = Runtime(client)
        runtime.captureBaseline()
        client.flushOnce()
        sink.events.clear()

        // No image is loaded between the baseline and the scan, so there is nothing to report.
        assertEquals(emptyList(), runtime.scan())
        client.flushOnce()
        assertEquals(0, sink.events.size, "scan emitted events for images already in the baseline")
    }

    @Test
    fun anImageOutsideTheBaselineIsReportedOnceAndThenFoldedIn() = runTest {
        val sink = CapturingSink()
        val client = clientWith(sink, this)
        // No baseline captured, so *every* loaded image counts as new: this exercises the diff and
        // the fold-in without needing to dlopen something mid-test.
        val runtime = Runtime(client)

        val first = runtime.scan()
        client.flushOnce()
        assertTrue(first.isNotEmpty(), "expected the loaded images to be reported as new")
        assertEquals(first.size, sink.events.size)
        assertTrue(sink.events.all { it.kind == EventKind.Runtime && it.name == "module_loaded" })
        // The source module is the image's own name, and stays purely descriptive.
        assertTrue(sink.events.all { it.source.module != null })

        sink.events.clear()
        assertEquals(emptyList(), runtime.scan(), "an image must only be reported once")
        client.flushOnce()
        assertEquals(0, sink.events.size)
    }

    @Test
    fun hostReportedBehaviorCarriesItsModuleAndAttributes() = runTest {
        val sink = CapturingSink()
        val client = clientWith(sink, this)
        Runtime(client).recordBehavior("clipboard_read", module = "SomeSDK", attributes = mapOf("count" to 3))
        client.flushOnce()

        val e = sink.events.single()
        assertEquals(EventKind.Runtime, e.kind)
        assertEquals("clipboard_read", e.name)
        assertEquals("SomeSDK", e.source.module)
        assertEquals("3", e.attributes["count"]?.toString()?.trim('"'))
    }

    @Test
    fun theInventoryDigestIsStableAcrossInstances() = runTest {
        // Two Runtime instances over the same process must produce the same digest, otherwise the
        // server cannot use it to tell one installation's image set from another's.
        val a = CapturingSink()
        val ca = clientWith(a, this)
        Runtime(ca).captureBaseline()
        ca.flushOnce()

        val b = CapturingSink()
        val cb = clientWith(b, this)
        Runtime(cb).captureBaseline()
        cb.flushOnce()

        val da = a.events.single { it.name == "module_inventory" }.attributes["inventory_digest"].toString()
        val db = b.events.single { it.name == "module_inventory" }.attributes["inventory_digest"].toString()
        assertEquals(da, db, "inventory digest is not stable across instances")
        assertTrue(da.trim('"').length == 16, "expected a 16-hex-char digest, got $da")
    }

    @Test
    fun scanIgnoresSystemImagesLoadedAfterLaunch() = runTest {
        val sink = CapturingSink()
        val client = clientWith(sink, this)
        val runtime = Runtime(client)

        // Baseline covers everything currently loaded, so a scan right after it has nothing new.
        runtime.captureBaseline()
        client.flushOnce()
        sink.events.clear()

        // Whatever the OS loads lazily from here on is outside the app bundle. A scan must stay
        // silent about it: an unfiltered scan produced 418 events in one real iOS session, which
        // buries the single bundled image that would have been worth seeing.
        val fresh = runtime.scan()
        client.flushOnce()
        assertTrue(
            fresh.all { it in bundledModules() },
            "scan reported an image outside the app bundle: ${fresh.map { it.name }}",
        )
        assertTrue(
            sink.events.all { it.name != "module_loaded" || it.source.module != null },
            "module_loaded events must carry the image they refer to",
        )
    }
}
