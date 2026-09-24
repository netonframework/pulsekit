package pulse.core

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PulseResponseTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun successEnvelopeHasStableWireShape() {
        val encoded = json.encodeToString(
            PulseResponse.serializer(Boolean.serializer()),
            PulseResponse(code = 0, data = true),
        )
        assertEquals("{\"code\":0,\"msg\":null,\"data\":true}", encoded)
    }

    @Test
    fun applicationFailureStillDecodesAsAResponse() {
        val response = json.decodeFromString(
            PulseResponse.serializer(Boolean.serializer()),
            "{\"code\":429,\"msg\":\"ingest overloaded\",\"data\":false}",
        )
        assertEquals(429, response.code)
        assertEquals("ingest overloaded", response.msg)
        assertEquals(false, response.data)
    }

    @Test
    fun nullableDataIsPartOfTheContract() {
        val response = json.decodeFromString(
            PulseResponse.serializer(String.serializer()),
            "{\"code\":0,\"msg\":null,\"data\":null}",
        )
        assertTrue(response.isSuccess)
        assertNull(response.data)
    }
}
