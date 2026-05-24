# Consultation 57 — Phase 2 fix-up review (greenlight check)

Phase 2 base landed in 1dc4f74 + 9e592b6. Gemini greenlit with one
YELLOW (auto-reconnect). Codex flagged 5 REDs across reviewers A/B.
Fix-ups for all six landed in commit `2d5e21b`.

Review the diff `git diff 9e592b6..2d5e21b` against each finding from
consultations 56 (Codex) and Gemini's parallel review.

## Fix-up mapping

| # | Codex sev | Finding | Fix |
|---|-----------|---------|-----|
| 7 | RED | Bus ordering race: ID assigned under lock, enqueue after release; concurrent publishers could deliver event 2 before event 1 | `publish_to_user` now enqueues inside the same `async with self._lock`; `put_nowait` is non-blocking so holding the lock is safe. |
| 9 | RED | start() returns before SSE handshake; refresh runs against a snapshot that misses events landing in the gap | New `streamReady: SharedFlow<Unit>` on EventStreamManager emits in `onOpen`. InboxViewModel collects it and does a second catchup `repository.refresh() + userState.pull()`. |
| 12 | RED | 401 only logs; no re-login routing | New `AuthFailureHandler` interface in `:core:network`; `AuthRepository` implements it (Hilt `@Binds` in `SessionBindings`). On 401, EventStreamManager stops reconnect machinery and calls `onAuthFailure()` → `session.logout()`. |
| 13 | RED | No auto-reconnect on transient failure; stranded until lifecycle event | Exponential backoff (1s, 2s, 4s, 8s, 16s, 30s cap). `wantRunning` flag scopes retries to foreground only — `stop()` cancels any in-flight retry. 401 short-circuits the loop. |
| 6 | RED/Y | JWT expiry not enforced mid-stream; a stream could outlive its 24h token | New `_MAX_STREAM_AGE_SECONDS = JWT_TTL - 60s` in `events.py`. Server closes the stream proactively; client reconnects (succeeds with a still-valid token, gets 401 → re-login if not). |
| — | Y | No manual refresh button in top bar (polling removed) | Added `IconButton(Icons.Filled.Refresh)` in InboxScreen TopAppBar between drawer/search. |

Gemini's matching findings (consultation 56):
- YELLOW #13 auto-reconnect → closed by fix #4
- Line #1 dismiss handler triggers refresh but server doesn't filter
  by `DeliveryStatus.dismissed_at` → pre-existing limitation;
  Gemini suggested Phase 3a follow-up. **NOT addressed in this
  commit.** Confirm this is acceptable as Phase 3a work, or
  reclassify as Phase 2 fix-up needed.
- Line #3 plan/test wording inconsistency about Last-Event-ID →
  documentation nit; implementation correctly followed the
  deferral.

## Diff to review

```
server/app/routers/events.py                                         (+ _MAX_STREAM_AGE_SECONDS gate)
server/app/services/events.py                                        (enqueue under lock)
android/core/network/src/main/kotlin/.../EventStreamManager.kt       (+ reconnect, + streamReady, + AuthFailureHandler)
android/core/auth/src/main/kotlin/.../AuthRepository.kt              (+ AuthFailureHandler impl, + scope, import cleanup)
android/core/auth/src/main/kotlin/.../Session.kt                     (+ Hilt @Binds for AuthFailureHandler)
android/feature/inbox/src/main/kotlin/.../InboxScreen.kt             (+ streamReady collector, + manual refresh button)
```

## What to verify

For each numbered finding (6, 7, 9, 12, 13), confirm full closure or
flag a residual gap. Specifically:

1. **#7 bus ordering**: is `put_nowait` inside the lock safe under
   high publisher concurrency? Any deadlock risk if a subscriber's
   queue is bounded and full (we just log + drop, no awaiting)?

2. **#9 catchup race**: does `streamReady` fire exactly once per
   successful handshake? What about reconnect after a failure —
   does it fire again (so the post-reconnect catchup runs)?

3. **#12 auth failure**: does `AuthRepository.onAuthFailure()` race
   with a concurrent successful login (user re-authenticated faster
   than the 401-handler scope launched)? The fix uses a SupervisorJob
   scope inside AuthRepository — verify the logout it triggers
   doesn't fight with an in-flight login coroutine.

4. **#13 reconnect**: exponential backoff is 1s/2s/4s/8s/16s/30s.
   `wantRunning` gates the retry; `stop()` cancels in-flight retries.
   Are there ordering edge cases — e.g., onFailure scheduling a
   reconnect while stop() cancels the job? Volatile vs synchronization?

5. **#6 max-age**: `_MAX_STREAM_AGE_SECONDS = ACCESS_TOKEN_EXPIRE_HOURS * 3600 - 60`.
   If the token TTL changes, both sides update automatically. The
   close happens on next `wait_for` cycle (max ~25s extra). Is that
   acceptable, or should the close be more immediate?

6. **Manual refresh**: viewModel.refresh() exists and was already
   wired to `repository.refresh() + userState.pull()`. The new button
   surfaces it. Anything else needed?

7. **Dismiss handler still a no-op**: deferring to Phase 3a per
   Gemini's suggestion. Or do you want it as a Phase 2 hardening?

## Output

Per reviewer:
1. Per-finding green / yellow / red.
2. New concerns.
3. Overall: land Phase 2 / specific blockers / hold.

If both reviewers green, Phase 3a (templates renderer) starts.
