# Live Backplane — V3 #17 (Redis swap)

V3 #14 shipped the in-process BroadcastHub + EventBus +
connect-token store with the explicit promise that the
contracts could swap to a shared backplane without touching
call sites. V3 #17 cashes that promise: every component
behind those contracts gains a Redis-backed implementation
so multi-worker FastAPI deployments (and multi-pod
production) can fan out events across the whole fleet.

Spec triad: codex pre-work at consultation 147 (gemini
quota-blocked — codex-only design pass, follow-up review at
the post-work triad).

## Load-bearing sentence

**Redis is a live backplane, not durable application
storage. Postgres remains the source of truth for catch-up
and durable state.** Concretely: messages, live cards,
card_patches, pairings, plugins, senders, rotation state —
all Postgres. Connect tokens, ephemeral hub fan-out,
ordered-lane replay window, SSE hint events — all Redis.

## Scope — three in-process pieces swap

1. **BroadcastHub** (`server/app/live/hub.py`) — the V3 #14
   three-lane hub (ephemeral / ordered / control). Used by
   V3 #14 WS push + V3 #16 card.patch publish.
2. **EventBus** (`server/app/services/events.py`) — SSE
   fan-out keyed by `(user_id, device_id)`.
3. **Connect-token store** (`server/app/routers/live.py`) —
   `_TOKEN_STORE` dict that mints + redeems WS bearer
   tokens. Any worker must be able to redeem any other
   worker's mint.

Anti-requirements:
- **NOT** swapping durable application state to Redis.
- **NOT** introducing a Redis-backed durable inbox queue.
- **NOT** changing the wire contract between server ↔
  device / sender. The swap is purely server-side topology.

## Lane mapping

### Ephemeral lane — PUB/SUB

`publish_ephemeral(topic, message)` → Redis `PUBLISH live:eph:{topic}`.
`subscribe_ephemeral(topic)` → Redis `SUBSCRIBE live:eph:{topic}`
on the worker, fanning out to local subscriber queues with
the existing bounded-oldest-drop behavior.

Contract changes from the in-process impl:
- `delivered` return value is **best-effort telemetry**, not
  a delivered-to-sockets count. Redis `PUBLISH` returns
  "Redis subscribers", not "downstream device sockets".
- Reconnect gaps are expected (no replay).
- Local subscriber queue behavior is unchanged: bounded,
  oldest-drop on saturation.
- Topic names stay privacy-scoped:
  `user:{user_id}:plugin:{plugin_row_id}` — never a bare
  global plugin topic.

### Ordered lane — Redis Streams (with cursor mapping)

`publish_ordered(topic, message, cursor_id)` →
`XADD live:ord:{topic} MAXLEN ~ <N> * cursor_id <sender> message <json>`.

`subscribe_ordered(topic, since_cursor)`:
1. If `since_cursor` is None → start live only via XREAD `$`.
2. Otherwise: scan recent stream entries for an entry whose
   `cursor_id` field equals `since_cursor`; capture that
   stream ID `S*`; replay every entry with stream ID > `S*`
   then transition to live XREAD.
3. If `since_cursor` is not found in the retained window
   (trimmed away): return whatever tail is still in the
   stream + transition to live, AND log/metric the miss.
   Devices that need durability fall back to Postgres
   catch-up — the ordered lane is not authoritative state.

**Why cursor mapping**: today the interface uses
sender-assigned `cursor_id` strings. Redis Streams keys
replay by its own monotonic stream ID. We track both: the
sender `cursor_id` is a field inside each XADD entry; the
backplane's true position is the Redis stream ID.

Trim policy:
- `LIVE_ORDERED_STREAM_MAXLEN` env, default `1024`. (The
  in-process backend keeps the existing 256 cap — they're
  independent.)
- `MAXLEN ~` approximate trim on every XADD.

Cursor uniqueness:
- Required: `cursor_id` MUST be unique within a topic for
  the duration of the retention window. Duplicates make
  "since this cursor" ambiguous.
- Validation lives at the caller for v0.1 (no server-side
  uniqueness check); a duplicate produces unspecified
  replay behavior.

### Control lane — PUB/SUB

`publish_control(topic, event)` → `PUBLISH live:ctrl:{topic}`.
`subscribe_control(topic)` → `SUBSCRIBE live:ctrl:{topic}`.

V0.1 honesty:
- Control delivery is **best-effort**. A worker reconnecting
  its Redis subscription during a revocation can miss the
  event.
- Safety net: WS heartbeat timeout
  (`HEARTBEAT_PONG_DEADLINE_S = 60s`) closes the stale
  socket; the next connect-token mint goes through
  `current_auth_context` which rejects revoked devices.
  60-second worst case + immediate auth recheck on
  reconnect is acceptable.
- V0.2 upgrade path (if needed): Redis Streams with
  per-worker consumer groups, or DB-backed revocation
  epochs that the WS handler periodically re-reads.

## Connect-token store

Redis string with TTL:
- Mint: `SET live:token:<token> <json> EX 60 NX` (60s TTL,
  matches the in-process default; `NX` blocks duplicate
  inserts on the same opaque random 256-bit key — should
  never collide but the safety belt is cheap).
- Redeem: `GETDEL live:token:<token>` for single-use atomic
  consume. **Requires Redis 6.2+.** For older Redis, a Lua
  script wrapping `GET` + `DEL` provides the same atomicity.

Value shape (compact JSON):
```json
{"user_id": "...", "device_id": "...", "expires_at_epoch_s": 1234567890}
```

`plugin_row_id` is NOT bound into the token today — the
WS endpoint takes plugin_row_id from the URL path and
revalidates pairing. Adding plugin binding here would tie
tokens to a single plugin, which the current API doesn't.

Privacy: tokens are NEVER logged after their first
verification — `logger.info` only logs presence/absence,
not the token value.

Failure: Redis unreachable → mint endpoint 503, redeem
returns "invalid token" (which closes the WS handshake at
the bearer-check stage).

## EventBus

The SSE fan-out swap has three parts.

### Event IDs

The current in-process `_next_id: int` would collide across
workers. **Do not paper over this with `INCR live:sse:next-id`** — that buys
uniqueness without buying the replay that the SSE
`Last-Event-ID` header would imply.

V0.1 IDs:
- Shape: `{epoch_ms}-{worker_id}-{seq_within_ms}` —
  collision-resistant, lexicographically sortable for
  client-side debugging.
- Documented contract: SSE event IDs are **diagnostic /
  resume markers only**. There is no `Last-Event-ID`
  replay guarantee in the v0.1 Redis-PUB/SUB-backed bus.
  The inbox REST pull is authoritative for catch-up.

### Per-user fan-out

- Each worker subscribes to `sse:user:{user_id}` for every
  user that has at least one active local SSE subscriber on
  that worker.
- Subscription is **reference-counted** by local
  subscriber count — first subscriber opens the Redis
  subscription, last unsubscribe closes it.
- One Redis reader task per worker per active user.
- The reader task fans out received messages to the local
  `_Subscriber.queue` set with the existing bounded
  oldest-drop behavior.

`publish_to_user(user_id, event_type, data)`:
- Builds the event JSON (with the new ID shape above).
- `PUBLISH sse:user:{user_id} <json>`.
- Return is best-effort telemetry; doesn't reflect actual
  fan-out across workers.

### Device-close control channel

Single shared channel `sse:device-close` (not one per
device — channel proliferation isn't worth the dispatch
savings for the small number of revocations).

`close_device_subscribers(device_id)`:
- `PUBLISH sse:device-close <json>` with payload
  `{"device_id": "<uuid>", "reason": "revoked"}`.
- Every worker has a single ambient subscription to this
  channel; on receipt, local filter for matching device IDs
  and push `None` sentinel into the relevant queues
  (existing semantics).

## Configuration

ONE global flag, not per-component:

```
LIVE_BACKPLANE=memory|redis   (default: memory)
REDIS_URL=redis://host:port/db (required if LIVE_BACKPLANE=redis)
```

Optional tuning:
```
LIVE_ORDERED_STREAM_MAXLEN=1024
LIVE_REDIS_KEY_PREFIX="" (for shared Redis envs)
```

Per-component flags (`LIVE_HUB_BACKEND` /
`EVENT_BUS_BACKEND` / `TOKEN_STORE_BACKEND`) are
**rejected**. Mixed backends (Redis hub + memory token
store) pass some tests but break under multi-worker
routing.

Startup behavior:
- `LIVE_BACKPLANE=redis` → validate Redis connectivity at
  startup. If unreachable, fail to start (loud failure,
  early).
- `environment=production` + `LIVE_BACKPLANE=memory` →
  loud warning at startup. (Hard refusal is V0.2 once
  the env is consistently flagged.)

## Failure modes — fail closed

Redis unavailable mid-flight:
- **Connect-token mint** → 503.
- **Connect-token redeem** → invalid-token rejection at
  WS handshake.
- **WS hub subscribe** → fail the WS open with a 4503
  close code.
- **SSE stream open** → 503.
- **Hub publish** → log the failure; do NOT silently
  succeed. The endpoint that triggered the publish
  surfaces a 5xx or a warning depending on the caller's
  expectations.

No automatic memory fallback. The Redis backend is a hard
dependency once configured.

## Migration order

Three components, deployed in this canary order:

1. **Connect-token store** — smallest surface, least
   state, easiest to validate. A flaky Redis here only
   blocks new WS connections; existing sockets keep
   running.
2. **EventBus (SSE fan-out)** — SSE is already
   tolerant of dropped events (clients re-pull on
   resume). Failure scope: stale inbox indicators
   until next pull.
3. **BroadcastHub** — last because the ordered lane has
   the most subtle replay semantics. Validate Streams
   behavior under real load before this swap.

Constraint: **do not run a mixed-backend rolling
deploy**. A token minted by a Redis-backed worker
cannot be redeemed by a memory-backed worker. Drain
all sockets to the same backend, or accept a 60-second
reconnect blip during the cutover.

## Observability — first-class

Counters:
- `live_redis_publish_attempts_total{lane}`
- `live_redis_publish_failures_total{lane}`
- `live_redis_messages_received_total{lane}`
- `live_local_fanout_total{lane}`
- `live_local_queue_drops_total{lane}`
- `live_token_mints_total{result}`
- `live_token_redeems_total{result}`
- `live_redis_subscription_reconnects_total`

Gauges:
- `live_local_sse_subscribers_active`
- `live_local_ws_subscribers_active`
- `live_redis_topic_subscriptions_active`
- `live_ordered_stream_length{topic}` (cheap when sampled)

Logs:
- WARN: Redis unavailable on startup or mid-flight.
- WARN: subscription task restart.
- WARN: malformed payload received from Redis.
- INFO: ordered cursor trimmed away (cursor + topic).

Privacy:
- Tokens NEVER logged after first verify.
- Sealed envelope payloads NEVER logged.
- Channel names + plugin_row_ids logged at debug only;
  hashed at info+ (mirrors V3 #14 logging policy).
- Raw Redis payloads NEVER logged.

## Implementation order (V3 #17)

1. **Spec digest (this file)** + add `REDIS_URL`,
   `LIVE_BACKPLANE`, `LIVE_ORDERED_STREAM_MAXLEN`,
   `LIVE_REDIS_KEY_PREFIX` to `app/config.py`. Add
   `redis>=5.0` to `pyproject.toml`. Add Redis service to
   `docker-compose.yml` for local dev.
2. **Redis client factory** — `app/redis_client.py` with
   `get_redis()` async, connection-pool managed. Used by
   all three components.
3. **Connect-token RedisStore** — implement against the
   existing `_TOKEN_STORE` shape. Mint/redeem/expire.
   Wire `LIVE_BACKPLANE` gate.
4. **EventBus RedisBackend** — per-user PUB/SUB, ref-
   counted subscriptions, device-close channel. New ID
   shape. Wire gate.
5. **BroadcastHub RedisBackend** — three lanes
   (PUB/SUB ephemeral, Streams ordered with cursor
   resolution, PUB/SUB control). Per-worker subscription
   registry with ref counts. Wire gate.
6. **Startup connectivity check** — when
   `LIVE_BACKPLANE=redis`, ping Redis on app start; fail
   loud if unreachable.
7. **Observability** — wire the counters/gauges above.
   Prometheus integration if already in the codebase;
   structured logs otherwise.
8. **Tests** —
   - Token mint/redeem across two worker processes.
   - SSE publish on worker_A delivered to subscriber on
     worker_B.
   - Hub ordered-lane replay-by-cursor with trim.
   - Fail-closed behavior with Redis down.
   - Mixed-backend startup refusal in production env.
9. **Docs** — update `docs/live-channel.md` cross-
   reference; update `docs/ROADMAP.md` #17 status.
10. **Post-work triad** — spec + commits review.

## Non-goals for V3 #17

- Redis Sentinel / Cluster HA — V0.2.
- Hard refusal of `LIVE_BACKPLANE=memory` in production —
  V0.2 (warn-only for V0.1).
- Backplane-driven Last-Event-ID replay for SSE — would
  require swapping SSE PUB/SUB for Streams, semantic
  change, V0.2 candidate.
- Per-plugin or per-user Redis quotas — V0.2.
- Multi-region replication — V0.2.

## Privacy invariants

- Channel name patterns:
  - `live:eph:user:{user_id}:plugin:{plugin_row_id}` for WS push.
  - `live:eph:control:pairing-revocation` for the control lane.
  - `sse:user:{user_id}` for SSE fan-out.
  - `sse:device-close` for SSE close events.
  Never a bare plugin-id topic that crosses users.
- Sealed envelopes ride opaque through Redis. The
  backplane sees only the JSON bytes the caller produced.
- Connect-token values are never logged after the first
  verification.
- Hub payloads are stringly-typed; the backplane never
  parses them.
