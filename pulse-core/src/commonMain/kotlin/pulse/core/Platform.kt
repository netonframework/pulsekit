package pulse.core

/** Wall-clock epoch milliseconds. */
expect fun nowMillis(): Long

/** A fresh unique id (uuid-like) for events, sessions, installations. */
expect fun newId(): String

/** Per-app durable directory for the event outbox and next-launch crash record. */
expect fun defaultPulseStorageDir(projectId: String): String

/**
 * A small key/value store that survives process restarts, used for the device and installation
 * ids. NSUserDefaults on Apple; a file under the user's home on Linux (server/dev hosts).
 *
 * Deliberately tiny: the SDK persists two ids and nothing else. It is not a cache and must never
 * hold event payloads or anything sensitive.
 */
expect object PersistentStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * The device id, generated once and reused for every later launch.
 *
 * This is what makes a device countable across sessions: without persistence every launch looks
 * like a new device, which silently inflates DAU and makes crash-per-device rates meaningless.
 * Scoped to the app's own storage, so it is not a cross-app identifier.
 */
fun persistentDeviceId(): String = persistentId("pulse.device_id")

/**
 * The installation id, generated once per install. Separate from the device id so a reinstall can
 * be distinguished from a new device.
 */
fun persistentInstallationId(): String = persistentId("pulse.installation_id")

private fun persistentId(key: String): String =
    PersistentStore.get(key) ?: newId().also { PersistentStore.put(key, it) }
