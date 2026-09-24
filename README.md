# PulseKit

Application analytics, performance/error monitoring, and runtime observation for the
Kotlin/Native stack. The current product target is iOS: 友盟 (U-App analytics + U-APM) class,
plus evidence-oriented auditing of third-party plugins inserted during signing and packaging.
PulseKit is a data/reliability SDK — it does not integrate any IM/PrivChat logic.

The client SDK produces one unified `Event` model and ships batches to the Pulse ingest server over
the [msgtrans](../msgtrans-kotlin) long connection (which runs on the [neton-io](../neton-io)
reactor). The server hands received batches to the Pulse modules → a Redis queue → consumers that
persist into each module's store.

## Artifact

One artifact, `com.netonstream:pulsekit`. iOS hosts consume it as the `PulseKit` CocoaPods
framework (`pod 'PulseKit', :path => '<pulsekit>/pulsekit'` from source); Kotlin/Native consumers
depend on the Maven coordinate and must compile with the release's Kotlin version (the artifact is
a klib).

```kotlin
dependencies { implementation("com.netonstream:pulsekit:0.1.0") }
```

The capabilities are packages, not artifacts — whether one runs is `PulseConfig`'s decision
(`analytics` / `apm` / `runtime`), and the linker strips what a build does not reach:

| package | role |
|---|---|
| `pulse.core` | the unified `Event` / `Session` / `Identity` model, `PulseConfig`, bounded `EventBuffer`, durable outbox, `EventCodec` (JSON), `EventSink` boundary, and the `PulseClient` batch/flush pipeline with the server-driven collection policy. |
| `pulse.analytics` | U-App class: `track` business events, `identify`, automatic lifecycle (session/launch/foreground/background). |
| `pulse.apm` | U-APM / Bugly class: errors, crashes (signals and uncaught Objective-C exceptions), performance samples and gauges. |
| `pulse.runtime` | Opt-in Apple runtime inventory and sensitive API observation, with caller/module attribution. |
| `pulse.transport` | the `EventSink` backed by the msgtrans long connection (batch → ingest request, heartbeat). |
| `pulse` (`PulseSDK`, `Pulse`) | batteries-included entry wiring the above, plus what the SDK measures on its own: launch time, main-thread hangs, memory footprint, lifecycle. |

Naming is deliberately neutral (Pulse / analytics / apm / runtime / context); the client never
labels events as first/system/third-party — the server attributes them.

## Usage

Create an App in the Pulse console first, then embed the generated App ID in the host. The App ID
is a public routing identifier, not a secret. On iOS the host also reports its real bundle ID; the
server applies that App's `strict`, `allowlist`, or `any` package policy.

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

Swift hosts should use the Objective-C-stable facade:

```swift
PulseSDK.shared.startWithAppId(
    appId: "pulse_xxxxxxxxxxxxxxxxxxxxxxxx",
    host: "collect.example.com",
    port: 9600,
    runtime: true,
    packageName: Bundle.main.bundleIdentifier,
    buildNumber: 42,
    appVersion: "1.2.0"
)
```

The old `start(projectId:...)` selector remains available for already shipped integrations.

Apps that want link-time stripping can build a `PulseClient` with only the capabilities they need
instead of the umbrella `Pulse` entry.

## Transport and delivery

A batch is sent as one msgtrans **request** with biz type `5` (`CLIENT_EVENT_BATCH_UPLOAD`); only a
`{"code":0,"msg":null,"data":true}` response confirms receipt into the durable pipeline. `request`
gives delivery confirmation and backpressure; a failed send or non-zero application response
remains in a bounded SQLDelight/SQLite outbox and is retried in FIFO order after backoff or the next
process launch. Batches of at least 1 KiB use zstd by default (`uploadCompression` also supports
zlib or none); smaller batches avoid compression overhead. A row is deleted only after the matching
msgtrans response. On iOS, entering the background automatically schedules a FIFO flush so events
already accepted by the SDK move into the SQLite outbox; `flushAndWait` remains available for a
host-controlled fatal or shutdown boundary. A POSIX crash handler
cannot safely run Kotlin allocation, coroutine, or
network code, so it writes a preallocated crash record and reports that record through the normal
pipeline on the next launch.

The seven permanently allocated Pulse `biz_type` values, compression and delivery contract are in
[WIRE_PROTOCOL.md](WIRE_PROTOCOL.md). msgtrans-kotlin implements none, zstd and zlib, validates the
header value, and rejects decompression output beyond its configured limit.

## Build and test

```bash
./gradlew :pulsekit:macosArm64Test          # model / pipeline / crash / runtime / end-to-end Pulse.start -> msgtrans
./gradlew :pulsekit:linkDebugTestLinuxX64   # cross-compile for Linux
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
- Encoded event batches are persisted in a bounded SQLDelight outbox across process termination.
  iOS entering the background automatically schedules an ordered flush; a process killed before
  that command runs can still lose the final in-memory observations.
- `batchMaxBytes` limits the complete encoded wire envelope. Oversized individual observations are
  dropped and counted instead of blocking all later telemetry.
- Apple runtime observation covers the currently registered Objective-C API signatures. Android
  transport, lifecycle, crash, ANR, and runtime collectors have not been implemented yet.
- `pulse-push` and web/TypeScript SDK interoperability have not been implemented.

The iOS third-party audit evidence model, privacy boundary, known coverage gaps, and delivery order
are defined in [IOS_RUNTIME_AUDIT.md](IOS_RUNTIME_AUDIT.md). Android work is intentionally deferred.

## License

[Apache License 2.0](LICENSE).
