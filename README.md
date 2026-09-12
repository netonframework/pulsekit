# PulseKit

Cross-platform application analytics, performance/error monitoring, and runtime observation for the
Kotlin/Native stack. Product target: 友盟 (U-App analytics + U-APM) class, plus Runtime attribution.
PulseKit is a data/reliability SDK — it does not integrate any IM/PrivChat logic.

The client SDK produces one unified `Event` model and ships batches to the Pulse ingest server over
the [msgtrans](../msgtrans-kotlin) long connection (which runs on the [neton-io](../neton-io)
reactor). The server hands received batches to the Pulse modules → a Redis queue → consumers that
persist into each module's store.

## Modules

| module | role |
|---|---|
| `pulse-core` | the unified `Event` / `Session` / `Identity` model, `PulseConfig`, bounded `EventBuffer`, `EventCodec` (JSON), `EventSink` boundary, and the `PulseClient` batch/flush pipeline. No transport dependency, so capabilities stay link-time independent. |
| `pulse-analytics` | U-App class: `track` business events, `identify`, automatic lifecycle (session/launch). |
| `pulse-apm` | U-APM / Bugly class: errors, crashes, ANR, startup and network performance reporting surface. |
| `pulse-transport` | the `EventSink` backed by the msgtrans long connection (batch → ingest request). |
| `pulse-sdk` | batteries-included entry (`Pulse.start` / `track` / `identify`) wiring the above. |

Naming is deliberately neutral (Pulse / analytics / apm / runtime / context); the client never
labels events as first/system/third-party — the server attributes them.

## Usage

```kotlin
runReactor {
    val pulse = Pulse.start(this, PulseConfig(
        projectId = "vip-mall",
        host = "collect.example.com", port = 9600,
        analytics = true, apm = true,
    ))
    pulse.identify("100086")
    pulse.track("purchase", mapOf("amount" to 199))
    // apm surface:
    pulse.apm.recordError("NullPointer", message = "…", stack = "…")
    pulse.apm.recordPerformance("startup", durationMs = 820)
    pulse.stop()
}
```

Apps that want link-time stripping can build a `PulseClient` with only the capabilities they need
instead of the umbrella `Pulse` entry.

## Transport and delivery

A batch is sent as one msgtrans **request** with biz type `1` (ingest); the server's reply confirms
receipt into the pipeline. `request` gives delivery confirmation and backpressure; a failed send
requeues the batch in the bounded ring buffer (stability over completeness — oldest events drop
when the buffer is full, and the drop count is reported). Crash batches must be flushed
synchronously before the process dies (platform glue, a later step).

## Build and test

```bash
./gradlew :pulse-core:macosArm64Test        # model / buffer / codec / pipeline
./gradlew :pulse-sdk:macosArm64Test          # end-to-end: Pulse.start -> msgtrans -> server decode
./gradlew :pulse-sdk:linkDebugTestLinuxX64   # cross-compile for Linux
```

## Status

Skeleton (2026-09-13): the event model, the batch/flush pipeline, the analytics and apm surfaces,
the msgtrans ingest sink, and the `Pulse` entry compile and pass tests on macOS; the end-to-end path
(SDK → msgtrans → server decodes real events) is green. Native targets match msgtrans-kotlin
(macos/linux); **iOS/Android targets and platform glue** (dyld/signal/ANR capture, persisted device
id, crash-time flush) are the next step and need neton-io/msgtrans Apple client targets. The server
side (msgtrans ingest server → Redis queue → consumers → module DB) is a separate module, not in
this repo yet.

Not built yet: `pulse-runtime`, `pulse-push`, cross-language interop with the web/TS SDK, and
persistence/offline queue.
