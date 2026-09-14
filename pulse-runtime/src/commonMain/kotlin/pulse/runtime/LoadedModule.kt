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

/**
 * The images that ship inside the application itself, rather than with the operating system.
 *
 * This is the set an audit actually cares about: the app's own embedded frameworks and whatever
 * third-party SDKs came with them. A process has several hundred system libraries loaded and they
 * are identical on every install, so reporting them is noise the server pays to store.
 *
 * "Inside the application" is decided by path against the main executable's own directory rather
 * than by pattern-matching system prefixes. Those prefixes differ between a device, a simulator and
 * a desktop host, and a list of them is a list that goes stale; the loader's own answer for where
 * this binary lives does not.
 */
fun bundledModules(all: List<LoadedModule> = loadedModules()): List<LoadedModule> {
    // Index 0 is the main executable in the loader's table on every platform this runs on.
    val mainPath = all.firstOrNull()?.path ?: return emptyList()
    val root = mainPath.substringBeforeLast('/', "")
    if (root.isEmpty()) return emptyList()
    return all.filter { it.path.startsWith("$root/") || it.path == mainPath }
}
