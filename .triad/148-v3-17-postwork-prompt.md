=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 148 — V3 #17 post-work review

Triad 147 was the pre-work design pass for V3 #17 (live
backplane swap to Redis). Gemini was quota-blocked on
that pass — codex-only design. Spec landed at
`docs/live-backplane.md`. All 8 implementation steps have
shipped across 4 commits.

v0.1 dev posture preserved. PG dev box still flaky; the
server pytest run is blocked the same as V3 #14 + #16.

## Commits in scope

| Commit  | Steps  | Content |
|---------|--------|---------|
| 9e2953f | 1 (spec) | docs/live-backplane.md (post-triad 147) |
| 0c54f6f | 1,2,3  | config + REDIS_URL + LIVE_BACKPLANE + redis dep + docker-compose + redis_client.py + token_store.py |
| 7d84e1c | 4      | InProcessEventBus + RedisEventBus + new event-ID shape |
| 83a62fb | 5      | RedisBroadcastHub (PUB/SUB ephemeral + Streams ordered + PUB/SUB control) |
| 77e74b0 | 6,8    | Startup connectivity ping + unit tests (token store + event-ID) + ROADMAP #17 → shipped |

## Files in primary review scope

### Config + plumbing

- `server/app/config.py` — added `LIVE_BACKPLANE`, `REDIS_URL`,
  `LIVE_ORDERED_STREAM_MAXLEN`, `LIVE_REDIS_KEY_PREFIX`.
  Per-component flags explicitly rejected (codex 147 FIX #10).
- `server/app/redis_client.py` — lazy `get_redis()` pool
  (max 32 conns, 5s connect / 10s op timeouts,
  `decode_responses=False`), `ensure_connected_or_raise()`
  for startup ping, `prefixed()` for shared-Redis namespacing.
- `server/docker-compose.yml` — redis:7-alpine on host
  port 6380.
- `server/pyproject.toml` — `redis==5.2.1`.

### Connect-token store

- `server/app/live/token_store.py` — `ConnectToken`
  dataclass + `TokenStore` Protocol + `InProcessTokenStore`
  (V0.1 dict + purge-on-access shape) +
  `RedisTokenStore` (`SET EX 60 NX` mint + `GETDEL` atomic
  redeem, with a Lua `GET+DEL` fallback for Redis <6.2).
  `get_token_store()` dispatches on `LIVE_BACKPLANE`.
- `server/app/routers/live.py` — handler now uses the
  factory; `mint_connect_token`/`redeem_connect_token`/
  `_reset_store_for_test` became async to match the
  Redis backend.
- `server/tests/test_v3_14_connect_token.py` +
  `test_v3_14_ws_endpoint.py` — `await`s updated.
- `server/tests/test_v3_17_token_store.py` — focused unit
  tests for the in-process backend + factory dispatch.

### EventBus

- `server/app/services/events.py`:
  - `_next_event_id()` — new ID shape
    `{epoch_ms}-{worker_pid_hex}-{seq_within_ms}` (codex 147 FIX #5).
  - `EventBus` (Protocol) + `InProcessEventBus` (renamed
    from the V1 `EventBus` class) + `RedisEventBus`.
  - `RedisEventBus`:
    - Per-user `sse:user:{user_id}` PUB/SUB channels,
      ref-counted per worker by local subscriber count.
    - Single shared `sse:device-close` PUB/SUB channel.
    - `publish_to_user` returns Redis subscriber count
      (best-effort telemetry, NOT downstream device count).
    - Reader loops mirror in-process bounded oldest-drop
      saturation behavior on local queues.
  - `get_event_bus()` lazily dispatches on `LIVE_BACKPLANE`.
- `server/tests/test_events.py` — `EventBus = InProcessEventBus`
  back-compat alias so existing tests keep working.
- `server/tests/test_v3_17_event_id.py` — new ID shape
  tests.

### BroadcastHub

- `server/app/live/hub_redis.py` — `RedisBroadcastHub`
  implementing the V3 #14 BroadcastHub Protocol byte-for-
  byte:
  - **Ephemeral lane**: PUB/SUB on `live:eph:{topic}`.
    Per-worker subscriptions ref-counted.
  - **Control lane**: PUB/SUB on `live:ctrl:{topic}`.
    Same ref-counted shape as ephemeral.
  - **Ordered lane**: Redis Streams (`XADD MAXLEN ~ N`
    cursor_id <sender> message <json>`). Each subscriber
    gets its own XREAD reader task (per-subscriber cursor
    state). since_cursor resolution is an XRANGE-scan for
    the matching `cursor_id` FIELD, then XRANGE replay
    after the matched stream ID, then live XREAD. Trimmed
    cursors log a warning and fall back to live-only
    (codex 147 DESIGN #2).
- `server/app/live/hub.py` — factory now lazily constructs
  Redis or InProcess based on `LIVE_BACKPLANE`.

### Startup

- `server/app/main.py` — lifespan calls
  `ensure_connected_or_raise()` when
  `LIVE_BACKPLANE=redis`, closes the Redis client on
  shutdown, loud-warns when `environment=production` +
  `LIVE_BACKPLANE=memory`.

## Privacy / contract claims

1. **Outer wire contract unchanged.** No client (sender,
   device) sees any difference between memory and Redis
   backends. The swap is purely server-side topology.
2. **Topic naming preserves V3 #14 privacy fix.** All
   user-scoped fan-out keys are
   `user:{user_id}:plugin:{plugin_row_id}` namespaced
   under the `live:eph:` / `live:ctrl:` prefixes.
   `sse:user:{user_id}` for SSE. Never a bare global
   plugin topic.
3. **Sealed envelopes ride opaque** through Redis.
   Backplane sees JSON bytes the caller produced; no
   payload decoding ever happens on the backplane path.
4. **Tokens never logged** after first verification —
   `logger.info` on mint/redeem only logs presence /
   counts, never the token value.
5. **Fail-closed** on Redis unavailable: startup ping
   raises (fails app boot), runtime errors propagate
   through publish/redeem to the route handler.
6. **No silent memory fallback** when `LIVE_BACKPLANE=redis` —
   spec invariant.

## Specific concerns I'd like flagged

1. **Ordered-lane `_resolve_since_cursor` XRANGE-walks
   the whole stream.** For v0.1 stream cap = 1024
   entries this is fine but on large streams it's
   linear. Worth XREVRANGE early-exit, or v0.1 OK?

2. **Reader loops use `pubsub.aclose()` in finally
   blocks** but I haven't validated that the
   `redis-py 5.2.1` pubsub object actually has `aclose()`
   on all paths — if it's `close()` (sync) the cleanup
   leaks. Worth a runtime check.

3. **`RedisEventBus` per-user reader task is cancelled
   when the local subscriber count hits 0.** If two
   subscribers for the same user arrive in close
   succession on the same worker, the second one races
   the cancel? The lock around the registry mutation
   should serialize that — confirm or flag.

4. **`RedisBroadcastHub.publish_*` returns the Redis
   `PUBLISH` count.** Some V3 #14 callers (live.py push
   endpoint, V3 #16 patch endpoint) use the return value
   as "delivery count" — has the semantic drift been
   audited? I think they just pass it through to telemetry
   but worth a spot-check.

5. **Ordered-lane cursor uniqueness is caller-enforced**
   (spec says "required per topic in the retention
   window"). The hub does NO validation; a duplicate
   cursor in the same window produces undefined replay.
   Acceptable for v0.1?

6. **`prefixed(...)` is a thin string concat.** No
   guard against a prefix that ends with `:` already, or
   one that contains spaces. Worth a `.strip()` /
   normalize?

7. **`_next_event_id` uses an asyncio.Lock at module
   scope.** Locks are bound to an event loop on first
   use; if the FastAPI app uses multiple loops (uvloop
   per worker), the lock is fine PER worker. But if any
   test reuses one ID generator across loops it could
   crash. Worth flagging.

8. **`InProcessEventBus` is now constructed lazily by
   `get_event_bus()` instead of at module import.** That
   could change test ordering — earlier tests that relied
   on module-import-time construction now get a fresh bus
   on first access. Any test that grabbed
   `events._bus` directly would break (none found in
   grep, but worth a heads-up).

9. **`Settings.live_backplane` is `Literal["memory",
   "redis"]`.** Pydantic enforces enum validity at parse
   time — but the `extra="ignore"` posture means a typo
   in `LIVE_BACKPLANE=Redis` (capital R) parses as
   invalid → exception. Acceptable, but worth confirming
   the fail mode is the loud kind (raises at startup,
   not silent default to memory).

10. **`docker-compose.yml` Redis exposes 6380 on the
    host** (to avoid clashing with any local Redis on
    6379). The `REDIS_URL` default in config points at
    `localhost:6379`. So docker-compose + the default
    REDIS_URL mismatch. Intentional (force explicit
    REDIS_URL=…:6380 in dev) or a bug?

11. **`docs/live-backplane.md` "Migration order" says no
    mixed-backend rolling deploy.** No CI gate enforces
    this — a careless deploy could mix. Acceptable for
    v0.1 (deployment is single-host) or worth a CI gate?

12. **Server pytest is still PG-blocked** so the
    integration story (token cross-worker, SSE fan-out
    cross-worker, Streams replay) isn't proven yet —
    it's logic-only review. Worth flagging that this
    is the THIRD time in a row (V3 #14, #16, #17) we
    ship behind the PG dev-box outage.

## Test status

```
:app:assembleDebug                              — green
:feature:plugin-host:testDebugUnitTest          — green
:feature:inbox:testDebugUnitTest                — green
server py_compile  (all modified files)          — green
sdk-python py_compile                            — green
server pytest                                    — BLOCKED on PG dev-env (same as V3 #14/#16)
```

## What I'm asking for

Per-numbered-concern verdict (OK / NIT / FIX / DESIGN).
Plus anything you spot in the commits' diffs that's
load-bearing.

Focus on:
- Contract preservation across memory ↔ Redis backends
  (publish_* shape, subscribe_* shape, sequence
  guarantees).
- Privacy invariants (no payload decoding on backplane,
  no token logging, topic naming).
- Fail-closed posture for Redis unavailable.
- Cursor-mapping correctness on the ordered lane.
- Reader-loop lifecycle / cancellation correctness.

Skip cosmetics; flag substance. Goal: greenlight V4 (or
a clear FIX list to apply first).
