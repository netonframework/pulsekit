package pulse.runtime

/**
 * One native image loaded into the process, as observed by the platform loader.
 *
 * Deliberately descriptive and nothing more: a path and where it was mapped. The client does not
 * decide whether an image is first-party, system or third-party, and does not carry a verdict —
 * that attribution is a server concern (see the architecture doc, "neutral naming"). Keeping the
 * judgement server-side also means it can be revised without shipping a new SDK.
 */
data class LoadedModule(
    /** Filesystem path the loader reports for the image. */
    val path: String,
    /** Load address, when the platform exposes one; 0 when it does not. */
    val loadAddress: Long = 0L,
) {
    /** Last path component, the part worth putting in an event name. */
    val name: String get() = path.substringAfterLast('/')
}

/**
 * Snapshot of every native image currently loaded. Implemented per platform:
 * dyld on Apple, /proc/self/maps on Linux.
 *
 * Order is the loader's, not sorted, so a diff against an earlier snapshot reflects load order.
 */
expect fun loadedModules(): List<LoadedModule>
