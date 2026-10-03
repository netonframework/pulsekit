package pulse.host

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * What a JVM host platform supplies to the SDK. On iOS the SDK reads these facts itself through
 * UIKit, Foundation and the kernel; on Android only the framework knows them, so the platform layer
 * (the pulsekit-android module) implements this interface and registers it with [PulseHost] before
 * the SDK starts.
 *
 * Every member is called from SDK threads except where noted, and must not throw.
 */
interface HostEnvironment {
    /** The platform name sent on registration and on every batch, e.g. "android". */
    val platform: String

    /** Device model reported on registration (Build.MODEL); at most 64 characters are kept. */
    val deviceType: String

    /** App-private directory the SDK keeps its outbox and crash records under (Context.getFilesDir()). */
    val storageRoot: String

    /** When the OS started this process, epoch ms, or 0 if unknown. */
    fun processStartMillis(): Long

    /** The process's resident memory in bytes, or 0 if unknown. */
    fun residentMemoryBytes(): Long

    /** Whether there is a main (UI) thread [postToMainThread] can reach. */
    val hasMainThread: Boolean

    /** Run [task] on the main thread, asynchronously; false when it could not be posted. Any thread. */
    fun postToMainThread(task: Runnable): Boolean

    /** The install-scoped key/value store for the SDK's two ids (SharedPreferences on Android). */
    fun getValue(key: String): String?

    fun putValue(key: String, value: String)

    /** Open (creating or migrating) the SQLite database at [path] with the platform's driver. */
    fun openSqliteDriver(schema: SqlSchema<QueryResult.Value<Unit>>, path: String): SqlDriver

    /**
     * What the installed package declares — permissions, signing certificates, install source —
     * as a JSON object; see [pulse.runtime.HostFacts]. Read once per start.
     */
    fun factsJson(): String
}

/**
 * The registration point for the [HostEnvironment] and the lifecycle transitions the platform
 * observes (the counterparts of UIApplication's didBecomeActive / didEnterBackground).
 */
@OptIn(ExperimentalAtomicApi::class)
object PulseHost {
    private val current = AtomicReference<HostEnvironment?>(null)

    /** Set before starting the SDK; read on every SDK thread. */
    @JvmStatic
    var environment: HostEnvironment?
        get() = current.load()
        set(value) = current.store(value)

    /** The app came to the foreground and is usable. Main thread. */
    @JvmStatic
    fun onActive() = HostLifecycle.dispatchActive()

    /** The app went to the background. Main thread. */
    @JvmStatic
    fun onBackground() = HostLifecycle.dispatchBackground()
}

/**
 * Foreground/background listeners, and the current state.
 *
 * The state is remembered because the platform's first "active" usually comes before the SDK's
 * observer exists: the host starts the SDK in Application.onCreate and its first activity resumes
 * while the SDK thread is still setting up. A listener added while the app is active is told so at
 * once, with the time it actually became active (which is what the launch time is measured to).
 * Listeners are added on the SDK thread and called on the main thread; a lock keeps "add and catch
 * up" and "change state and notify" from interleaving, so each transition is delivered exactly once.
 */
internal object HostLifecycle {
    class Listener(val onActive: (Long) -> Unit, val onBackground: (Long) -> Unit)

    private val lock = Any()
    private val listeners = ArrayList<Listener>()
    /** Epoch ms the app last became active; 0 while it is in the background (or never was active). */
    private var activeSince = 0L

    fun add(listener: Listener) = synchronized(lock) {
        listeners.add(listener)
        if (activeSince > 0) listener.onActive(activeSince)
    }

    fun remove(listener: Listener) = synchronized(lock) { listeners.remove(listener); Unit }

    fun dispatchActive() = synchronized(lock) {
        val now = System.currentTimeMillis()
        activeSince = now
        for (l in listeners.toList()) l.onActive(now)
    }

    fun dispatchBackground() = synchronized(lock) {
        val now = System.currentTimeMillis()
        activeSince = 0L
        for (l in listeners.toList()) l.onBackground(now)
    }
}
