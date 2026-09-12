package pulse

import kotlinx.coroutines.CoroutineScope
import pulse.analytics.Analytics
import pulse.apm.Apm
import pulse.core.Identity
import pulse.core.PulseClient
import pulse.core.PulseConfig
import pulse.core.newId
import pulse.transport.MsgTransEventSink

/**
 * Batteries-included SDK entry. Wires the core pipeline, the analytics/apm surfaces and the
 * msgtrans ingest transport. Apps that want link-time stripping can instead build a [PulseClient]
 * with only the capabilities they need; this umbrella is the convenience path.
 *
 * Outward-neutral by design (Pulse / analytics / apm), matching the naming convention in the
 * architecture doc — nothing here reads as security/audit tooling.
 */
class Pulse private constructor(
    val client: PulseClient,
    val analytics: Analytics,
    val apm: Apm,
) {
    fun identify(userId: String) = analytics.identify(userId)
    fun track(name: String, attributes: Map<String, Any?> = emptyMap()) = analytics.track(name, attributes)
    suspend fun stop() = client.close()

    companion object {
        /**
         * Start the SDK: open the ingest connection, begin the flush pipeline, emit app_launch.
         * TODO(persist): installationId/deviceId are fresh per start here; a real device id must be
         * persisted across launches (platform-apple / platform-android).
         */
        suspend fun start(scope: CoroutineScope, config: PulseConfig): Pulse {
            val identity = Identity(config.projectId, installationId = newId(), deviceId = newId())
            val client = PulseClient(config, identity, scope)
            client.attachSink(MsgTransEventSink.connect(scope, config))
            client.start()
            val pulse = Pulse(client, Analytics(client), Apm(client))
            if (config.analytics) { pulse.analytics.sessionStart(); pulse.analytics.appLaunch() }
            return pulse
        }
    }
}
