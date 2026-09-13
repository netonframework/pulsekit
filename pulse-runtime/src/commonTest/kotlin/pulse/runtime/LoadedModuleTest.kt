package pulse.runtime

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The inventory has to read the real loader state, so this asserts against facts that hold for any
 * running process rather than a fixture: something is loaded, every entry has an absolute path,
 * and reading twice in a row agrees (the loader table is not being misparsed into noise).
 */
class LoadedModuleTest {

    @Test
    fun reportsTheImagesLoadedIntoThisProcess() {
        val modules = loadedModules()
        assertTrue(modules.isNotEmpty(), "no loaded images reported; the platform binding is not working")
        for (m in modules) {
            assertTrue(m.path.startsWith("/"), "expected an absolute image path, got '${m.path}'")
            assertTrue(m.name.isNotEmpty(), "empty image name from path '${m.path}'")
        }
    }

    @Test
    fun repeatedReadsAgree() {
        // Nothing is dlopen'd between these two calls, so the sets must match. A parser that is
        // picking up junk lines or racing the loader table would show up here.
        val first = loadedModules().map { it.path }.toSet()
        val second = loadedModules().map { it.path }.toSet()
        assertTrue(first == second, "inventory is unstable: ${(first - second) + (second - first)}")
    }

    @Test
    fun theProcessImageItselfIsListed() {
        // The main executable is always in the loader's table on both platforms.
        val paths = loadedModules().map { it.path }
        assertTrue(
            paths.any { it.endsWith(".kexe") || it.contains("test") },
            "the test binary is not in the inventory: ${paths.take(5)}",
        )
    }
}
