=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 147 — V3 #17 design pre-work

V3 #17 in `docs/ROADMAP.md`:

> 17. Redis pub/sub for SSE scaling. Move the in-process
>     event bus to a shared backplane so the SSE fanout works
>     across multiple FastAPI workers and pods.

…plus the V3 #14 hub interface (`server/app/live/hub.py`) was
designed up front to be Redis-swappable. So #17 actually
covers three "in-process → shared backplane" swaps:

1. **EventBus** (`server/app/services/events.py`) — SSE
   fan-out keyed by (user_id, device_id). Today's
   `publish_to_user` + `close_device_subscribers` live in
   a single process.
2. **BroadcastHub** (`server/app/live/hub.py`) — the three-
   lane WS / live-channel hub from V3 #14 (ephemeral,
   ordered with replay, control). Used by V3 #14 push and
   V3 #16 card.patch publish.
3. **Connect-token store** (`server/app/routers/live.py`) —
   currently `_TOKEN_STORE` dict + `_purge_expired_locked`.
   Any WS worker must be able to redeem a token minted by
   any other worker.

v0.1 dev posture preserved. Postgres dev box still flaky;
Redis is a new infra dep so its dev-env story matters too.

## What's already in place (V0.1)

```
server/app/live/hub.py:
  Protocol BroadcastHub:
    - publish_ephemeral / subscribe_ephemeral
    - publish_ordered (cursor_id-keyed) / subscribe_ordered (since_cursor)
    - publish_control / subscribe_control
  InProcessBroadcastHub:
    - EPHEMERAL_QUEUE_CAP = 64
    - ORDERED_QUEUE_CAP = 128
    - ORDERED_REPLAY_CAP = 256 (ring buffer per topic)
    - oldest-drop on saturation
    - single asyncio.Lock for subscriber-map mutations
  Topic shapes today:
    plugin:{plugin_row_id}              — global (deprecated, replaced)
    user:{user_id}:plugin:{plugin_row_id} — V3 #14 fan-out (privacy)
    control:pairing-revocation           — control lane

server/app/services/events.py:
  EventBus: in-process dict[user_id -> set[_Subscriber]]
            and dict[device_id -> set[_Subscriber]]
  publish_to_user(user_id, event_type, data) — fan-out across user's devices
  close_device_subscribers(device_id) — sentinel-pushes None
  Event id is a monotonic int per process (will collide across workers)

server/app/routers/live.py:
  _TOKEN_STORE: dict[str, tuple[user_id, device_id, plugin_row_id, expires_at]]
  _purge_expired_locked sweep on mint/redeem
```

## Specific design questions

### Lane mapping

For each of the three hub lanes, what's the right Redis primitive?

- **Ephemeral.** Best-effort, no replay, unordered across subscribers.
  Candidates: PUB/SUB; Streams without consumer groups; Streams with
  XADD MAXLEN ~ small. PUB/SUB is the obvious answer (no
  persistence, no consumer state). Confirm? Edge cases:
  reconnecting subscriber that briefly disconnects loses
  in-flight messages — fine for ephemeral (presence etc.)
  but worth flagging.

- **Ordered with replay since cursor.** Today: per-topic ring
  of 256, cursor_id is a string (sender-assigned). Map to
  Redis Streams: XADD topic * cursor_id <value>, XREAD with
  $ for live, XRANGE for since-cursor backfill. Q: should
  the stream MAXLEN cap match the in-process 256, or expose
  it as config? V3 #17 catch-up flows (V3 #16 patches) want
  more headroom — patches are persisted in Postgres
  (card_patches table) so the stream can be smaller.

- **Control.** Today: best-effort, no replay. PUB/SUB obvious
  fit. The revocation event MUST land at every WS worker; a
  worker that misses the publish keeps a doomed socket open
  until heartbeat times out. For V0.1 acceptable (60s
  timeout caps the window). For V0.2 needs Streams with
  per-worker consumer groups? Or accept "PUB/SUB +
  heartbeat-timeout fallback" forever?

### Connect-token store

Token is a 256-bit opaque random string mapped to
(user_id, device_id, plugin_row_id, expires_at, ~60s TTL).

Two obvious choices:
- **Redis string + SETEX/GET/DEL**: `live:token:<token>` →
  JSON blob, with TTL == ~60s. Redeem is GET + DEL (or
  GETDEL for atomic). Multiple workers can mint and redeem
  freely.
- **Redis hash + EXPIREAT**: same shape, slightly noisier
  but lets us inspect tokens by field. Probably overkill.

Going with SETEX/GETDEL unless someone has a stronger
reason. Confirm?

### EventBus → Redis

The current `_next_id` monotonic int is process-local; SSE
event IDs would collide across workers. Two options:

- **Redis INCR for a shared counter**: every publish_to_user
  does an INCR; cheap but introduces a Redis round-trip on
  every publish.
- **Snowflake-ish IDs**: `{epoch_ms}-{worker_id}-{seq}` —
  no Redis dep, lexicographically sortable for SSE Last-
  Event-ID semantics. Slightly more code.

Which buys more honesty?

For the fan-out itself: every worker subscribes to a Redis
PUB/SUB channel like `sse:user:{user_id}`; publish_to_user
maps to PUBLISH on that channel. Each worker holds an
asyncio.Queue per local subscriber; the PUB/SUB consumer
task fan-outs to its local subscriber set. Standard
pattern; calling it out for review.

`close_device_subscribers` (sentinel-push None): need a
control channel `sse:device-close:{device_id}` that every
worker subscribes to. Worker that owns the device's SSE
stream sees the PUBLISH, drops the sentinel into the
local queue.

### Redis topology

V0.1 dev posture: single Redis instance on localhost.
Production "Redis cluster vs Sentinel vs single+replica":
- single+replica with manual failover is enough for V0.1.
- Codex/Gemini: any objections to deferring HA story to V2?

### Failure modes

When Redis is unavailable:
- **fail-closed**: the live-channel WS can't accept new
  connections, the connect-token endpoint 503's, and the
  SSE bus errors out. Senders + devices see honest
  failures.
- **fail-open with in-process degradation**: each worker
  falls back to in-process state; multi-worker scenarios
  break silently.

I lean fail-closed for v0.1 (we control deployment;
silent broken multi-worker is worse than visible
downtime). Confirm?

### Config gate

Adding a `LIVE_HUB_BACKEND={memory|redis}` env var (and
similarly for the event bus and token store) so:
- Tests + local dev keep using `memory`.
- Production sets `redis` + REDIS_URL.

Per-component flag or one global toggle? Per-component is
more flexible but easier to misconfigure.

### Migration strategy

Three components to swap. Order matters for canary:
1. Connect-token first (smallest surface, least state).
2. EventBus next (SSE fan-out — already tolerant of dropped
   events; reverse-proxied as opaque).
3. BroadcastHub last (ordered lane has the most subtle
   replay semantics; want to validate Streams behavior
   under real load before this swap).

Or all-at-once with a single deploy? Recommendation?

### Multi-worker WS specifics

If user A has WS open on worker_1 and a card.patch publish
lands on worker_2, the publish must fan out to worker_1
via PUB/SUB. Worker_2 calls `hub.publish_ephemeral(topic,
envelope)` which PUBLISHes to a Redis channel; worker_1's
subscription delivers it to the local WS subscriber.

Q: heartbeat liveness is per-socket on the holding worker.
A worker that crashes mid-flight strands its sockets; OS
will close them but the device may briefly see "no
delivery" before reconnecting. That's already true today;
flagging that nothing in V3 #17 makes it worse.

## Anti-requirements

- **NOT swapping** durable application state (Postgres tables) to Redis. Sender records, plugins, pairings, live cards, card_patches all stay in PG.
- **NOT introducing** a Redis-backed durable inbox queue. Inbox catch-up still pulls from Postgres.
- **NOT changing** the wire contract between server ↔ device / sender. This is purely a server-side topology swap; clients don't notice.

## What I'm asking for

For each numbered design question above:
- Your recommendation.
- The biggest risk you see in my proposed answer.
- Anything load-bearing the spec needs to nail down BEFORE I implement (privacy, ordering, failover, observability).

Then, if you'd take a different overall shape (e.g. "split
the hub into separate per-lane interfaces", "don't use
Streams at all for ordered — use list+blocking-pop"),
say so up front.

Format: numbered verdicts (OK / NIT / FIX / DESIGN) +
the bigger-picture take at the end.

Goal: a concrete spec for `docs/live-backplane.md` (working
title) I can implement against. v0.1 scope; production HA
deferred to V2 unless you flag it as a must.
