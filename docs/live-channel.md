# Live Channel — V3 #14 (WebSocket two-way)

V2 shipped the "inbox + per-device envelopes + SSE hints" model.
V3 adds a bidirectional channel for plugins that need push-style
updates with low latency (chat, presence, cursors, score
streams). This spec is V3 #14 only — `platform.live.subscribe`
(#15) is SDK sugar over the same wire; `card.patch` field-level
updates (#16) and the Redis backplane (#17) need their own
design triads.

Closed at: triad 141 (codex + gemini) with the items below
applied. v0.1 dev posture.

## What this is and isn't

**Is:**
- A multiplexed WebSocket between one device and the Syncler
  server, scoped to a `(user_id, device_id, plugin_row_id)`
  triple.
- Best-effort ephemeral delivery (no backfill, no durable
  ordering — inbox pull remains authoritative for missed
  state).
- Two-way: sender → device (via signed POST to the server)
  and device → sender (via signed webhook from the server).
- E2EE: WS frames carry V2-shape envelopes; the server is a
  routing pipe with no payload access.

**Is not:**
- A durable message bus (use the inbox path).
- An ordered CRDT-safe channel (V3 #17 may add that).
- A presence-aware service (no roster API in V0.1).
- A direct peer-to-peer fabric.

## Auth model

```
GET /v1/live/plugin/{plugin_row_id}
Sec-WebSocket-Protocol: syncler.v1, bearer.<short-connect-token>
```

Two-step:
1. Device POSTs `/v1/live/connect-token` with its device JWT.
   Server mints a short-lived (60s) opaque connect token bound
   to `(user_id, device_id)`. This keeps the long-lived JWT
   out of the WS handshake's subprotocol parsing path (codex
   141 #1).
2. Device opens the WS with `bearer.<token>`. Server validates:
   - Token valid + not expired.
   - `plugin_row_id` exists and is active.
   - Plugin's `sender_id` is paired to `user_id` (existing
     pairing-table join).

Critical invariant (codex 141): never trust frame-body
`user_id`, `device_id`, `sender_id`, or channel ownership.
Routing decisions read ONLY the server-validated socket
identity.

**Revocation:** when a pairing is deleted or a device JWT is
revoked, the server publishes a control event on the internal
bus; the WS worker listening for that `plugin_row_id`'s
sockets closes the matching ones with close code 4401
(`pairing_revoked`). The polling fallback for V0.1 single-
worker dev is to re-check pairing every 60s, but the bus path
is the production answer (and lands together with V3 #17 once
the hub interface is finalized).

## Wire contract

All frames are UTF-8 JSON text. Binary opcode reserved for
future use. Max frame: 64 KB.

### Outer envelope (all frames)

```json
{
  "channel": "<= 64 chars, ^[a-zA-Z0-9._-]+$",
  "type": "open" | "close" | "message" | "error" | "ack" | "ping" | "pong",
  "id": "<= 32 chars, opaque",
  "payload": "..."
}
```

- `channel` — in-band tag, scoped to the socket's
  `plugin_row_id`. Server doesn't interpret it beyond
  multiplexing.
- `type` — frame kind.
- `id` — client-generated for `message` frames; echoed in
  `ack` and `error`. Omitted for `ping`/`pong`/`open`/`close`.
- `payload` — frame-type-specific.

### Open

Client → server: announce a new channel.

```json
{ "channel": "chat-room-42", "type": "open" }
```

Server replies with `open` echoing the channel name (success)
or `error` with `code=channel_limit_exceeded` /
`code=channel_name_invalid`.

Cap: 10 open channels per socket. 11th `open` returns
`channel_limit_exceeded`.

### Close

```json
{ "channel": "chat-room-42", "type": "close" }
```

Server echoes. Either side can close a channel without
closing the socket. Closing all channels does NOT close the
socket — heartbeat is the keepalive.

### Message

```json
{
  "channel": "chat-room-42",
  "type": "message",
  "id": "msg-12345",
  "payload": "<base64 V2-style envelope bytes>"
}
```

`payload` is OPAQUE to the server. For sender → device
messages, it's a V2 envelope sealed for THIS device's
encryption key (same shape `/v1/messages/send` already
uses). For device → sender messages, the device seals for
the sender's registered public key (same key used for
plugin manifest verification).

The server's role: route. No decryption, no inspection.

### Ack

```json
{ "channel": "chat-room-42", "type": "ack", "id": "msg-12345" }
```

Server → client: acknowledges that the host accepted the
frame for routing. For sender → device frames, ack fires
when the server has placed the frame on the device's queue.
For device → sender frames, ack fires when the server's
webhook delivery has either succeeded or exhausted retries
(see "Inbound webhook" below).

### Error

```json
{
  "channel": "chat-room-42",
  "type": "error",
  "id": "msg-12345",
  "payload": "<error_code>"
}
```

Error codes:
- `channel_not_open`
- `channel_limit_exceeded`
- `channel_name_invalid`
- `rate_limit_exceeded`
- `webhook_delivery_failed`
- `pairing_revoked`
- `invalid_frame`
- `payload_too_large`

### Ping / Pong

Heartbeat. Server pings every 30s; client must pong within
60s or the socket closes with code 4408 (`heartbeat_timeout`).

(Codex says 30s/90s is OK; Gemini suggests 20s/60s for
mobile NAT. V0.1 compromise: 30s ping / 60s pong deadline.
Tunable via config.)

## Inbound webhook (device → sender)

When a device sends a frame, the server forwards it to the
sender's registered webhook URL. The sender registers this
URL at manifest-publish time:

```
PluginPublishRequest {
  ...,
  live_inbound_url: "https://sender.example.com/live"  // V3 addition
}
```

Server signs each forwarded request with the Syncler-Server
Ed25519 key (registered + rotated via the existing
`server-public-key` admin path). The sender verifies the
signature before processing.

Forwarded request body:

```json
{
  "plugin_row_id": "...",
  "channel": "...",
  "envelope": "<base64 V2 envelope sealed for the sender>",
  "received_at": 1234567890,
  "device_pseudonym": "<opaque>"
}
```

`device_pseudonym` = HMAC(server-key, device_id || sender_id);
gives the sender a stable identifier per (device, sender) pair
without leaking the global device_id.

Delivery:
- POST with 5s timeout.
- 3 retries with exponential backoff (1s / 4s / 16s) on
  network errors or 5xx.
- On 2xx: server `ack`s the frame to the device.
- On exhausted retries: server `error`s the frame with
  `webhook_delivery_failed`.

## Rate limits

Per-socket:
- 64 KB max frame size (hard).
- 16 KB/s outbound (device → server) sustained; 64 KB burst
  via token bucket. Sustained violation (~10s over budget)
  closes the socket with code 4429
  (`rate_limit_exceeded_sustained`).
- 32 KB/s inbound (server → device) sustained; same burst.
- 10 concurrent open channels per socket.

Per-plugin (across all this device's sockets):
- Connection count cap: 4 sockets per plugin per device.
- Reconnect backoff: server gates same-token reconnects with
  a 1s minimum; client SDK uses jittered exponential backoff
  starting at 2s, max 30s (mentioned by gemini as missing).

## Cross-device fanout

A sender push (POST to
`/v1/live/plugin/{plugin_row_id}/push`) fans out to EVERY
device for the plugin's user that has an open WS for this
plugin row. The push body is a V2-shape envelope with one
`recipient_envelope` per active device's encryption key (no
new envelope shape — reuses the per-device sealing path).

The server routes by `plugin_row_id`; per-device decryption
keys mean each device sees only the recipient envelope
addressed to its key. Devices without an open WS for this
plugin row do NOT receive a backfill on next connect — they
catch up via the existing inbox pull if the sender chose to
persist the update as a regular message.

## Frame ordering + delivery semantics

- Within a single channel: best-effort per-connection
  ordering. Server delivers in the order it receives.
- Across channels: no ordering guarantee.
- Across reconnects: no replay. Devices that need
  ordered/durable delivery must use the inbox path AND the
  live channel together (sender writes both).

This is the "ephemeral live events" lane Codex 141 #10 asked
for. V3 #17 will introduce an opt-in "ordered/durable" lane
via Redis Streams — but the wire shape for #14 already
declares delivery as best-effort + unordered so plugins don't
build CRDT logic on top of `live.send()`.

## Native SDK ABI bump

`NATIVE_SDK_ABI` bumps `2 → 3` to expose:

```kotlin
interface PluginContext {
    // ... existing ABI 2 surface
    suspend fun liveConnect(channel: String): Result<LiveChannel>
}

interface LiveChannel {
    val incoming: Flow<ByteArray>
    suspend fun send(envelope: ByteArray): Result<Unit>
    suspend fun close()
}
```

`incoming` / `send` carry the OPAQUE envelope bytes — the SDK
doesn't do encryption; the plugin author is responsible for
sealing/opening using the V2 envelope helpers exposed
elsewhere in the SDK runtime.

V2-era plugins (ABI 2) keep working; the bridge gracefully
rejects `liveConnect` for them with `unsupported_sdk_abi`.

## JS SDK shape

```ts
const ch = await platform.live.connect("chat-room-42");
ch.onMessage((envelopeBytes) => { /* open + decrypt */ });
await ch.send(envelopeBytes);
await ch.close();
```

JS plugins running in the WebView path get the same multiplex
channel; the `platform.live` namespace is added in lockstep
with the native ABI bump.

`platform.live.subscribe(name, callback)` (V3 #15) is sugar:

```ts
platform.live.subscribe = (name, cb) =>
  platform.live.connect(name).then(ch => { ch.onMessage(cb); return ch; });
```

No separate wire shape, no separate triad — just SDK
ergonomics.

## Logging policy

- Device JWT: never logged.
- Connect token: never logged after it's verified once.
- `plugin_row_id`: logged at debug only, hashed at info+ (per
  codex 141 #4).
- Channel name: logged at debug only.
- Frame `id`: logged at info+ (it's plugin-generated, safe).
- Close codes: logged at info+.
- Webhook delivery errors: logged at warn+ with the sender's
  HTTP status; the request body is NEVER logged.

## Implementation order (V3 #14)

1. Server: `_BroadcastHub` interface + in-process impl, keyed
   by `plugin_row_id`. Designed to be replaced by a Redis-
   backed impl in #17 — the interface MUST distinguish
   ephemeral vs ordered/durable up front (codex 141 #10).
2. Server: `/v1/live/connect-token` endpoint (device JWT in,
   short token out).
3. Server: `/v1/live/plugin/{plugin_row_id}` WS endpoint with
   the auth + multiplex + heartbeat + rate-limit machinery.
4. Server: `/v1/live/plugin/{plugin_row_id}/push` sender POST
   endpoint (Ed25519-signed envelope).
5. Server: webhook forwarder (POST with retries + signing
   key + HMAC pseudonym).
6. Server: `PluginPublishRequest.live_inbound_url` schema
   field + migration.
7. Server: revocation event channel hooked into pairing-
   delete + device-revoke paths.
8. Server: tests for auth, multiplex, ordering, rate limits,
   webhook signing/verification, revocation close.
9. Android: WS client (OkHttp WebSocket) + multiplex
   coordinator + reconnect logic + heartbeat.
10. Android: `LiveBridge` exposing `platform.live.connect /
    onMessage / send / close` to the JS bridge dispatch
    table.
11. Android: native ABI 3 — `PluginContext.liveConnect` +
    `LiveChannel` data class wiring through `BridgePluginContext`.
12. SDK (TS): `platform.live.connect/subscribe` typed surface;
    manifest `liveInboundUrl` field.
13. Mid-track triad.

#15-#17 hooks:
- #15 SDK sugar lands inline with step 12.
- #16 (`card.patch`) needs its OWN design triad before any
  impl. Privacy invariant: patch payloads MUST use V2-style
  per-recipient envelopes — never plaintext field updates.
- #17 (Redis pub/sub vs Streams) needs its own design triad
  for the hub interface. The interface defined in step 1 must
  already split ephemeral pub/sub from durable streams so
  #17 doesn't force a contract break.

## Non-goals for V3 #14

- File transfer over WS — use the existing capability-handle
  path (V2 #10).
- Live audio/video — out of scope.
- Group channels (multi-sender per channel) — channel = one
  plugin row, one sender.
- Server-initiated channel close beyond
  pairing-revoked/heartbeat-timeout/rate-limit cases.
- Plugin → plugin direct messaging — must go via the sender.
