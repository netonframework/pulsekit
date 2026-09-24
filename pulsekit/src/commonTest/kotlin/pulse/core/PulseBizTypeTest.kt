package pulse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PulseBizTypeTest {
    @Test
    fun sevenPermanentBusinessTypesAreUniqueBytes() {
        val allocated = listOf(
            PulseBizType.CLIENT_CONNECT,
            PulseBizType.CLIENT_HEARTBEAT,
            PulseBizType.CLIENT_CONFIG_PULL,
            PulseBizType.CLIENT_CRASH_BATCH_UPLOAD,
            PulseBizType.CLIENT_EVENT_BATCH_UPLOAD,
            PulseBizType.CLIENT_APP_UPDATE_CHECK,
            PulseBizType.BIDIRECTIONAL_CONTROL_RPC,
        )

        assertEquals((1..7).toList(), allocated)
        assertEquals(allocated.size, allocated.toSet().size)
        assertTrue(allocated.all { it in 1..255 })
        assertEquals(0, PulseBizType.RESERVED)
    }
}
