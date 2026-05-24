# Consultation 56 — Phase 2 code review (SSE + remove polling)

Phase 2 of the agreed plan (`.triad/50-agreement-and-plan.md`) is
implemented across two commits on top of Phase 1:

```
1dc4f74 phase 2 (server): SSE event stream + publisher hooks
9e592b6 phase 2 (android): SSE EventStreamManager + lifecycle-driven refresh
```

Review the diff `git diff 0689203..9e592b6` against the Phase 2 scope.
Independent positions per reviewer.

## Phase 2 scope recap

From `.triad/50-agreement-and-plan.md`:

- **Server:** `GET /v1/events` SSE endpoint. Auth via `current_auth_context`.
  Heartbeats. Bounded per-subscriber queues. Server-initiated disconnect on
  device revoke.
- **Server:** publish hint events from message send, dismiss, and state put.
- **Server:** in-process event bus (Redis swap-in on the future roadmap).
- **Android:** `EventStreamManager` in `:core:network` using `okhttp-sse`.
  Lifecycle-driven open/close (`ON_RESUME` / `ON_PAUSE`).
- **Android:** event handlers route to `InboxRepository.refresh()` /
  `UserStateRepository.pull()`.
- **Android:** remove the 15-second polling loop in `InboxScreen.kt`.

Out of scope for Phase 2 (next phases):
- Live cards (Phase 3b)
- Templates (Phase 3a)
- WebSocket / `platform.live.subscribe` (V2 roadmap)
- Field-level live subscriptions (V2 roadmap)
- `Last-Event-ID` resume server-side (deferred; client doesn't pass it yet
  either — left as a future hardening).

## Diff to review

`git diff 0689203..9e592b6` covers:

```
server/app/main.py                                                       (events router registered)
server/app/routers/devices.py                                            (close_device_subscribers on revoke)
server/app/routers/events.py                                             (new — SSE endpoint)
server/app/routers/messages.py                                           (publish inbox.changed, dismiss)
server/app/routers/state.py                                              (publish state.changed)
server/app/services/events.py                                            (new — EventBus + Event + encode_sse)
server/tests/test_events.py                                              (new — 9 tests)

android/core/network/build.gradle.kts                                    (+ okhttp-sse, + timber)
android/core/network/src/main/kotlin/.../EventStreamManager.kt           (new)
android/feature/inbox/src/main/kotlin/.../InboxScreen.kt                 (- polling loop, + lifecycle observer, + event collector)
android/gradle/libs.versions.toml                                        (+ okhttp-sse library entry)
```

## What to pressure-test

**Event bus / scaling:**

1. **In-process bus = one uvicorn worker.** With multiple workers, an event
   published on worker A is invisible to a subscriber on worker B. Is the
   single-worker constraint clearly documented? Anything that needs to
   land before a real deployment with workers > 1?

2. **Bounded queue overflow.** `_MAX_QUEUE = 64`. A device that pauses
   reading (network limbo) loses events. Is 64 the right ceiling? Should
   a queue-full event log at warn or error level, or trigger a
   server-initiated disconnect so the client reconnects cleanly?

3. **`_Subscriber` identity hash.** Custom `__hash__` = `id(self)` and
   `__eq__` = identity. Two subscribers from the same device are tracked
   independently. Acceptable, or should we deduplicate by device_id so
   only one stream-per-device is alive?

**SSE endpoint:**

4. **Heartbeat interval.** 25 seconds, picked under OkHttp's 30s default
   read timeout. The EventStreamManager bumps readTimeout to 0 (infinite),
   which arguably makes the heartbeat redundant for the client — but it
   still matters for any intermediate proxy (nginx, CDN). Right answer?

5. **`X-Accel-Buffering: no`** is nginx-specific. Anything else needed
   for other proxies (cloudflare, fly.io router)?

6. **Auth on long-lived stream.** Handshake authenticates via
   `current_auth_context`, but the stream stays open for hours. If a
   device is revoked mid-stream, the publisher hook calls
   `close_device_subscribers` and the stream exits. **But: if the JWT
   itself expires (24h cap) mid-stream, the stream stays open.** Is
   this acceptable, or should we add a periodic re-validation? Test
   currently covers revoke; not JWT expiry.

7. **Event ordering.** The bus assigns a monotonic `id` and serialises
   the `next_id` increment under a lock, but the actual enqueue is per-
   subscriber. Could a subscriber receive events out of order if the
   lock interleaves with another publish? Eyeballing it looks fine,
   but worth confirming.

**Android client:**

8. **Lifecycle observer in Compose.** The `DisposableEffect` adds and
   removes the `LifecycleEventObserver`. In what scenarios does this
   leak? E.g., configuration change (rotation) — does the observer
   re-bind cleanly?

9. **`onForeground` calls `refresh()` synchronously after `start()`.**
   `start()` returns immediately and the SSE handshake races with the
   refresh. If the handshake takes 100ms and the user lands on the inbox,
   the refresh fires first (correct catchup), then the stream picks up
   any events that arrived between catchup and stream-open. Looks fine
   but confirm there's no "events arrive during the gap and we miss
   them" risk.

10. **`EventStreamManager` is a `@Singleton`.** Multiple `InboxViewModel`
    instances (rare, but possible with configuration changes) would all
    subscribe to the same `events` SharedFlow. The buffer is `replay = 0`,
    so a late subscriber misses any events from before it joined. Is
    `replay = 0` correct, or should it be 1 for the "rotation lost a
    fresh event" edge case?

11. **`tryEmit` on overflow.** The `_events` flow has `extraBufferCapacity = 64`.
    A stalled collector triggers `tryEmit` to return false and the event
    is dropped (logged at warn). Same question as #2 server-side: is 64
    the right ceiling?

12. **401 not retried.** The `EventStreamManager` correctly stops on
    401. Does anything trigger an automatic re-login flow on the
    client when this happens, or does the user have to manually
    log in again to get the stream back?

**Cross-cutting:**

13. **Polling removed entirely.** The 15-second loop is gone. If SSE
    fails to connect (e.g., server down), the client now has only
    lifecycle-triggered refresh + FCM as freshness paths. Is the
    manual-refresh button still wired up? (Phase 3 docs say yes.)

14. **Phones without Google Play Services** (no FCM). They still get
    SSE while foreground, but background delivery is gone. Documented?

15. **Latent CAS / merge bugs** that a faster event-driven feedback
    loop might surface. The state.changed → pull path now fires
    immediately on a remote PUT instead of after up to 15s.

## Output

Per reviewer, independently:

1. Per-task (1-15): green / yellow / red.
2. Any line-level concerns.
3. Overall: land Phase 2 as-is / specific fix-ups / hold for design.

If both reviewers green, Phase 3a (templates renderer) starts immediately.
