# Consultation 60 — Phase 2 fix-up round 4 (greenlight check)

Round-3 verdicts (consultation 59):
- **Gemini**: GREEN ("LAND PHASE 2").
- **Codex**: HOLD with one RED. The retry coroutine's own `Job` stays
  `isActive == true` while it re-enters the lock and calls
  `openStreamLocked`, so a synchronous failure of the just-opened
  stream would hit the `reconnectJob?.isActive == true` gate in
  `scheduleReconnectLocked` and silently skip the next retry —
  stranding the user with `wantRunning=true, current=null, no
  reconnect`.

Round 4 fix landed in commit `a2db9cb`. One line: inside the retry
coroutine's `synchronized(lifecycleLock)` block, set
`reconnectJob = null` before calling `openStreamLocked()`. Diff is
`git diff 9406f5f..a2db9cb`.

## What changed

```kotlin
reconnectJob = scope.launch {
    delay(delayMs)
    synchronized(lifecycleLock) {
        reconnectJob = null   // <- new
        if (wantRunning) openStreamLocked()
    }
}
```

The retry coroutine retires itself by clearing the `reconnectJob`
reference under the lock, *before* opening the new stream. A
synchronous failure on the just-opened stream now sees
`reconnectJob == null` and schedules the next retry cleanly.

## What to verify

1. **Does the fix actually close Codex 59's RED?** Walk the same
   scenario:
   - retry delay fires
   - coroutine acquires lock
   - coroutine sets `reconnectJob = null`
   - coroutine calls `openStreamLocked()` → installs new source
   - coroutine releases lock (lambda still running)
   - new stream fires `onFailure` synchronously (e.g., immediate
     transport 401)
   - onFailure acquires lock, identity-check passes, clears
     `current`, calls `scheduleReconnectLocked()`
   - `scheduleReconnectLocked` checks `reconnectJob?.isActive == true`
     → `reconnectJob` is null → condition false → proceeds to
     schedule
   - new retry coroutine launched, gate works. ✓

2. **Doesn't break stop() ordering.** `stop()` acquires the lock,
   reads `reconnectJob`, cancels it, nulls it. If `stop()` runs
   while the retry coroutine is inside its synchronized block:
   - stop() blocks waiting for the lock
   - retry coroutine: `reconnectJob = null` (nulled by us);
     `openStreamLocked` runs (sees `wantRunning=true` since stop
     hasn't run yet — but wait, stop is waiting for the lock; so
     `wantRunning=true` here). Opens stream. Sets `current = source`.
   - coroutine releases lock
   - stop() acquires lock, sets `wantRunning=false`,
     `reconnectJob?.cancel()` (null, no-op), nulls `reconnectJob`,
     reads `current = source`, sets `current = null`, cancels source.
   - End state: clean shutdown. ✓

   If `stop()` runs while the retry coroutine is in `delay()`:
   - stop() acquires lock first, cancels `reconnectJob`, sets it null,
     wantRunning=false. Releases lock.
   - coroutine's delay() throws CancellationException → coroutine
     dies without running the synchronized block. ✓

3. **Doesn't break the case where the retry coroutine succeeds.**
   The new stream's `onOpen` fires later (not synchronously). By
   then `reconnectJob` is already null. `onOpen` resets
   `reconnectAttempt = 0`, emits `streamReady`. No reconnect needed
   while the stream is healthy. ✓

4. **Any other path that could leak a stranded-live-session state?**
   E.g., `start()` after a real-401 logout drained the token — does
   anything restart the stream when the user logs back in? Expected:
   re-login → new session → UI navigates back to InboxScreen →
   ON_RESUME → `start()` → opens new stream. Confirm this is the
   intended teardown/rebuild path.

## Diff to review

```
git diff 9406f5f..a2db9cb
  android/core/network/src/main/kotlin/.../EventStreamManager.kt
    +reconnectJob = null  (inside the retry coroutine's
                          synchronized block, before openStreamLocked)
    + 8 lines of comment explaining why
```

## Output

Per reviewer:
1. Per-finding green / yellow / red.
2. New concerns.
3. Overall: **LAND PHASE 2** / specific blockers / hold.

If both reviewers green, Phase 3a (templates renderer) starts.
