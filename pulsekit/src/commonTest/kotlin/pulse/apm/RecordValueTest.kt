package pulse.apm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RecordValueTest {
    @Test
    fun multiLineValuesRoundTripThroughOneLine() {
        for (value in listOf("", "plain", "a\nb", "tab\tand\\backslash", "\\n literal", "trailing\\", "x\r\ny")) {
            val escaped = escapeRecordValue(value)
            assertFalse('\n' in escaped, "escaped value must stay on one line: $escaped")
            assertEquals(value.replace("\r", ""), unescapeRecordValue(escaped))
        }
    }
}
