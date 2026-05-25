=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 117 — Phase 10b steps 1-4 (IPC scaffolding mid-track)

Phase 10a design dual-GREEN at `a70351f`. Phase 10b steps 1-4
shipped:

- `bb61e37` step 1: `:core:plugin-aidl` module — AIDL files +
  `PluginLoadParcel`.
- `80f2987` step 2: `:feature:plugin-sandbox` module —
  `PluginSandboxService`, `PluginTokenCoordinator`,
  `PluginWebViewHost` interface, `PluginHostCallbackLocal`
  shim, 4 passing unit tests on the FSM.
- `640d48b` step 3: `PluginSandboxConnection` in
  `:feature:plugin-host` — ref-counted `bindService` +
  30 s idle-unbind.
- `78ecac9` step 4: `SandboxRouter` + `BridgeDispatcher`
  contract in `:feature:plugin-host`.

Steps 5 + 6 are NOT in this consult — they ship the real
`PluginWebViewHostFactory`, the connectedAndroidTest e2e, the
`PluginBridge` rewire, and the in-process WebView strip. Asking
for review now so any architectural issues land before the
disruptive step 5 work.

## What's in scope

### `:core:plugin-aidl`

- `PluginLoadParcel.aidl` + Kotlin Parcelable implementation
  with versioned wire format (`WIRE_VERSION = 1`) and a 64 KB
  truncation guard on `diagnosticManifestJson`.
- `IPluginSandbox.aidl`: `loadPlugin(parcel, callback)`
  synchronous; `unloadPlugin / dispatchHook /
  deliverBridgeResult` oneway; `querySandboxState` synchronous
  diagnostic.
- `IPluginHostCallback.aidl`: every method carries
  `sandboxToken`, all oneway including the new
  `onPluginUnloaded` cleanup ACK.

### `:feature:plugin-sandbox`

- `PluginSandboxService` declares `android:process=":plugin"` +
  `exported="false"`. Implements `IPluginSandbox.Stub`.
- Validates `PluginLoadParcel` at the AIDL boundary
  (`parcel_malformed`, `diagnostic_field_oversize`).
- Wraps incoming AIDL callback in `PluginHostCallbackLocal` so
  `PluginTokenCoordinator` doesn't depend on `IPluginHostCallback.Stub`
  (which can't be instantiated on the JVM unit-test classpath —
  `attachInterface` on `android.os.Binder` isn't mocked).
- `PluginTokenCoordinator` owns per-token state machine + a
  capacity-64 `Channel` serial queue. Hooks/results dropped per
  the state-machine table when out-of-window.
- `PluginWebViewHost` interface ships; production impl is a
  no-op factory in step 4. The real WebView impl is step 5.
- `PluginTokenRegistry` (concurrent map keyed by token).
- 4 unit tests:
  - `lifecycleFromLoadingThroughUnload`
  - `hooksDroppedAfterErrored`
  - `unloadIsIdempotent`
  - `bridgeCallFromWebViewRoutesToHost`

### `:feature:plugin-host` — connection + router

- `PluginSandboxConnection`: mutex-serialized `acquire`/`release`,
  ref-counted, schedules `unbindService` 30 s after the count
  hits zero. `onSandboxDeath` callback exposed for the router.
- `SandboxRouter`:
  - `allocateToken()` mints a process-local monotonic int (used
    before staging so the staged path includes it).
  - `loadPlugin(parcel)` calls `connection.acquire()` →
    sandbox AIDL `loadPlugin`, verifies the returned token
    equals the parcel's, registers a `SandboxHandle`.
  - `unload / dispatchHook / deliverBridgeResult` each acquire
    the connection just long enough for one AIDL call.
  - Inner `PluginHostCallbackStub` (extends
    `IPluginHostCallback.Stub`) generation-fences `bridgeCall`
    by token, routes events to a `BridgeDispatcher` contract.
  - On `onSandboxDeath` from the connection, drains every
    active handle with `bridgeDispatcher.onPluginCrashed
    ("process_died")`.
- `SandboxHandle`: opaque handle the bridge holds per active
  plugin; method-call sugar over the router.
- `BridgeDispatcher` interface ships for step 5 to wire into
  `PluginBridge`.

## Risks I want eyes on

1. **Bind/unbind reference counting.** `loadPlugin` acquires
   the connection; the release happens when the sandbox fires
   `onPluginUnloaded` (via `releaseHandle` in
   `SandboxRouter`). `onPluginCrashed` also calls
   `releaseHandle`. Is there a path where the ref count gets
   stuck (e.g. crash *before* the load succeeded — we'd have
   acquired but not registered a handle)? The current code
   has a `try/catch` in `loadPlugin` that calls `release()`
   on throw, but I want a second pair of eyes.

2. **Mutex contention on the connection.** Every AIDL call
   from host → sandbox calls `connection.acquire()` which
   takes the mutex briefly, even when the connection is
   already bound. The mutex is released before the AIDL
   transaction returns. That should serialize bind/unbind
   but not steady-state dispatches. Worth confirming.

3. **Generation-fence in the host stub.** `bridgeCall` checks
   `handles.containsKey(sandboxToken)`. If a sandbox is
   buggy and emits a `bridgeCall` for a token the host has
   never seen, we drop + warn-log. Stronger would be to
   crash the sandbox (kill its process). For V1.5, dropping
   is fine. Worth flagging.

4. **The `IPluginHostCallback.Stub`-vs-`PluginHostCallbackLocal`
   split.** The sandbox-side coordinator depends on the plain
   Kotlin interface (`PluginHostCallbackLocal`) and
   `PluginSandboxService` adapts. The host-side
   `SandboxRouter` directly extends `IPluginHostCallback.Stub`
   (no shim — host's unit-test path isn't on the JVM for the
   router; we test the router via instrumentation in step 5).
   Asymmetric but pragmatic.

5. **`SandboxRouter` keeps its own `CoroutineScope`.** Binder
   threads land in the stub, then we hop onto our scope
   before calling `bridgeDispatcher`. Means a slow capability
   call never blocks Binder. Worth verifying.

6. **No handle for `querySandboxState` exposed yet.** It's
   in the AIDL contract but the host doesn't have a public
   diagnostic surface. Step 5 will add one. Worth noting.

7. **`PluginSandboxConnection` is a plain class, not Hilt-
   injected.** `:feature:plugin-host` doesn't have Hilt
   wired. The host app's DI graph is responsible for
   instance-singleton enforcement. Worth flagging if the
   reviewer would prefer Hilt setup before any caller.

## Files

- `android/core/plugin-aidl/{build.gradle.kts, ManifestAID*,
  PluginLoadParcel.{kt,aidl}, IPluginSandbox.aidl,
  IPluginHostCallback.aidl}`
- `android/feature/plugin-sandbox/{build.gradle.kts, AndroidManifest.xml,
  PluginSandboxState.kt, PluginWebViewHost.kt,
  PluginHostCallbackLocal.kt, PluginTokenCoordinator.kt,
  PluginSandboxService.kt}` + test
- `android/feature/plugin-host/src/main/kotlin/.../sandbox/{
  PluginSandboxConnection.kt, SandboxRouter.kt}`
- `android/settings.gradle.kts` adds both new modules.

## Test status

- `:core:plugin-aidl:testDebugUnitTest`: 1 unit test (cap
  constant) + 1 @Ignore'd Parcel round-trip (deferred to
  instrumented test in step 5). 1 passed.
- `:feature:plugin-sandbox:testDebugUnitTest`: 4/4 passing.
- `:app:assembleDebug`: green.

## Output

Per reviewer, terse:

1. Verdict on steps 1-4: GREEN / YELLOW / RED + items.
2. Anything missing before step 5 starts.
3. Anything new (concurrency, lifecycle, security).

If dual-GREEN, step 5 begins (real WebView impl + e2e test +
bridge wire).

**Reply text only. Do NOT call any write/mutation tool. Do not
commit, edit, or stage anything.**
