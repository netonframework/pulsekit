package pulse.core

import platform.Foundation.NSUserDefaults

/**
 * NSUserDefaults, which is per-app, backed up with the app and cleared on uninstall — the right
 * lifetime for an install-scoped id. Keys are namespaced with `pulse.` so the SDK cannot collide
 * with the host app's own defaults.
 */
actual object PersistentStore {
    private val defaults get() = NSUserDefaults.standardUserDefaults

    actual fun get(key: String): String? = defaults.stringForKey(key)

    actual fun put(key: String, value: String) {
        defaults.setObject(value, key)
        // No synchronize(): it is a no-op on current systems and deprecated; defaults are flushed
        // by the system. A crash immediately after first launch may lose the id, which costs one
        // duplicated device — cheaper than a synchronous disk write on every start.
    }
}
