package pulse.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Minimal `Any?` -> `JsonElement` mapping for event attributes (String / Number / Boolean kept as
 * primitives; anything else via toString()). Shared by every capability so the attribute shape is
 * uniform on the wire.
 */
fun Map<String, Any?>.toEventAttributes(): Map<String, JsonElement> =
    mapValues { (_, v) ->
        when (v) {
            null -> JsonPrimitive(null as String?)
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }
