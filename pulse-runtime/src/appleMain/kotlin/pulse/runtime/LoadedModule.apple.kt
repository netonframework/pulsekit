@file:OptIn(ExperimentalForeignApi::class)

package pulse.runtime

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import pulse.runtime.dyld._dyld_get_image_header
import pulse.runtime.dyld._dyld_get_image_name
import pulse.runtime.dyld._dyld_image_count

/**
 * dyld's own image table: the list the dynamic loader maintains, so it covers everything mapped
 * into the process, including images loaded after start.
 */
actual fun loadedModules(): List<LoadedModule> {
    val count = _dyld_image_count().toInt()
    val out = ArrayList<LoadedModule>(count)
    for (i in 0 until count) {
        val path = _dyld_get_image_name(i.toUInt())?.toKString() ?: continue
        val header = _dyld_get_image_header(i.toUInt())
        out.add(LoadedModule(path, header?.rawValue?.toLong() ?: 0L))
    }
    return out
}
