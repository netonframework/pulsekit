# Pulse msgtrans wire contract

PulseKit uses one long-lived msgtrans connection for native telemetry and application-control
requests. `biz_type` belongs to the Pulse application protocol and is written into byte 3 of the
fixed 16-byte msgtrans header. It is not an event kind and must not be inferred from JSON content.

## Message types

| `biz_type` | Name | Packet flow | Payload | Success response |
|---:|---|---|---|---|
| `0` | Reserved | — | — | — |
| `1` | `EVENT_BATCH_UPLOAD` | Request → Response | One identity envelope and an ordered event array | Same message ID and biz type after the batch is durably accepted into the server queue |
| `2` | `APP_UPDATE_CHECK` | Request → Response | Platform, App ID, package name and monotonic build number | Update decision JSON; failures fail open to “no update” |

Values are an ABI. They are never renumbered or reused. A response echoes its request's message ID
and biz type, so it does not need a separate biz type. New values must be added to
`pulse.core.PulseBizType` and mirrored by the server's independent wire decoder.

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

The server responds only after it has durably moved the accepted batch into Redis. Redis consumers
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
