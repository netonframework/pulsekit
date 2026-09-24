package pulse.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObjectiveCMethodInventoryTest {

    @Test
    fun bundledObjectiveCMethodsAreInventoriedWithoutInvokingThem() {
        val snapshot = ObjectiveCMethodInventory.capture()

        assertTrue(snapshot.discoveredClassCount > 0, "no bundled Objective-C classes were found")
        assertTrue(snapshot.reportedClassCount > 0, "no Objective-C class inventory was produced")
        assertEquals(snapshot.classes.size, snapshot.reportedClassCount)
        assertTrue(snapshot.classes.all { it.image.isNotBlank() && it.className.isNotBlank() })
        assertTrue(snapshot.classes.all { it.methodsDigest.length == 16 })
        assertTrue(
            snapshot.classes.flatMap { it.instanceMethods + it.classMethods }
                .all { it.startsWith('-') || it.startsWith('+') },
            "method kind was not retained",
        )
    }
}
