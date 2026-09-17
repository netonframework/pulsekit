# Pulse msgtrans wire contract

PulseKit uses one long-lived msgtrans connection for native telemetry and application-control
requests. `biz_type` belongs to the Pulse application protocol and is written into byte 3 of the
fixed 16-byte msgtrans header. It is not an event kind and must not be inferred from JSON content.

This file is the canonical Pulse application wire specification. “Must”, “must not”, “should” and
“may” are normative. Client and server models intentionally remain independent; compatibility is
proved against this document and wire fixtures rather than by sharing Kotlin classes.

## Layer boundary

The msgtrans header carries four independent concepts. Implementations must not overload one field
with the meaning of another:

| Concern | Wire location | Owner |
|---|---|---|
| Request, response or lossy notification | `packet_type` | msgtrans |
| Pulse operation and processing contract | `biz_type` | Pulse |
| Correlation of a response to a request | `message_id` | msgtrans |
| None, zstd or zlib | `compression` | msgtrans |

`biz_type` is an unsigned byte (`0..255`). Pulse value `0` is invalid even though msgtrans accepts
it as its application-neutral default. Pulse payloads are UTF-8 JSON before optional compression.
The receiver first validates and decompresses the msgtrans packet, then dispatches the resulting
plaintext payload by `biz_type`.

## Message types

| `biz_type` | Name | Packet flow | Payload | Success `data` |
|---:|---|---|---|---|
| `0` | Reserved | — | — | — |
| `1` | `CLIENT_CONNECT` | Client Request → Server Response | App, package, install/device identifier, SDK/version and supported capabilities | Connection ID, server time, configuration revision and heartbeat interval |
| `2` | `CLIENT_HEARTBEAT` | Client Request → Server Response | Connection ID, client time and PulseKit version | Acceptance, server time, latest configuration revision and next heartbeat interval |
| `3` | `CLIENT_CONFIG_PULL` | Client Request → Server Response | Current configuration revision and SDK capabilities | Sampling, audit, collection and endpoint policy |
| `4` | `CLIENT_CRASH_BATCH_UPLOAD` | Client Request → Server Response | One or more high-priority crash reports plus attachment metadata | `true` after durable acceptance into the crash queue |
| `5` | `CLIENT_EVENT_BATCH_UPLOAD` | Client Request → Server Response | One identity envelope and an ordered event array | `true` after the batch is durably accepted into the server queue |
| `6` | `CLIENT_APP_UPDATE_CHECK` | Client Request → Server Response | Platform, App ID, package name and monotonic build number | Update decision object; failures fail open to “no update” |
| `7` | `BIDIRECTIONAL_CONTROL_RPC` | Bidirectional Request → Response | Stable route string plus route-specific arguments | Route-specific result |

This table is the canonical ABI beginning with Pulse protocol version `2`. The unreleased beta
mapping used different values and is intentionally incompatible. These version-2 values are never
renumbered or reused. A response echoes its request's message ID and biz type, so it does not need
a separate biz type. New values must be added to
`pulse.core.PulseBizType` and mirrored by the server's independent wire decoder.

There are **seven allocated business types**, plus reserved value `0`. `CLIENT_CONNECT`,
`CLIENT_HEARTBEAT`, `CLIENT_EVENT_BATCH_UPLOAD` and `CLIENT_APP_UPDATE_CHECK` are implemented today. The other assigned
operations must return `code=501` until their payload and handler are implemented.

Msgtrans is bidirectional. Direction is specified by this table rather than encoded into a numeric
range: `BIDIRECTIONAL_CONTROL_RPC` can originate from either peer, while the other allocated types originate from
the SDK. A long-lived connection may carry requests in both directions at the same time; response
completion is independent from request-handler execution, so a reverse request cannot deadlock the
read loop. Until a control route is implemented, the SDK answers it with a structured non-zero
response; it never silently returns an empty payload.

`BIDIRECTIONAL_CONTROL_RPC` follows the same idea as PrivChat's generic RPC packet: new operational commands add
a stable payload route instead of consuming a new `biz_type`. Initial route namespaces are
`config.*`, `telemetry.*` and `audit.*`. A route must be allowlisted by the SDK; the server cannot
use this channel to invoke arbitrary native methods or collect arbitrary data.

## Packet-type rules

1. Every operation in the table above uses msgtrans `Request`, including telemetry upload and
   heartbeat. Receipt of bytes is not acceptance; only the matching application response confirms
   the operation.
2. The receiver must send exactly one `Response` for every syntactically complete `Request`, even
   when the operation fails. The response must reuse both `message_id` and `biz_type`.
3. A sender must register the request as in-flight before it becomes writable. Timeout,
   cancellation, response and connection close race to one terminal completion and remove it once.
4. `OneWay` is allowed only after a future specification explicitly marks a notification as lossy.
   None of the seven currently allocated Pulse types may be sent as `OneWay`.
5. A `Response` never enters the business request handler. It completes the matching in-flight
   request. An unmatched or duplicate response is a protocol fault and must not be interpreted as
   a new business operation.
6. After `CLIENT_CONNECT` succeeds, receiving an unknown `biz_type` must produce `code=404` and an
   allocated but unavailable type must produce `code=501`. Before it succeeds, the first-frame rule
   takes precedence and a first frame whose type is not `CLIENT_CONNECT` closes the connection.

## Direction and connection lifecycle

A numeric range does not encode direction. Direction is metadata in the message registry because
responses already reuse the request type and `BIDIRECTIONAL_CONTROL_RPC` is intentionally bidirectional.

The intended native connection sequence is:

1. Establish one msgtrans connection.
2. Send `CLIENT_CONNECT` as the first frame. It binds this live
   connection to the App ID, allowed package, installation/device identity, build and SDK
   capabilities. It is SDK registration, not application-user login; `userId` remains optional and
   only comes from the host application's explicit identify call.
3. Reconcile configuration with `CLIENT_CONFIG_PULL` when the connect response reports a newer
   revision.
4. Upload durable telemetry and crash batches. Requests may be in flight concurrently up to the
   configured per-connection bound.
5. Send `CLIENT_HEARTBEAT` at the server-provided interval while idle so NAT state and the server's
   connection registry remain current.
6. Either peer may issue `BIDIRECTIONAL_CONTROL_RPC`. Reading responses and running inbound handlers must progress
   independently, so a handler may make a reverse request without deadlocking the connection.

The SDK performs `CLIENT_CONNECT` immediately after transport connection. At the transport boundary,
the server requires the first inbound frame to be a msgtrans `Request` with `biz_type=1`. Any other
first frame is a protocol violation: the server closes that connection immediately, sends no
application response and does not dispatch the payload. A rejected or malformed `CLIENT_CONNECT`
receives its normal error response; no other operation is accepted until connect succeeds. The
accepted identity is immutable for the lifetime of a connection. A batch whose App, package,
device, installation, platform or declared PulseKit version differs from the connected identity is
rejected with `code=403`.

## Per-type payload rules

### 1 — `CLIENT_CONNECT`

Carries protocol version, App ID, runtime package, installation/device ID, platform/device type,
app version/build, SDK version and a capability list. Success returns a connection ID, server time,
configuration revision and heartbeat interval. Package policy is evaluated here so an App ID may
allow one or more explicitly configured packages without treating the package name as the App ID.

App ID is public identification. A future authenticated form may add `keyId`, `timestamp`, `nonce`,
`algorithm` and `signature` fields. The AppSecret is never transmitted. The signature covers a
canonical representation of the complete connect identity plus timestamp and nonce; the server
checks allowed clock skew, nonce replay, key status and package policy before accepting the client.
A fixed secret in a distributed mobile binary raises the cost of casual forgery but is extractable;
stronger deployments should combine this proof with Apple App Attest or a server-issued installation
credential.

### 2 — `CLIENT_HEARTBEAT`

Carries the server-issued connection ID, client time and PulseKit version. Success returns
`accepted=true`, server time, the latest configuration revision and the next heartbeat interval.
The SDK schedules the next request from that interval (currently 60 seconds). Each accepted
heartbeat refreshes a 150-second Redis presence TTL and updates durable PostgreSQL connection
history. A mismatched connection ID or SDK version is rejected with `code=403`. This application
heartbeat maintains routability and policy reconciliation; lower-level socket liveness remains a
msgtrans/neton-io responsibility.

### 3 — `CLIENT_CONFIG_PULL`

Will carry the client's current revision and supported capabilities. Success will return a complete
versioned policy snapshot covering sampling, enabled monitors, audit policy, batching, compression
and endpoints. Applying a snapshot must be atomic.

### 4 — `CLIENT_CRASH_BATCH_UPLOAD`

Will carry durable high-priority crash records, build identity and attachment metadata. It is
separate from general events because crash ingestion has different size limits, retention,
symbolication, attachment handling and queue priority.

### 5 — `CLIENT_EVENT_BATCH_UPLOAD`

Carries one identity and an ordered, non-empty event array. `batchId` is stable for the lifetime of
the durable outbox row and must survive retries. `event.id` is the database idempotency key. The
identity belongs to the whole batch and must not be repeated in every event.

Event `kind` values such as `Analytics`, `Network`, `Performance`, `Runtime` and `Breadcrumb` do not
receive separate `biz_type` values. Sensitive values such as credentials, authorization headers and
URL query/fragment data must be removed before the event reaches the outbox.

Success is `{"code":0,"msg":null,"data":true}` and means the server has durably accepted the batch
into its queue. Decode success, socket receipt or an in-memory handoff is insufficient.

### 6 — `CLIENT_APP_UPDATE_CHECK`

Carries `platform`, App ID/AppKey, runtime package name and monotonic build number. Version ordering
uses the platform build number (`CFBundleVersion` on iOS), not lexical comparison of display
versions. A successful `data` object returns `action` (`none`, `optional` or `forced`), latest
version/build, release notes and download URL. Any failure must fail open to “no update” in the SDK;
Pulse telemetry must not prevent the host app from launching.

### 7 — `BIDIRECTIONAL_CONTROL_RPC`

Carries a stable route and route-specific arguments:

```json
{"route":"telemetry.flush","args":{},"schemaVersion":1}
```

The normal Pulse response envelope wraps the route-specific result. Routes use lower-case dotted
names and are append-only contracts. Initial namespaces are `config.*`, `telemetry.*` and
`audit.*`. Each SDK build must explicitly allowlist supported routes and validate their arguments.
There is no route for arbitrary Objective-C selector invocation, arbitrary file reads, arbitrary
network requests or permission requests. Unsupported routes return `code=404` without closing the
connection.

### Allocation rules

- Values are unsigned bytes and are permanent after assignment. Append only; never renumber or
  reuse a retired value.
- `biz_type` identifies the processing and delivery contract, not each telemetry event kind.
  Analytics, sessions, network observations, performance and permission/API audit events remain
  records inside `CLIENT_EVENT_BATCH_UPLOAD`.
- Crash upload is separate because it needs higher delivery priority, larger limits, symbolication,
  attachments and a different server queue. Legacy crash events inside event batches remain valid.
- Transport keepalive is a msgtrans concern. `CLIENT_HEARTBEAT` is the application heartbeat used to keep
  the connected client routable and reconcile configuration revisions.
- Responses reuse the request's `biz_type` and `message_id`; response-only type numbers are not
  allocated.
- A new `biz_type` is justified only by a different direction, delivery guarantee, priority, size
  limit, authorization boundary or server processing pipeline. A new JSON shape by itself is not
  sufficient; use an event name or `BIDIRECTIONAL_CONTROL_RPC` route when those semantics remain the same.
- Reserve the next unused value only when its contract is reviewed. Update the canonical table,
  client constant, independent server constant and conformance tests in the same change.
- Removing a feature retires its number permanently. Receivers may continue decoding it for old
  clients, but the value must never acquire a different meaning.
- Experimental operations use `BIDIRECTIONAL_CONTROL_RPC` routes. They must not consume permanent type numbers
  until their distinct delivery or processing contract is proven.

## Response envelope

Every Pulse `Request` receives exactly one msgtrans `Response`, using the same `message_id` and
`biz_type`. Its JSON payload always has this application envelope:

```json
{"code": 0, "msg": null, "data": true}
```

`code = 0` means success. `data` contains the typed result and may be `null`. A non-zero code is an
application failure and `msg` explains it; it is still a valid protocol response and does not close
the connection.

| Code | Meaning | Default sender action |
|---:|---|---|
| `0` | Accepted | Complete the request; remove a durable outbox row only when its typed `data` also confirms acceptance |
| `400` | Malformed payload or invalid direction/packet usage | Do not retry unchanged bytes |
| `401` | Registration/authentication missing or invalid | Refresh credentials/register before retrying |
| `403` | App, package or policy rejected | Do not retry until server policy changes |
| `404` | Unknown `biz_type` or control route | Do not retry unchanged request |
| `409` | Session/config revision conflict | Register or pull configuration, then retry once |
| `429` | Bounded queue or in-flight capacity exhausted | Retry with jittered exponential backoff |
| `500` | Internal request failure | Retry durable operations with backoff |
| `501` | Permanently allocated operation is not implemented by this peer | Do not retry until peer capability/version changes |
| `503` | Durable processing queue unavailable | Retry durable operations with backoff |

Transport timeout, cancellation and disconnect have no application code because no valid response
was received. Durable operations remain queued and retry. The client must cap attempts per time
window and add jitter so reconnecting devices do not create a retry storm.

During the current beta migration, the update-check response also repeats its update fields at the
JSON root so already-built SDKs can continue to parse it. New code must read `data`; the root-level
copy is compatibility-only and can be removed after those builds are retired.

## Sender usage

The application layer serializes the request body, selects the registered constant and calls the
request API. It must inspect the Pulse response rather than treating any returned bytes as success:

```kotlin
val bytes = connection.request(
    payload = encodedBatch,
    bizType = PulseBizType.CLIENT_EVENT_BATCH_UPLOAD,
    compression = Compression.Zstd,
)
val response = decodePulseResponse<Boolean>(bytes)
if (response.code == 0 && response.data == true) outbox.ack(batchId)
```

Business code must not pass numeric literals such as `bizType = 1`; it uses the named registry
constant. The transport must not inspect Pulse JSON or choose a `biz_type` from payload contents.

## Receiver dispatch

The receiver dispatches only after msgtrans has validated the complete frame and decompressed its
payload. Handler failures are converted to the common response envelope. They must not escape and
silently abandon the request:

```kotlin
connection.onRequest { payload, bizType ->
    when (bizType) {
        PulseBizType.CLIENT_EVENT_BATCH_UPLOAD -> handleEventBatch(payload)
        PulseBizType.CLIENT_APP_UPDATE_CHECK -> handleUpdateCheck(payload)
        else -> encodeResponse(code = 404, msg = "unsupported pulse biz_type=$bizType")
    }
}
```

The read path completes inbound Responses before scheduling business handlers. Business handlers
must not mutate connection state from another thread; cross-thread work returns through the
connection/reactor dispatch entry.

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

Before an outbox row is created, the client selects the largest FIFO event prefix whose complete
encoded envelope (identity, batch id and events) is no larger than `batchMaxBytes`. Remaining events
form later rows without reordering. An individual event larger than that limit can never be sent;
the client drops it, increments `oversizedEventsDropped`, and continues with later events so one
bad observation cannot permanently block the queue.

## Permission boundary

PulseKit never requests a platform permission. Runtime monitoring records which module called a
permission or sensitive-system API and, where the existing call supplies a completion callback,
observes the resulting state. It must not create a permission prompt, change the application's
decision, or add a permission declaration to the host bundle.
