# Consultation 58 — Phase 2 fix-up round 2 (greenlight check)

Phase 2 fix-up round 1 (commit `2d5e21b`) shipped after consultation 56.
Round-1 re-review (consultation 57):
- **Gemini**: GREEN, "LAND PHASE 2".
- **Codex**: HOLD with two REDs (#12 token-stale 401, #13 stale
  callback ownership) — same root cause.

Round 2 fixes landed in commit `1f76df3`. Review diff
`git diff 2d5e21b..1f76df3` against the consultation 57 findings.

## Round-2 fix-up mapping

| # | Codex 57 sev | Finding | Fix in `1f76df3` |
|---|---|---|---|
| 13 | RED | `onOpen`/`onClosed`/`onFailure` never check `eventSource == current`. Stale callbacks from a canceled stream could null out a newer EventSource, schedule retries, emit `streamReady`, or trigger logout. Compound `current`/`reconnectJob` transitions unsynchronized. | EventStreamManager rewritten: `@Volatile var current` replaced with `AtomicReference<EventSource?> currentRef`. Per-stream listener (closure-captured token). `onOpen`/`onEvent` gate on `currentRef.get() !== eventSource` (identity check). `onClosed`/`onFailure` use `currentRef.compareAndSet(eventSource, null)` — stale callbacks no-op silently. `openStream()` now: pre-check `wantRunning`, install via `compareAndSet(null, source)`, then post-CAS recheck `wantRunning` (catches start/stop race). `stop()` uses `currentRef.getAndSet(null)` so it always wins against any concurrent install. |
| 12 | RED | `AuthRepository.onAuthFailure()` launches unconditional async `logout()`. A stale 401 from an old EventSource could clear a newer successful session. | Interface signature changed to `onAuthFailure(failedToken: String)`. EventStreamManager's per-stream listener passes the token used for that stream's handshake. `AuthRepository.onAuthFailure(failedToken)` calls `session.currentToken()` and only logs out if it matches `failedToken`. Stale 401s from replaced streams are debug-logged and ignored. |
| 9 | YELLOW (residual) | Stale `streamReady` emit after stop/restart from an old canceled stream's `onOpen`. | Closed by the same identity guard in `onOpen` (`currentRef.get() !== eventSource` → return). Stale streams cannot emit `streamReady` anymore. |
| — dismiss | YELLOW | `dismiss` SSE event triggers `repository.refresh()`, but server inbox does not filter `DeliveryStatus.dismissed_at` and client merge does not remove existing dismissed rows — live cross-device dismiss isn't visible. | Tracked in `.triad/50-agreement-and-plan.md` Phase 3a "Cross-device dismiss filtering (Phase 2 carry-over)" section. Phase 3a will filter on the server side. **Not addressed in `1f76df3`.** |

## Diff to review

```
.triad/50-agreement-and-plan.md                                      (+ Phase 3a dismiss-filtering tracking)
android/core/network/src/main/kotlin/.../EventStreamManager.kt       (AtomicReference rewrite + CAS install + per-stream listener)
android/core/auth/src/main/kotlin/.../AuthRepository.kt              (token-scoped onAuthFailure)
```

## What to verify

For each finding, confirm full closure or flag a residual gap.

1. **#13 stale callback ownership.**
   - `onOpen`/`onEvent` use `currentRef.get() !== eventSource` (identity, not equality).
   - `onClosed`/`onFailure` use `currentRef.compareAndSet(eventSource, null)`.
   - `openStream()` CAS-installs and rechecks `wantRunning` after install (catches the case where `stop()` ran between the `wantRunning` gate and the CAS).
   - `stop()` uses `getAndSet(null)` so it always wins against a concurrent install attempt.
   - Are there remaining race windows? E.g., does the post-CAS recheck have a TOCTOU between `wantRunning` read and the CAS-undo? (If `start()` re-sets `wantRunning=true` between our read and our CAS-undo, we'd cancel a stream the user wants — but `wantRunning=true` followed by `openStream()` would re-install via the install CAS, so it self-heals. Confirm this is fine.)

2. **#12 token-scoped auth failure.**
   - `AuthFailureHandler.onAuthFailure(failedToken: String)`.
   - `AuthRepository.onAuthFailure(failedToken)` reads `session.currentToken()` and gates `logout()` on equality.
   - Race window: between the 401 firing and the launched coroutine running, the user could re-login. `session.currentToken()` is read inside the launched coroutine (not at the call site), so we see the post-relogin token. Good.
   - Reverse race: what if the user *just* relogged in and the new token equals the old one? In practice JWTs are unique per login (different `iat`/`exp` claims), so collision is effectively impossible. Confirm acceptable.
   - 401 from a stream opened *before* a relogin: stream uses the old token in its handshake header; 401 fires; `onAuthFailure(oldToken)`; `session.currentToken()` returns the new token; we skip logout. Correct.

3. **Per-stream listener closure capturing token.** Each `openStream` builds a fresh listener with `makeListener(token)`. Confirm there's no leak (listeners are GC'd when EventSource is canceled/finalized), no surprising memory cost (per-stream allocation is fine).

4. **Phase 3a dismiss-filtering tracking.** The plan now has a "Cross-device dismiss filtering (Phase 2 carry-over)" subsection in Phase 3a. Codex consultation 57 asked for this to be "explicitly tracked." Is the tracking clear and actionable enough, or does it need more detail?

5. **Anything else?** New concerns introduced by the round-2 changes — coroutine context, error propagation, GC, threading on the okhttp dispatcher, etc.

## Output

Per reviewer:
1. Per-finding green / yellow / red, including reasoning for any non-green.
2. New concerns introduced by round-2 changes.
3. Overall verdict: **LAND PHASE 2** / specific blockers / hold.

If both reviewers green, Phase 3a (templates renderer) starts.
