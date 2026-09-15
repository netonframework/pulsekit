package pulse

/** PulseKit currently ships the automatic lifecycle bridge only for iOS. */
internal actual class AppLifecycleFlushObserver actual constructor(onBackground: () -> Unit) {
    actual fun close() = Unit
}
