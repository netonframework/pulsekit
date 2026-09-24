@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package pulse.runtime

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.free
import pulse.dyld.pulse_class_image
import pulse.dyld.pulse_class_name
import pulse.dyld.pulse_copy_class_list
import pulse.dyld.pulse_copy_class_methods
import pulse.dyld.pulse_copy_instance_methods
import pulse.dyld.pulse_method_encoding
import pulse.dyld.pulse_method_name

/**
 * Runtime inventory of Objective-C classes and methods implemented by images bundled in the app.
 *
 * This complements call interception: a method need not execute during one test session to be
 * visible here. It is descriptive only. Classification as first-party or third-party belongs to
 * the server, where a rule can be changed without shipping a new client.
 *
 * The Objective-C runtime exposes exact method type encodings, so this can safely inspect methods
 * of every ABI shape without invoking or replacing any implementation. Pure Swift, C and C++ code
 * that is not exported to Objective-C remains outside this surface and is handled by IPA/Mach-O
 * static analysis.
 */
internal object ObjectiveCMethodInventory {

    data class ClassInfo(
        val image: String,
        val imageUuid: String?,
        val className: String,
        val instanceMethods: List<String>,
        val classMethods: List<String>,
        val methodCount: Int,
        val methodsDigest: String,
        val truncated: Boolean,
    )

    data class Snapshot(
        val classes: List<ClassInfo>,
        val discoveredClassCount: Int,
        val reportedClassCount: Int,
        val methodCount: Int,
        val truncatedClassCount: Int,
    )

    fun capture(): Snapshot = memScoped {
        val bundled = bundledModules()
        if (bundled.isEmpty()) return Snapshot(emptyList(), 0, 0, 0, 0)
        val byPath = bundled.associateBy { it.path }

        val count = alloc<UIntVar>()
        val classes = pulse_copy_class_list(count.ptr)
            ?: return Snapshot(emptyList(), 0, 0, 0, 0)
        try {
            val out = ArrayList<ClassInfo>()
            var discovered = 0
            var methods = 0
            var truncatedClasses = 0
            for (i in 0 until count.value.toInt()) {
                val cls = classes[i] ?: continue
                val imagePath = pulse_class_image(cls)?.toKString() ?: continue
                val module = byPath[imagePath] ?: continue
                discovered++
                if (out.size >= MAX_REPORTED_CLASSES) {
                    truncatedClasses++
                    continue
                }

                val className = pulse_class_name(cls)?.toKString()?.take(MAX_NAME_LENGTH) ?: continue
                val instance = methodsOf(cls, '-', classMethods = false)
                val classMethods = methodsOf(cls, '+', classMethods = true)
                val all = instance.all + classMethods.all
                methods += all.size
                out += ClassInfo(
                    image = module.name,
                    imageUuid = module.imageUuid,
                    className = className,
                    instanceMethods = instance.reported,
                    classMethods = classMethods.reported,
                    methodCount = all.size,
                    methodsDigest = digest(all.sorted()),
                    truncated = instance.truncated || classMethods.truncated,
                )
            }
            Snapshot(out, discovered, out.size, methods, truncatedClasses)
        } finally {
            free(classes)
        }
    }

    private data class MethodList(
        val all: List<String>,
        val reported: List<String>,
        val truncated: Boolean,
    ) {
        companion object { val EMPTY = MethodList(emptyList(), emptyList(), false) }
    }

    private fun methodsOf(cls: kotlinx.cinterop.COpaquePointer, kind: Char, classMethods: Boolean): MethodList = memScoped {
        val count = alloc<UIntVar>()
        val methods = if (classMethods) pulse_copy_class_methods(cls, count.ptr)
        else pulse_copy_instance_methods(cls, count.ptr)
        methods ?: return MethodList.EMPTY
        try {
            val all = ArrayList<String>(count.value.toInt())
            for (i in 0 until count.value.toInt()) {
                val method = methods[i] ?: continue
                val selector = pulse_method_name(method)?.toKString() ?: continue
                val encoding = pulse_method_encoding(method)?.toKString().orEmpty()
                all += "$kind${selector.take(MAX_NAME_LENGTH)}|${encoding.take(MAX_ENCODING_LENGTH)}"
            }
            MethodList(
                all = all,
                reported = all.take(MAX_REPORTED_METHODS_PER_KIND),
                truncated = all.size > MAX_REPORTED_METHODS_PER_KIND,
            )
        } finally {
            free(methods)
        }
    }

    private fun digest(values: List<String>): String {
        var hash = 0xcbf29ce484222325UL
        for (value in values) {
            for (c in value) {
                hash = hash xor c.code.toULong()
                hash *= 0x100000001b3UL
            }
            hash = hash xor 0xffUL
            hash *= 0x100000001b3UL
        }
        return hash.toString(16).padStart(16, '0')
    }

    private const val MAX_REPORTED_CLASSES = 1_024
    // Keeps one class event comfortably below the default 64 KiB batch ceiling even when both
    // selectors and type encodings hit their string limits. The digest and method_count still
    // describe the complete set and `truncated=true` makes omitted names explicit.
    private const val MAX_REPORTED_METHODS_PER_KIND = 32
    private const val MAX_NAME_LENGTH = 191
    private const val MAX_ENCODING_LENGTH = 191
}

/** Emit one bounded event per runtime class plus a coverage summary. */
fun Runtime.reportObjectiveCMethodInventory(): Int {
    val snapshot = ObjectiveCMethodInventory.capture()
    for (entry in snapshot.classes) {
        recordAttributedBehavior(
            name = "objc_class_inventory",
            module = entry.image,
            imageUuid = entry.imageUuid,
            attributes = mapOf(
                "class_name" to entry.className,
                "instance_methods" to entry.instanceMethods.joinToString(";"),
                "class_methods" to entry.classMethods.joinToString(";"),
                "method_count" to entry.methodCount,
                "methods_digest" to entry.methodsDigest,
                "truncated" to entry.truncated,
            ),
        )
    }
    recordBehavior(
        name = "objc_method_inventory_summary",
        attributes = mapOf(
            "discovered_class_count" to snapshot.discoveredClassCount,
            "reported_class_count" to snapshot.reportedClassCount,
            "method_count" to snapshot.methodCount,
            "truncated_class_count" to snapshot.truncatedClassCount,
        ),
    )
    return snapshot.reportedClassCount
}
