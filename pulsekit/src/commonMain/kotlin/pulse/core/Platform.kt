package pulse.core

/** Wall-clock epoch milliseconds. */
expect fun nowMillis(): Long

/**
 * A fresh unique id for events, sessions, installations: a random (version 4) UUID as 32 hex chars, from the platform's
 * secure random source (`kotlin.uuid.Uuid`, stable in Kotlin 2.4; the opt-in is for the 2.2 API level this SDK keeps for
 * Kotlin 2.1 hosts). The 32-hex format is unchanged; it used to come from `kotlin.random.Random`, which is not a secure
 * source.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun newId(): String = kotlin.uuid.Uuid.random().toHexString()

/** Per-app durable directory for the event outbox and next-launch crash record. */
expect fun defaultPulseStorageDir(projectId: String): String

/**
 * A small key/value store that survives process restarts, used for the device and installation
 * ids. NSUserDefaults on Apple, SharedPreferences on Android (through the JVM host), a file
 * under the user's home on Linux (server/dev hosts).
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
