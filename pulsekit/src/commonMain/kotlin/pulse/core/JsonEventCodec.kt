package pulse.core

import kotlinx.serialization.json.Json

/**
 * Default codec: a JSON [EventBatch] object. Cross-language readable (web/TS use the same shape).
 *
 * `ignoreUnknownKeys` is deliberate on both sides — it is what lets either end add a field without
 * a lockstep release.
 */
object JsonEventCodec : EventCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun encode(batch: EventBatch): ByteArray =
        json.encodeToString(EventBatch.serializer(), batch).encodeToByteArray()

    override fun decode(bytes: ByteArray): EventBatch =
        json.decodeFromString(EventBatch.serializer(), bytes.decodeToString())
}
