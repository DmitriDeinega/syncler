=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 141 — V3 design (two-way + scaling track)

V2 closed at 87ee587 (closeout triad 140). V3 is next per
ROADMAP — four items that collectively flip the host↔sender
data path from "pull + SSE hints" to "push-friendly two-way
channels."

Same v0.1 dev posture: "we still developing on my local pc.
so everything is v0.1."

Both reviewers in triad 140 recommended a design triad for
#14 (WebSocket) FIRST, with #15-#17 as follow-on tracks. This
prompt focuses on #14's contract and explicitly frames the
hook points for #15-#17 without locking their detailed designs.

## V3 items (from ROADMAP)

- **#14 WebSocket two-way channel.** Bidirectional, low-
  latency channel between plugins and their backends. Covers
  chat, live cursors, presence. Complements but does not
  replace SSE.
- **#15 `platform.live.subscribe(...)` for script plugins.**
  Script bundle subscribes to a named feed; gets incremental
  updates without polling. Likely built ON TOP of #14.
- **#16 Field-level live subscriptions on templates.** Update
  specific template fields without re-rendering the whole
  card. Today's `live_cards` path is whole-card upsert; this
  adds finer grain.
- **#17 Redis pub/sub for SSE scaling.** Move the in-process
  event bus to a shared backplane so SSE (and now WS) fanout
  works across multiple FastAPI workers / pods.

## V3 #14 design proposal — WebSocket two-way channel

### Auth model

Mirror the V2 inbox path: device JWT in the WS connect URL or
Sec-WebSocket-Protocol subprotocol header. The server
verifies + binds the socket to `(user_id, device_id)`.
Plugins authenticate by `plugin_row_id` in the path; the
server checks that the plugin row is active + paired with
this user.

Open question: how do we prevent a device from spoofing
`plugin_row_id` to listen on another user's plugin channel?
Server checks the plugin_row's sender_id matches a sender
paired with THIS user. (Existing pairing-table join.)

### Endpoint shape

```
GET /v1/live/plugin/{plugin_row_id}
Sec-WebSocket-Protocol: syncler-jwt-bearer-<device_jwt>
```

Server accepts, validates the JWT + pairing, holds the socket.
Inbound frames from plugin → forward to sender via a
sender-registered webhook (which the sender publishes
alongside the manifest signing/bootstrap keys).
Outbound frames from sender → POST to
`/v1/live/plugin/{plugin_row_id}/push` (signed envelope same
shape as `/v1/messages/send`), server fans out to every
connected device for that plugin row.

### Server-side fanout

V0.1: in-process `_BroadcastHub` keyed by `plugin_row_id`,
`dict[plugin_row_id, set[asyncio.Queue]]`. Per-queue cap with
oldest-drop on overflow.

V0.2 (= V3 #17): swap `_BroadcastHub` for a Redis-backed
implementation. Same interface; the WS handler doesn't change.

### Plugin (device-side) API

JS `platform.live`:

```ts
const ch = await platform.live.connect("my-channel");
ch.onMessage((msg) => { ... });
await ch.send({...});
await ch.close();
```

Native (NATIVE_SDK_ABI bump to 3?):

```kotlin
suspend fun liveConnect(channel: String): Result<LiveChannel>
interface LiveChannel {
    val incoming: Flow<String>
    suspend fun send(message: String): Result<Unit>
    suspend fun close()
}
```

Behind the scenes: the host opens ONE WebSocket per
`(device, plugin_row)` and multiplexes channels by name. The
plugin's "channel name" is just an in-band tag the sender +
plugin both understand.

### Bandwidth + caps

- 64 KB max message size.
- 10 concurrent open channels per plugin.
- Per-plugin throughput cap: 16 KB/s outbound, soft. Going
  over closes the WS with `policy_violation`.
- Heartbeat: ping every 30s, fail-close after 90s no pong.

### Encryption

The WS payload is ALREADY end-to-end encrypted by the sender
(same V2 envelope shape) before it crosses the wire. Server is
a dumb pipe. Same model as `/v1/messages/send` + the existing
inbox path.

Open question: do we want per-message Ed25519 signatures, or
is the WS upgrade auth + handshake enough? V0.1 lean: rely on
the envelope's existing per-payload signature (recipient
classifier already verifies); the WS just routes.

### Disconnect / reconnect

Standard WS close codes + exponential backoff client-side.
Server drops the queue subscription on disconnect; the device
state catches up on reconnect via the existing inbox pull (no
backfill via WS).

### #15 hook (forward-compat note)

`platform.live.subscribe(name, callback)` is a thin wrapper
over `platform.live.connect(name)` + onMessage. No new wire
shape — just SDK ergonomics. Doesn't need a separate triad
unless the wire contract changes.

### #16 hook (forward-compat note)

Field-level updates on template cards become a special
message shape on the live channel:

```json
{
  "type": "card.patch",
  "plugin_row_id": "...",
  "card_id": "...",
  "field": "$.subtitle",
  "value": "Updated"
}
```

The host's existing template renderer gains a patch path that
sets the field's resolved value without re-running the full
JSONPath resolver. Whole-card upsert still works alongside.

### #17 hook (forward-compat note)

`_BroadcastHub` is an interface; swap the in-process impl
for a Redis-backed one keyed by `plugin_row_id`. Plugins +
clients don't change.

## Concerns I want a second opinion on

1. **Subprotocol vs query-param JWT.** Subprotocol header
   keeps the JWT out of access logs but requires careful
   browser support (not a concern for native clients).
   Query-param is simpler but logs the JWT. V0.1 leans
   subprotocol; worth it?

2. **One WS per (device, plugin_row) vs per channel.** Multi-
   plexing keeps the connection count bounded for a device
   loading multiple channels; per-channel is simpler. V0.1
   leans multiplex; agree?

3. **Sender webhook for inbound.** Plugin → sender direction
   requires a webhook URL the sender publishes. That's a new
   piece of sender infrastructure. Worth requiring, or should
   V0.1 only support sender → plugin (one-way push)?

4. **Encryption layering.** WS payload is already E2EE'd by
   the V2 envelope, so the WS frame itself doesn't need
   transport encryption beyond TLS. But the WS handshake
   leaks the plugin_row_id in the URL. Acceptable
   metadata leak, or is the plugin_row_id sensitive enough
   to warrant per-channel ephemeral IDs?

5. **Throughput cap (16 KB/s outbound).** Soft cap with WS
   close on violation. Pick a number that lets chat work but
   not file uploads. Too tight? Too loose?

6. **Heartbeat interval.** 30s ping / 90s fail-close. Cellular
   carriers sometimes drop sockets after 60s idle. Tighter?

7. **Auth + pairing check timing.** Server verifies JWT +
   sender-pairing on connect, then trusts the socket for its
   lifetime. If the user revokes the pairing mid-session, the
   socket stays open until the next heartbeat (then dies
   gracefully). Acceptable, or should the server poll the
   pairing table periodically?

8. **Native SDK ABI bump.** NATIVE_SDK_ABI = 3 if liveConnect
   ships in this phase. Phase 11-12 native plugins (ABI 2)
   would need re-publish. V0.1 has no shipped native plugins,
   so this is free. Confirm the bump is the right move
   (vs. shipping liveConnect as a feature-flagged extension)?

9. **#16 templating split**. `card.patch` lets a sender push
   targeted field updates without a full envelope re-publish.
   But the V2 envelope contract requires `recipient_envelopes`
   to be fresh for every payload. If the patch path bypasses
   re-encrypting the full payload, what's the privacy contract?
   Maybe `card.patch` requires its own mini-envelope (per-
   recipient encrypted patch payload), or maybe it's only for
   non-private template fields. Worth a dedicated triad before
   implementation?

10. **#17 ordering.** When the in-process bus moves to Redis,
    ordering guarantees relax (Redis pub/sub is fire-and-
    forget, no per-subscriber sequencing). Plugins counting
    on monotonic delivery (e.g. CRDT-style sync) would break.
    Is that acceptable for V3, or should #17 ship with a
    stream-based (Redis Streams / XADD) implementation that
    preserves ordering per channel?

## What I'm asking for

For V3 #14 specifically:
- For each numbered concern, a verdict (OK / NIT / FIX /
  DESIGN) + recommendation.
- Anything missing from the design that you'd want to see
  before implementation starts.

For #15-#17:
- Confirm or push back on the proposed hook points (are they
  enough?).
- Flag any item that needs its own design triad before V3
  starts (vs. follow-on triads after #14 lands).

Focus on:
- Auth correctness (JWT + pairing + plugin_row_id binding).
- E2EE invariants (does anything in the WS path weaken what
  V2's per-device envelopes guarantee?).
- Scaling realities (per-process queues, per-device sockets,
  the Redis swap path).
- Backwards compat (NATIVE_SDK_ABI bump, JS SDK shape).

Skip cosmetics; flag substance.
