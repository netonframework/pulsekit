# Pulse msgtrans wire contract

PulseKit uses one long-lived msgtrans connection for native telemetry and application-control
requests. `biz_type` belongs to the Pulse application protocol and is written into byte 3 of the
fixed 16-byte msgtrans header. It is not an event kind and must not be inferred from JSON content.

## Message types

| `biz_type` | Name | Packet flow | Payload | Success `data` |
|---:|---|---|---|---|
| `0` | Reserved | — | — | — |
| `1` | `EVENT_BATCH_UPLOAD` | Client Request → Server Response | One identity envelope and an ordered event array | `true` after the batch is durably accepted into the server queue |
| `2` | `APP_UPDATE_CHECK` | Client Request → Server Response | Platform, App ID, package name and monotonic build number | Update decision object; failures fail open to “no update” |
| `3` | `SESSION_REGISTER` | Client Request → Server Response | App, package, install/device identifier, SDK/version and supported capabilities | Connection ID, server time, configuration revision and heartbeat interval |
| `4` | `CRASH_BATCH_UPLOAD` | Client Request → Server Response | One or more high-priority crash reports plus attachment metadata | `true` after durable acceptance into the crash queue |
| `5` | `CONFIG_PULL` | Client Request → Server Response | Current configuration revision and SDK capabilities | Sampling, audit, collection and endpoint policy |
| `6` | `HEARTBEAT` | Client Request → Server Response | Connection ID and current configuration revision | Server time and latest configuration revision |
| `7` | `CONTROL_RPC` | Bidirectional Request → Response | Stable route string plus route-specific arguments | Route-specific result |

Values are an ABI. They are never renumbered or reused. A response echoes its request's message ID
and biz type, so it does not need a separate biz type. New values must be added to
`pulse.core.PulseBizType` and mirrored by the server's independent wire decoder.

There are **seven allocated business types**, plus reserved value `0`. Types `1` and `2` are
implemented today; types `3` through `7` are permanently assigned for the next protocol stage and
must return `code=501` until their payload and handler are implemented.

Msgtrans is bidirectional. Direction is specified by this table rather than encoded into a numeric
range: `CONTROL_RPC` can originate from either peer, while the other allocated types originate from
the SDK. A long-lived connection may carry requests in both directions at the same time; response
completion is independent from request-handler execution, so a reverse request cannot deadlock the
read loop. Until a control route is implemented, the SDK answers it with a structured non-zero
response; it never silently returns an empty payload.

`CONTROL_RPC` follows the same idea as PrivChat's generic RPC packet: new operational commands add
a stable payload route instead of consuming a new `biz_type`. Initial route namespaces are
`config.*`, `telemetry.*` and `audit.*`. A route must be allowlisted by the SDK; the server cannot
use this channel to invoke arbitrary native methods or collect arbitrary data.

### Allocation rules

- Values are unsigned bytes and are permanent after assignment. Append only; never renumber or
  reuse a retired value.
- `biz_type` identifies the processing and delivery contract, not each telemetry event kind.
  Analytics, sessions, network observations, performance and permission/API audit events remain
  records inside `EVENT_BATCH_UPLOAD`.
- Crash upload is separate because it needs higher delivery priority, larger limits, symbolication,
  attachments and a different server queue. Legacy crash events inside event batches remain valid.
- Transport keepalive is a msgtrans concern. `HEARTBEAT` is the application heartbeat used to keep
  the registered session routable and reconcile configuration revisions.
- Responses reuse the request's `biz_type` and `message_id`; response-only type numbers are not
  allocated.

## Response envelope

Every Pulse `Request` receives exactly one msgtrans `Response`, using the same `message_id` and
`biz_type`. Its JSON payload always has this application envelope:

```json
{"code": 0, "msg": null, "data": true}
```

`code = 0` means success. `data` contains the typed result and may be `null`. A non-zero code is an
application failure and `msg` explains it; it is still a valid protocol response and does not close
the connection. Current server codes are `400` for malformed input, `404` for an unsupported
`biz_type`, `501` for an allocated operation not yet implemented, `429` for bounded-queue overload,
`500` for an internal request failure, and `503` when
the durable event queue is unavailable.

Transport timeout, cancellation and disconnect remain transport failures because no response was
received. `OneWay` packets are allowed only for explicitly lossy notifications. Telemetry uploads,
configuration commands and any operation whose caller needs a result must use Request/Response.

During the current beta migration, the update-check response also repeats its update fields at the
JSON root so already-built SDKs can continue to parse it. New code must read `data`; the root-level
copy is compatibility-only and can be removed after those builds are retired.

## Event upload

One upload contains multiple events. The client preserves their queue order and compresses the
whole encoded batch once, rather than compressing individual events. The msgtrans header's
`compression` byte is authoritative:

| Value | Encoding |
|---:|---|
| `0` | none |
| `1` | zstd |
| `2` | zlib |

Payloads smaller than `compressionMinBytes` remain uncompressed. Larger event batches use the
configured codec (zstd by default). A sender must never label uncompressed bytes as zstd or zlib.
A receiver rejects unsupported encodings, invalid compressed streams, and output expanding beyond
16 MiB. msgtrans-kotlin compresses after the complete payload is built and normalizes inbound
packets back to plaintext before dispatch.

## Delivery contract

Delivery is at least once. The device first commits an encoded batch to a persistent ordered
outbox, then sends the oldest due batch. It deletes that row only after the matching msgtrans
response arrives. Disconnects, timeouts and process termination leave the row for retry. Event IDs
make replay idempotent at the database.

The server responds with `{code:0,data:true}` only after it has durably moved the accepted batch into Redis. Redis consumers
must claim work into a processing state and acknowledge it after the database transaction commits;
removing an item before persistence leaves a crash window and does not satisfy this contract.

The mobile outbox is an SQLite table ordered by an autoincrement sequence. It is bounded by byte
size, event count and age, and records attempt count and next-attempt time for exponential backoff.
MMKV remains suitable for small configuration values, but it is not the event queue.

## Permission boundary

PulseKit never requests a platform permission. Runtime monitoring records which module called a
permission or sensitive-system API and, where the existing call supplies a completion callback,
observes the resulting state. It must not create a permission prompt, change the application's
decision, or add a permission declaration to the host bundle.
