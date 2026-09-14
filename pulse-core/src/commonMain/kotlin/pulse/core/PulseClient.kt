package pulse.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonElement

/**
 * The core pipeline shared by every capability: build events with the current session/identity,
 * buffer them (bounded), and flush batches to the [EventSink] on size/interval triggers. A crash
 * handler can force a synchronous flush before the process dies.
 *
 * Single-dispatcher ownership: emit/flush/session state run on [scope]'s dispatcher, so there is no
 * locking. This mirrors the transport's reactor-bound discipline.
 */
class PulseClient(
    val config: PulseConfig,
    val identity: Identity,
    private val scope: CoroutineScope,
    private var sink: EventSink = NoopEventSink,
    private val codec: EventCodec = JsonEventCodec,
) {
    private val buffer = EventBuffer(config.bufferCapacity)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var session: Session = newSession()
    private var closed = false

    private val updateJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val currentSession: Session get() = session

    fun start() {
        if (loop != null) return
        loop = scope.launch { flushLoop() }
    }

    /** Attach the real transport sink (e.g. msgtrans) after construction. */
    fun attachSink(newSink: EventSink) { sink = newSink }

    fun newSession(): Session = Session(newId(), nowMillis()).also { session = it }

    /** Enqueue an event; triggers a flush when the batch threshold is reached. */
    fun emit(kind: EventKind, name: String, attributes: Map<String, JsonElement> = emptyMap(), source: EventSource = EventSource()) {
        if (closed) return
        buffer.add(Event(newId(), nowMillis(), session.id, kind, name, source, attributes))
        if (buffer.size >= config.batchMaxEvents) wake.trySend(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun flushLoop() {
        while (scope.isActive && !closed) {
            select<Unit> {
                wake.onReceive { }
                onTimeout(config.flushIntervalMs) { }
            }
            flushOnce()
        }
    }

    /** Drain one batch and deliver it; on failure the batch is requeued for the next attempt. */
    suspend fun flushOnce() {
        val batch = buffer.drain(config.batchMaxEvents)
        if (batch.isEmpty()) return
        try {
            // Identity is read at flush time, so a batch buffered before identify() still carries
            // the user id once it is known.
            sink.send(codec.encode(EventBatch(identity.toWire(config), batch)))
        } catch (t: Throwable) {
            buffer.requeueFront(batch) // keep for retry; never lose on a transient failure
        }
    }

    /**
     * Ask the server whether this build should update. Returns [UpdateInfo] with
     * [UpdateAction.None] whenever there is no answer to be had — no platform configured, no
     * transport, a timeout, or a reply we cannot parse.
     *
     * Failing open is deliberate and load-bearing: this runs during app start, and a telemetry SDK
     * that can stop an app from starting is a worse problem than a missed update prompt.
     */
    suspend fun checkForUpdate(): UpdateInfo {
        val platform = config.platform ?: return UpdateInfo()
        val request = UpdateCheckRequest(
            platform = platform,
            appKey = config.projectId,
            packageName = config.packageName,
            buildNumber = config.buildNumber,
        )
        return try {
            val encoded = updateJson.encodeToString(UpdateCheckRequest.serializer(), request)
            val reply = sink.request(PulseBizType.APP_UPDATE_CHECK, encoded.encodeToByteArray()) ?: return UpdateInfo()
            if (reply.isEmpty()) return UpdateInfo()
            updateJson.decodeFromString(UpdateCheckWireResult.serializer(), reply.decodeToString()).toInfo()
        } catch (t: Throwable) {
            UpdateInfo()
        }
    }

    suspend fun close() {
        if (closed) return
        closed = true
        flushOnce()
        loop?.cancel()
        sink.close()
    }

}
