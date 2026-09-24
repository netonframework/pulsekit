package pulse.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The single event model for the whole platform (see the architecture doc, "unified event model").
 * Every capability — analytics, apm, runtime — produces the same Event tied to one Session, so a
 * user_id / session_id joins them server-side. There is deliberately no per-feature protocol.
 *
 * Naming is intentionally neutral (Pulse / runtime / context): the client never labels an event as
 * first/system/third-party; that classification is done server-side.
 */
@Serializable
data class Event(
    val id: String,
    val timestamp: Long,            // epoch ms
    val sessionId: String,
    val kind: EventKind,
    val name: String,               // e.g. "purchase", "app_launch", "crash"
    val source: EventSource = EventSource(),
    val attributes: Map<String, JsonElement> = emptyMap(),
)

/** First-class event categories, shared across platforms and capabilities. */
@Serializable
enum class EventKind { Analytics, Crash, Error, Log, Network, Performance, Runtime, Breadcrumb }

/** Where an event came from. `module`/`imageUuid` stay neutral; the server attributes them. */
@Serializable
data class EventSource(val module: String? = null, val imageUuid: String? = null)
