package pulse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PulseBizTypeTest {
    @Test
    fun sevenPermanentBusinessTypesAreUniqueBytes() {
        val allocated = listOf(
            PulseBizType.EVENT_BATCH_UPLOAD,
            PulseBizType.APP_UPDATE_CHECK,
            PulseBizType.SESSION_REGISTER,
            PulseBizType.CRASH_BATCH_UPLOAD,
            PulseBizType.CONFIG_PULL,
            PulseBizType.HEARTBEAT,
            PulseBizType.CONTROL_RPC,
        )

        assertEquals((1..7).toList(), allocated)
        assertEquals(allocated.size, allocated.toSet().size)
        assertTrue(allocated.all { it in 1..255 })
        assertEquals(0, PulseBizType.RESERVED)
    }
}
