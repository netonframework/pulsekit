package pulse.runtime

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoadedModuleUuidTest {

    @Test
    fun bundledMachOImagesCarryTheirLoaderUuid() {
        val bundled = bundledModules()
        assertTrue(bundled.isNotEmpty(), "the iOS test process must contain its own Mach-O image")
        for (module in bundled) {
            val uuid = assertNotNull(module.imageUuid, "missing LC_UUID for ${module.name}")
            assertTrue(UUID.matches(uuid), "invalid LC_UUID for ${module.name}: $uuid")
        }
    }

    private companion object {
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
