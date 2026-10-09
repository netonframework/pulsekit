# PulseKit

Application analytics, performance/error monitoring, and runtime observation for the
Kotlin/Native stack. The product targets are iOS and Android: 友盟 (U-App analytics + U-APM) class,
plus evidence-oriented auditing of third-party plugins inserted during signing and packaging.
PulseKit is a data/reliability SDK — it does not integrate any IM/PrivChat logic.

The client SDK produces one unified `Event` model and ships batches to the Pulse ingest server over
the [msgtrans](../msgtrans-kotlin) long connection (which runs on the [neton-io](../io)
reactor). The server hands received batches to the Pulse modules → a Redis queue → consumers that
persist into each module's store.

## Artifacts

The SDK is one Kotlin/Native artifact, `com.netonstream:pulsekit`, and each app platform consumes it
through a boundary that does not care about the host's Kotlin version:

- **iOS** — the `PulseKit` CocoaPods framework (`pod 'PulseKit', :path => '<pulsekit>/pulsekit'`
  from source), an Objective-C interface.
- **Android** — the `com.netonstream:pulsekit-android` AAR (minSdk 21): the same SDK compiled for
  the JVM, with the Android platform layer and the public API written in Java (`:pulsekit-android`).
  No PulseKit native code; the only native library it brings is zstd-kmp's.
- **Kotlin/Native** consumers depend on the klib directly and must compile with the release's
  Kotlin version.

```kotlin
dependencies { implementation("com.netonstream:pulsekit:0.2.0") }          // Kotlin/Native
dependencies { implementation("com.netonstream:pulsekit-android:0.2.0") }  // Android app
```

The capabilities are packages, not artifacts — whether one runs is `PulseConfig`'s decision
(`analytics` / `apm` / `runtime`), and the linker strips what a build does not reach:

| package | role |
|---|---|
| `pulse.core` | the unified `Event` / `Session` / `Identity` model, `PulseConfig`, bounded `EventBuffer`, durable outbox, `EventCodec` (JSON), `EventSink` boundary, and the `PulseClient` batch/flush pipeline with the server-driven collection policy. |
| `pulse.analytics` | U-App class: `track` business events, `identify`, automatic lifecycle (session/launch/foreground/background). |
| `pulse.apm` | U-APM / Bugly class: errors, crashes (signals and uncaught Objective-C exceptions), performance samples and gauges. |
| `pulse.runtime` | Opt-in runtime inventory: loaded images (Mach-O UUID / ELF build-id), signing and privacy declarations; on Apple also sensitive API observation with caller/module attribution. |
| `pulse.transport` | the `EventSink` backed by the msgtrans long connection (batch → ingest request, heartbeat). |
| `pulse` (`PulseSDK`, `Pulse`) | batteries-included entry wiring the above, plus what the SDK measures on its own: launch time, main-thread hangs, memory footprint, lifecycle. `PulseSDK` and the automatic instrumentation are one shared source set (`clientMain`) for Apple and Android. |

Naming is deliberately neutral (Pulse / analytics / apm / runtime / context); the client never
labels events as first/system/third-party — the server attributes them.

## Usage

Create an App in the Pulse console first, then embed the generated App ID in the host. The App ID
is a public routing identifier, not a secret. On iOS the host also reports its real bundle ID; the
server applies that App's `strict`, `allowlist`, or `any` package policy.

Every platform starts the same way: an App ID and the ingest server's URL, `tcp://host[:port]`
(the port defaults to 6000). The scheme is required and only `tcp` is supported today; a path,
query, credentials or an unbracketed IPv6 address is rejected, and a malformed endpoint leaves the
SDK stopped rather than throwing into the app.

```kotlin
runReactor {
    val pulse = Pulse.start(this, PulseConfig(
        projectId = "vip-mall",
        endpoint = "tcp://collect.example.com:6000",
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
PulseSDK.shared.startWithAppId(appId: "pulse_xxxxxxxxxxxxxxxxxxxxxxxx", endpoint: "tcp://collect.example.com:6000")
```

The bundle ID, build number (`CFBundleVersion`) and version (`CFBundleShortVersionString`) are read
from the main bundle. The full form,
`startWithAppId(appId:endpoint:runtime:packageName:buildNumber:appVersion:)`, overrides them;
`nil` / `0` there still means "this app's own".

Android hosts call the same facade from `Application.onCreate` — Java or any Kotlin version:

```kotlin
PulseSDK.shared.startWithAppId("pulse_xxxxxxxxxxxxxxxxxxxxxxxx", "tcp://collect.example.com:6000")
PulseSDK.shared.identify("100086")
PulseSDK.shared.track("purchase", mapOf("amount" to "199"))
val update = PulseSDK.shared.awaitUpdateInfo(5_000)   // off the main thread; update.isBlocking
```

No `Context` is passed — the call is the same as on iOS. `PulseInitProvider`, merged into the
host's manifest from the library, hands the SDK the application context when the process starts
(before `Application.onCreate`); it starts nothing by itself. The full form with
`runtime, packageName, buildNumber, appVersion` matches iOS too; on Android an omitted package name, build number (0) or version is filled from the app's own `PackageInfo`
(`versionCode` / `versionName`). The facade registers activity lifecycle callbacks (foreground at
the first resume after the app's first activity starts, background when its last one stops;
configuration changes are neither) and chains a `Thread.UncaughtExceptionHandler`.

Apps that want link-time stripping can build a `PulseClient` with only the capabilities they need
instead of the umbrella `Pulse` entry.

## Transport and delivery

A batch is sent as one msgtrans **request** with biz type `5` (`CLIENT_EVENT_BATCH_UPLOAD`); only a
`{"code":0,"msg":null,"data":true}` response confirms receipt into the durable pipeline. `request`
gives delivery confirmation and backpressure; a failed send or non-zero application response
remains in a bounded SQLDelight/SQLite outbox and is retried in FIFO order after backoff or the next
process launch. Batches of at least 1 KiB use zstd by default (`uploadCompression` also supports
zlib or none); smaller batches avoid compression overhead. A row is deleted only after the matching
msgtrans response. On iOS and Android, entering the background automatically schedules a FIFO flush
so events already accepted by the SDK move into the SQLite outbox; `flushAndWait` remains available for a
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
./gradlew :pulsekit:jvmTest                 # the same suite on the JVM (what Android runs), JDBC SQLite
./gradlew :pulsekit-android:assembleRelease # the AAR
```

End to end on an emulator, without the server: run the stand-in ingest server on the Mac, install
the sample host and drive it with intents (`track`, `update`, `hang`, `java_crash`; see
`pulsekit-android-sample`). Each event the stub receives is printed as a `PULSE_EVENT` line.

```bash
./gradlew :pulsekit:linkPulseIngestStubDebugExecutableMacosArm64 :pulsekit-android-sample:installDebug
pulsekit/build/bin/macosArm64/pulseIngestStubDebugExecutable/pulseIngestStub.kexe 127.0.0.1 9600
adb shell am start -n com.netonstream.pulsekit.sample/.MainActivity --es action track
```

## Status

Usable iOS/Kotlin-Native baseline (2026-09-14): analytics, APM, runtime observation, persisted
device/installation identity, next-launch crash recovery, startup update checks, and the msgtrans
ingest transport are implemented. The CocoaPods dynamic framework exposes a stable Objective-C
entry point to Swift hosts, including hosts built with a different Kotlin version.

The SDK and transport suites pass on macOS, the iOS simulator, and real Linux epoll/io_uring
(including a depth-8 io_uring run). The end-to-end path has also been exercised against the Pulse
server, Redis queue, and PostgreSQL storage.

Android (2026-10-03): the same SDK compiled for the JVM — `PulseSDK`, automatic instrumentation,
the event pipeline, the SQLDelight outbox and the msgtrans transport are shared code, running on
neton-io's NIO reactor — under a platform layer and public API written in Java (`pulse.host.
HostEnvironment` is the seam). The suite passes on the JVM (47 cases), and on an arm64 emulator
(API 36) the sample host against `pulseIngestStub` delivered registration (`platform=android`), the
update check, session / launch / foreground / background, `app_launch_time`, `main_thread_hang`,
`memory_footprint`, business events, the runtime declaration events, a next-launch report of an
uncaught Java exception (with its stack trace, joined to its session, the platform's handler still
run), rotation without spurious transitions, and offline events replayed after a force-stop. A host
built with Kotlin 2.1.21 compiles against the AAR and runs it. Not yet exercised against the real
Pulse server, and not on a physical device.

What Android does differently, and why:

- The platform facts come from the framework: process start is `Process.getStartElapsedRealtime`
  (the zygote fork; `/proc/self/stat` below API 24), memory is resident set size from
  `/proc/self/statm` (iOS: `phys_footprint`), the device type is `Build.MODEL` (iOS: the device
  family), the ids live in SharedPreferences (iOS: NSUserDefaults), the outbox in the framework
  SQLite through SQLDelight's Android driver, and the main-thread watchdog posts to the main `Looper`
  instead of `dispatch_async`.
- The first "active" usually happens before the SDK thread has set up, so the platform layer
  remembers the state and replays it, with the time it happened, to the SDK's observer; the launch
  time is measured to the real moment.
- Crash capture covers uncaught Java exceptions: a chained `Thread.UncaughtExceptionHandler` writes
  the next-launch record (class, message, stack trace) and passes the exception on. A native crash
  inside a host's own `.so` is not captured (a JVM cannot run a signal handler); Android's tombstone
  and the platform's crash reporting still see it.
- Runtime observation reports facts only. Android has no counterpart of the Objective-C hooks without
  ART method hooking, which PulseKit does not do, so `runtime_monitor_installed` reports zero hooks
  and no `*_read` call events are produced. The image inventory comes from `/proc/self/maps` (the
  APK's directory is the install root; extracted `.so` files carry their GNU build-id, libraries
  mapped straight from the APK appear as the APK), `privacy_declarations` lists the merged manifest's
  permissions and which are granted, and `signing_declarations` carries the signing certificates'
  SHA-256, key rotation, `debuggable` and the installer. There is no counterpart of the Objective-C
  method inventory or per-SDK privacy manifests.
- The JVM artifacts ask for kotlin-stdlib 2.2.21 and use no stdlib API newer than 2.2, so an app
  built with Kotlin 2.1 (KuiklyUI) can use the AAR; SQLDelight stays on 2.2.x for the same reason
  (2.3's runtime requires stdlib 2.3). An older AGP prints D8 warnings about the SDK's Kotlin
  metadata; they are warnings only.

Current boundaries:

- `userId` is optional host-provided identity, like the account binding API of an analytics SDK;
  PulseKit does not know or depend on an app's authentication model.
- Encoded event batches are persisted in a bounded SQLDelight outbox across process termination.
  Entering the background (iOS and Android) automatically schedules an ordered flush; a process
  killed before that command runs can still lose the final in-memory observations.
- `batchMaxBytes` limits the complete encoded wire envelope. Oversized individual observations are
  dropped and counted instead of blocking all later telemetry.
- Apple runtime observation covers the currently registered Objective-C API signatures. Android
  runtime observation is declarations and image inventory only (see above); code an SDK loads from
  the app's data directory is outside the install root and is not reported.
- `pulse-push` and web/TypeScript SDK interoperability have not been implemented.

The iOS third-party audit evidence model, privacy boundary, known coverage gaps, and delivery order
are defined in [IOS_RUNTIME_AUDIT.md](IOS_RUNTIME_AUDIT.md).

## License

[Apache License 2.0](LICENSE).
