# Consultation 59 — Phase 2 fix-up round 3 (greenlight check)

Round-2 verdicts (consultation 58):
- **Gemini**: GREEN ("LAND PHASE 2").
- **Codex**: HOLD with one specific residual race plus one new
  concern. Both rooted in the lock-free CAS state machine.

Round 3 fixes landed in commit `9406f5f`. Review diff
`git diff 1f76df3..9406f5f` against the consultation 58 findings.

## What changed in round 3

The lock-free `AtomicReference` + post-CAS-undo state machine was
replaced with a single monitor (`lifecycleLock: Any`) that
serializes every lifecycle transition. The 401 short-circuit was
removed — `onFailure(401)` now always falls through to
`scheduleReconnectLocked` instead of setting `wantRunning=false`.

| Codex 58 finding | Fix in `9406f5f` |
|---|---|
| #13 RED residual: post-CAS undo race in `openStream()` — `stop()` could run between the `wantRunning` gate and the CAS install, then `start()` could land between the install and the recheck, leaving `wantRunning=true / current=null / no reconnect`. | `start`, `stop`, `openStreamLocked`, `scheduleReconnectLocked` all execute under `synchronized(lifecycleLock)`. There is no longer any compound transition that can be interleaved. The whole "claim a slot, possibly undo it" pattern is gone — `openStreamLocked` either installs cleanly or returns. |
| New concern: 401 set `wantRunning=false` before `AuthRepository` decided the token was stale; a live session was left without a stream. | `onFailure(401)` no longer touches `wantRunning` or cancels the reconnect job. It calls `authFailureHandler.onAuthFailure(token)` (outside the lock) and falls through to `scheduleReconnectLocked`. Stale 401s reconnect with the rolled token. Real 401s: `AuthRepository.logout()` clears the session token, next `openStreamLocked` sees a null token and no-ops; teardown happens when `InboxScreen` ON_PAUSE fires as `AuthScreen` takes over. |

## Locking discipline

- `current`, `wantRunning`, `reconnectAttempt` all declared
  `@Volatile` for the lock-free `onEvent` hot path; mutated only
  while holding `lifecycleLock`.
- `reconnectJob: Job?` — not volatile; mutated only while holding
  the lock.
- The lock is never held across a suspend point. The reconnect
  coroutine does `delay()` outside the lock, then re-enters
  `synchronized(lifecycleLock)` to call `openStreamLocked()`.
- Listener callbacks: `onOpen` / `onClosed` / `onFailure` enter the
  lock for state mutation. `onOpen` emits `_streamReady` after
  releasing the lock so collectors don't run inside the critical
  section.
- `onEvent` is lock-free — gates on `current !== eventSource`
  identity check via the volatile field. A stale read just drops
  the event; correctness is preserved because mutating callbacks
  re-check under the lock.

## Diff to review

```
android/core/network/src/main/kotlin/.../EventStreamManager.kt
  - drop AtomicReference + CAS install + post-CAS undo
  - add lifecycleLock Any() monitor
  - start/stop/openStreamLocked/scheduleReconnectLocked under lock
  - listener callbacks: synchronized blocks for mutation, lock-free onEvent
  - drop wantRunning=false from 401 path
```

## What to verify

1. **Lock discipline.** Is the lock ever held across a suspend point
   or a blocking I/O call? `newEventSource` returns immediately (no
   network on the calling thread), `authFailureHandler.onAuthFailure`
   is invoked outside the lock. Anything I missed?

2. **Listener-vs-lifecycle interleaving.** With full synchronization
   on the four lifecycle methods plus the three mutating callbacks,
   is there any remaining compound-transition race? Specifically:
   - `start()` after `stop()` while a stale `onFailure` is queued on
     the okhttp dispatcher — does the stale callback still no-op?
     (Should: it acquires the lock, sees `current !== eventSource`
     because either `stop()` nulled it or `start()` installed a new
     one, returns.)
   - `stop()` while `scheduleReconnectLocked` is mid-flight inside
     the reconnect coroutine — `stop()` cancels `reconnectJob` and
     the coroutine, but if the coroutine has already crossed
     `delay()` and is inside `synchronized(...) { if (wantRunning) ... }`,
     it waits for the lock, then reads `wantRunning=false` and
     no-ops. OK?

3. **401 fallthrough behavior.**
   - Stale 401 path: `onFailure(401)` → `AuthRepository.onAuthFailure(staleToken)`
     reads `session.currentToken()` (new token), skips `logout()`.
     Reconnect (1s) fires → `openStreamLocked` reads `currentToken`
     (new token, non-null) → opens new stream. ✓
   - Real 401 path: `onFailure(401)` → `AuthRepository.onAuthFailure(currentToken)`
     calls `logout()` → `session.currentToken()` returns null.
     Reconnect (1s) fires → `openStreamLocked` reads null token →
     returns. No subsequent reconnect is scheduled until lifecycle
     ON_PAUSE/ON_RESUME or start()/stop() is called. ✓
   - Reconnect "infinite loop" risk: during the window between 401
     and `logout()` completing (AuthRepository.scope.launch), the
     reconnect could fire and get *another* 401. Each successive 401
     calls onAuthFailure again. Acceptable cost — at most one or two
     extra 401s before logout completes? Confirm.

4. **`onEvent` lock-free identity check.** The hot path doesn't take
   the lock. It reads `current` (volatile) and compares to
   `eventSource`. If the listener is for a stale stream, `current`
   either equals the new stream's source (mismatch → drop) or is
   null (mismatch → drop). Either way, event lands in the correct
   stream's collectors or is dropped. ✓?

5. **Anything else introduced by the locking changes** —
   deadlock potential, monitor contention under load, ordering of
   `_streamReady.tryEmit` after the lock, etc.

## Output

Per reviewer:
1. Per-finding green / yellow / red.
2. New concerns.
3. Overall: **LAND PHASE 2** / specific blockers / hold.

If both reviewers green, Phase 3a (templates renderer) starts.
