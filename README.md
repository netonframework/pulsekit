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
| `pulse-runtime` | Opt-in Apple runtime inventory and sensitive API observation, with caller/module attribution. |
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
when the buffer is full). A POSIX crash handler cannot safely run Kotlin allocation, coroutine, or
network code, so it writes a preallocated crash record and reports that record through the normal
pipeline on the next launch.

## Build and test

```bash
./gradlew :pulse-core:macosArm64Test        # model / buffer / codec / pipeline
./gradlew :pulse-sdk:macosArm64Test          # end-to-end: Pulse.start -> msgtrans -> server decode
./gradlew :pulse-sdk:linkDebugTestLinuxX64   # cross-compile for Linux
```

## Status

Usable iOS/Kotlin-Native baseline (2026-09-14): analytics, APM, runtime observation, persisted
device/installation identity, next-launch crash recovery, startup update checks, and the msgtrans
ingest transport are implemented. The CocoaPods dynamic framework exposes a stable Objective-C
entry point to Swift hosts, including hosts built with a different Kotlin version.

The SDK and transport suites pass on macOS, the iOS simulator, and real Linux epoll/io_uring
(including a depth-8 io_uring run). The end-to-end path has also been exercised against the Pulse
server, Redis queue, and PostgreSQL storage.

Current boundaries:

- `userId` is optional host-provided identity, like the account binding API of an analytics SDK;
  PulseKit does not know or depend on an app's authentication model.
- Pending analytics/APM events are bounded in memory but are not yet persisted across process
  termination. Crash records are persisted separately and reported on the next launch.
- `batchMaxBytes` is part of the configuration but is not yet enforced by the batching pipeline;
  event count and total buffered event count are bounded today.
- Apple runtime observation covers the currently registered Objective-C API signatures. Android
  transport, lifecycle, crash, ANR, and runtime collectors have not been implemented yet.
- `pulse-push` and web/TypeScript SDK interoperability have not been implemented.
