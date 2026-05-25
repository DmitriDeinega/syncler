=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 118 — Phase 10b steps 1-4 fixups (post-117)

Triad 117 verdicts: both YELLOW with overlapping findings. All
five fixes shipped at `f3d82dd`:

## v2 deltas

1. **`:app` dep on `:feature:plugin-sandbox`** (Codex 117 #1).
   The APK now packages the sandbox module so
   `PluginSandboxService` is reachable by name when
   `PluginSandboxConnection.bindService()` fires.

2. **Ref-count leak on sandbox process death** (Codex 117 #2 +
   Gemini CRITICAL). `SandboxRouter.handleSandboxDeath()`
   now does `connection.release()` per dead handle. Without
   this, every `:plugin` crash leaked a ref count and the
   idle-unbind path could never fire on a future
   load → unload cycle.

3. **Load/crash race** (Codex 117 #4 + Gemini 117 #2).
   `SandboxRouter.loadPlugin` now pre-registers the
   `SandboxHandle` BEFORE `sandbox.loadPlugin(parcel,
   callback)`. The catch block removes the handle on
   failure. Together with fix #4 below, mid-load
   `onPluginCrashed` / `onPluginUnloaded` arrivals no longer
   miss the token or double-release.

4. **`releaseHandle` idempotency** (Codex 117 #3 + Gemini 117
   #3). Only releases the connection ref when
   `handles.remove(token)` returns non-null. A stray
   `onPluginUnloaded` from the sandbox's sync load-fail
   teardown (after the host catch block already cleaned up)
   skips the spurious release.

5. **Lifecycle callbacks generation-fenced** (Codex 117 #3).
   `onWebViewError` + `onPluginReady` check
   `handles.containsKey(token)` and drop+warn-log if stale.
   `onPluginCrashed` + `onPluginUnloaded` route through
   `releaseHandle` whose idempotency guard already covers
   them.

## Deferred (not blocking)

- **Mutex contention on `PluginSandboxConnection.acquire`
  fast path** (Gemini 117 #4). Lockless DCL with
  `AtomicReference` would let steady-state acquires bypass
  the mutex. Current behavior is correct, just
  serializes-where-it-could-be-concurrent. Revisit if
  profiler flags it.
- **64 KB cap measured in `String.length` vs byte length**
  (Gemini 117 #5). 64K characters is comfortably safe for
  manifest JSON even with multi-byte content. Revisit if
  real-world manifests trend multi-byte-heavy.

## Files

- `android/app/build.gradle.kts` (+ dep)
- `android/feature/plugin-host/src/main/kotlin/.../sandbox/SandboxRouter.kt`
  (loadPlugin pre-register + idempotent releaseHandle +
   handleSandboxDeath release + lifecycle fences)

## Test status

`:app:assembleDebug` + `:feature:plugin-host:compileDebugKotlin`
both succeed. Existing
`:feature:plugin-sandbox:testDebugUnitTest` (4 tests) still
passes.

## Output

Per reviewer, terse:

1. Verdict on the fixups: GREEN / YELLOW / RED + items.
2. Anything still missing before step 5.
3. Anything new.

If dual-GREEN, step 5 begins (real `PluginWebViewHostFactory`,
e2e test, `PluginBridge` rewire).

**Reply text only. Do NOT call any write/mutation tool. Do not
commit, edit, or stage anything.**
