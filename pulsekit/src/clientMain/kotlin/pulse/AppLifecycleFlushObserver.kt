package pulse

/** Platform lifecycle bridge used to move the in-memory event buffer into the durable outbox. */
internal expect class AppLifecycleFlushObserver(onBackground: () -> Unit) {
    fun close()
}
