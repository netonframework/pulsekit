package pulse.core

/** Wall-clock epoch milliseconds. */
expect fun nowMillis(): Long

/** A fresh unique id (uuid-like) for events, sessions, installations. */
expect fun newId(): String
