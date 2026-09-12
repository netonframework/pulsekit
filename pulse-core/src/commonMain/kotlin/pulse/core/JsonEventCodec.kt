package pulse.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Default codec: a JSON array of events. Cross-language readable (web/TS use the same shape). */
object JsonEventCodec : EventCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Event.serializer())
    override fun encode(events: List<Event>): ByteArray =
        json.encodeToString(serializer, events).encodeToByteArray()
    override fun decode(bytes: ByteArray): List<Event> =
        json.decodeFromString(serializer, bytes.decodeToString())
}
