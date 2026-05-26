=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 144 — V3 #14 post-work review

Triad 141 was the pre-work design pass for V3 #14
(`docs/live-channel.md`). All 12 implementation steps have
shipped across 5 commits; step 13 of the spec IS this
post-work review.

v0.1 dev posture preserved.

## Commits in scope

| Commit  | Steps | Content |
|---------|-------|---------|
| 993cfed | 1     | BroadcastHub interface + in-process impl (ephemeral / ordered / control lanes) |
| b312fee | 2     | /v1/live/connect-token endpoint (mints short-lived opaque tokens) |
| ccc474e | 3     | WS endpoint with auth, multiplex, heartbeat, rate-limit |
| cdb7c75 | 4-7   | sender push POST + webhook forwarder + schema/migration + revocation event channel |
| 1c51883 | 9-12  | Android WS client + LiveBridge + NATIVE_SDK_ABI=3 + SDK shape |

Step 8 (full server tests) is partial — minimal smoke tests
in `test_v3_14_ws_endpoint.py`; Postgres-using e2e coverage
is blocked until the dev box PG is back up, then can be
added as a follow-on.

## Files in primary review scope

### Server

- `server/app/live/hub.py` — BroadcastHub Protocol +
  InProcessBroadcastHub with three lanes (ephemeral pub/sub,
  ordered with replay, control). Codex 141 #10 + gemini 141
  #10 required the lane split up front so V3 #17 Redis swap
  doesn't break callers.
- `server/app/live/webhook.py` — Ed25519-signed POST
  forwarder with 3 retries (1/4/16s) on 5xx/network. HMAC
  device_pseudonym(device_id, sender_id) per spec.
- `server/app/routers/live.py` — three surfaces:
  - POST /v1/live/connect-token — auth via current_auth_context,
    mints + stores a 256-bit token in an in-process dict
    (Redis SETEX swap in V3 #17).
  - POST /v1/live/plugin/{plugin_row_id}/push — sender → device
    fan-out via plugin_topic.
  - WebSocket /v1/live/plugin/{plugin_row_id} — multiplex
    with open/close/message/ack/error/ping/pong frames.
- `server/app/schemas.py` — PluginPublishRequest gains
  live_inbound_url; envelope conditionally includes it.
- `server/app/models.py` — Plugin.live_inbound_url TEXT NULL.
- `server/alembic/versions/0013_plugin_live_inbound.py`.
- `server/app/services/devices.py` + `pairing.py` — revoke
  paths publish JSON {user_id, device_id?} on
  pairing_revocation_topic() AFTER commit.
- `server/app/config.py` — SERVER_SIGNING_SEED_B64 env.
- `server/tests/test_v3_14_hub.py` — 14 hub tests (lanes,
  saturation, replay).
- `server/tests/test_v3_14_connect_token.py` — 7 endpoint
  tests via SQLite-in-memory pattern.
- `server/tests/test_v3_14_ws_endpoint.py` — 3 smoke tests
  (subprotocol missing/unknown/malformed plugin_row_id).

### Android

- `feature/plugin-host/.../live/LiveChannelClient.kt` —
  OkHttp WebSocket client. Two-step auth (mint via REST →
  open WS with bearer subprotocol). Multiplex via frame
  JSON. Heartbeat pong responder. Jittered backoff
  reconnect.
- `feature/plugin-host/.../capabilities/LiveBridge.kt` —
  platform.live.connect / send / close dispatcher.
  Per-plugin LiveChannelClient cache. Forwards
  incoming events into plugin's onLiveMessage hook via
  dispatchHook. LiveChannelClientFactory interface for
  prod wiring + test injection.
- `feature/plugin-host/.../PluginBridge.kt` — three new
  dispatch entries, constructor arg.
- `feature/plugin-host/.../PluginLoader.kt` — constructs the
  process-singleton LiveBridge. **V0.1 placeholder**:
  deviceJwtProvider throws no_session because Session isn't
  wired in.
- `plugin-sdk-runtime/.../NativeSdkAbi.kt` — bumped to 3.
- `plugin-sdk-runtime/.../PluginContext.kt` — adds
  liveConnect + LiveChannelHandle.
- `feature/plugin-native-sandbox/.../BridgePluginContext.kt`
  — implements liveConnect via bridgeCall AIDL +
  BridgeLiveChannelHandle for send/close.
- `sdk-plugin/src/manifest.ts` — PluginManifest gains
  liveInboundUrl.

## Concerns I want a second opinion on

1. **WS subprotocol parsing.** Server reads
   `sec-websocket-protocol` header, splits on `,`, looks for
   `bearer.<token>`. Client sends "syncler.v1, bearer.<token>".
   Server's `accept(subprotocol="syncler.v1")` echoes only
   the first. Is the parsing correct against real WS
   intermediaries that may reorder / strip whitespace?

2. **Connect token store in-process.** `_TOKEN_STORE` dict +
   `_purge_expired_locked` on every mint/redeem. No async
   lock — mutation runs on the FastAPI request thread. Two
   parallel mint calls could theoretically race the dict
   mutation; CPython dict ops are GIL-atomic for set, but
   I'm not certain `dict.pop` + check is. Is the race a
   problem, or is GIL-atomicity enough for v0.1?

3. **Revocation publish failure tolerance.** Both
   revoke_device and revoke_pairing wrap the hub.publish_control
   in try/except `pass`. If the hub is dead the revoke
   still succeeds DB-side, but live sockets stay open until
   60s heartbeat timeout. Is fail-open the right choice, or
   should the revoke fail loudly?

4. **Rate-limit semantics.** The token bucket is per-WS-
   session, not per-user / per-plugin. Two sockets from the
   same user (e.g. mobile + desktop) get independent quotas.
   Spec says 16 KB/s outbound device → server "per plugin"
   but my impl is per-socket. Tighten to a shared bucket, or
   accept the per-socket interpretation as more honest about
   the actual resource (server-side WS bandwidth)?

5. **Heartbeat-on-any-frame-rolls-deadline.** The pong
   deadline rolls forward on ANY inbound frame, not just
   pong. A chatty plugin keeps the socket alive without
   ever pong'ing. Spec doesn't pin this; lenient default OK?

6. **Webhook signing fail-open.** If SERVER_SIGNING_SEED_B64
   isn't configured, webhook delivery still POSTs but
   without the signature header. The sender SHOULD reject
   unsigned requests, but a misconfigured dev sender will
   accept them. Worth refusing to forward at all instead?

7. **WS endpoint pairing re-check.** Spec says re-check
   pairing every 60-120s (codex 141 #7 NIT) until the
   revocation bus is wired. The bus IS wired (step 7), so
   the polling fallback was deferred. If the bus
   publish/subscribe ever silently drops (Redis swap
   misconfig in V3 #17), socket stays open indefinitely.
   Add the polling fallback now or accept the bus as
   sufficient for V0.1?

8. **LiveChannelClient deviceJwtProvider placeholder.** The
   factory in PluginLoader.android() throws no_session
   unconditionally. Plugin code calling liveConnect today
   gets back Result.failure("no_session"). Honest about the
   wiring gap, or should the placeholder be louder (Timber
   .e + audit log) so it doesn't slip through code review
   into a misleading "works locally" state?

9. **BridgeLiveChannelHandle.close() doesn't update local
   state.** After close, the plugin's handle still holds the
   channel name. Subsequent send() would return io_error /
   channel_not_open from the server. Idempotent close is the
   spec, but is the absence of a local "closed" flag
   confusing for plugin authors?

10. **WS frame size enforcement asymmetric.** Server enforces
    MAX_FRAME_BYTES=64KB on inbound (rate-limit + frame
    parse). Client enforces nothing on outbound — the
    handle.send() just base64s and pushes. A malicious /
    buggy plugin could send a 10MB envelope; OkHttp would
    happily transmit; server would reject + close 4429. Add
    client-side frame size check too, or trust server
    enforcement?

11. **Replay buffer on ordered lane.** ORDERED_REPLAY_CAP =
    256 per topic. A long-running channel pushes past 256;
    replay-since-cursor returns only the last 256. Spec
    didn't pin a number. 256 reasonable for v0.1, or risk
    a "missing-history" silent failure?

12. **Live inbound URL scheme check.** Schema accepts any
    string. The webhook forwarder will POST to whatever URL
    is stored, including http://localhost. Production should
    require HTTPS; V0.1 dev allows HTTP. Add a publish-time
    scheme check now, or accept "trust the sender" for v0.1
    dev?

13. **Step 8 (server tests) deferral.** Hub + connect-token
    tests written but require PG to run. WS endpoint has
    only 3 smoke tests. Full e2e (multi-channel, heartbeat
    timeout, rate-limit close, revocation close, push fan-
    out, webhook retry) deferred to a follow-up. Is that
    acceptable for the post-work review, or do we need the
    e2e suite before V3 #15 starts?

## Test status

```
:app:assembleDebug                              — green
:feature:plugin-host:testDebugUnitTest          — all green
:feature:plugin-native-sandbox:testDebugUnitTest — all green
sdk-plugin tsc + esbuild                        — green
server pytest                                    — BLOCKED on PG dev-env
```

## What I'm asking for

Per-numbered-concern verdict (OK / NIT / FIX / DESIGN).
Plus anything you spot in the commits' diffs that's worth
fixing before V3 #16 starts.

Focus on:
- Auth correctness (subprotocol parsing, connect token
  binding, revocation propagation).
- E2EE invariants (the WS doesn't see payloads; envelopes
  ride opaque).
- Lifecycle (reconnect, channel close, revocation, plugin
  unload).
- Wire contract honesty (frame schema; ack semantics;
  what's enforced where).

Skip cosmetics; flag substance. Goal: greenlight V3 #16 (or
a clear FIX list to apply first).
