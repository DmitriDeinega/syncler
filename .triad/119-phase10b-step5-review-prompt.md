=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 119 — Phase 10b step 5 (real WebView host + e2e + ready wiring)

Steps 5a / 5b / 5c / 5d shipped at:

- `36bb61d` step 5a — `RealPluginWebViewHost` + `RealPluginWebViewHostFactory` + `PluginHtmlShell` (duplicated from in-process loader byte-for-byte).
- `9dd6f7c` step 5b — `BridgeDelivery` split (`PluginBridge` now takes delivery, not raw WebView), `SandboxBridgeDispatcher` + `SandboxBridgeDelivery` on the host side.
- `38cc31c` step 5c — `connectedAndroidTest` harness binding the real `:plugin` service via `ServiceTestRule`. Failure paths only (parcel_malformed, bundle_hash_mismatch, unsupported_renderer, diagnostic_field_oversize, concurrent_load_in_progress) + load → unload → onPluginUnloaded cross-process round-trip.
- `e2b82ba` step 5d — replaced `fun interface BridgeBroker { bridgeCall }` with `interface HostSignals { bridgeCall + reportReady + reportError }`. `RealPluginWebViewHost` now installs a `WebViewClient` whose `onPageFinished` drives `reportReady` and whose main-frame `onReceivedError` drives `reportError`. `shouldOverrideUrlLoading` blocks anything not equal to `PluginHtmlShell.INITIAL_URL` (`https://plugin.local/`).

## Files in scope

- `android/feature/plugin-sandbox/src/main/kotlin/app/syncler/feature/pluginsandbox/RealPluginWebViewHost.kt`
- `android/feature/plugin-sandbox/src/main/kotlin/app/syncler/feature/pluginsandbox/PluginHtmlShell.kt`
- `android/feature/plugin-sandbox/src/main/kotlin/app/syncler/feature/pluginsandbox/PluginWebViewHost.kt` (HostSignals)
- `android/feature/plugin-sandbox/src/main/kotlin/app/syncler/feature/pluginsandbox/PluginTokenCoordinator.kt` (hostSignals object)
- `android/feature/plugin-sandbox/src/main/kotlin/app/syncler/feature/pluginsandbox/PluginSandboxService.kt` (NoopPluginWebViewHost shape)
- `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/BridgeDelivery.kt`
- `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/PluginBridge.kt`
- `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/sandbox/SandboxBridgeDispatcher.kt`
- `android/feature/plugin-sandbox/src/androidTest/kotlin/app/syncler/feature/pluginsandbox/PluginSandboxServiceTest.kt`
- `android/feature/plugin-sandbox/build.gradle.kts` + `android/gradle/libs.versions.toml` (testInstrumentationRunner + androidx-test deps)

## Known scope gaps (already deferred — not blockers, but flag if you disagree)

- **connectedAndroidTest covers failure paths + load/unload only.** The `onPluginReady` round-trip from a real WebView cannot be asserted because `WebViewHostFactoryOverride` is a test-process static and is invisible to `:plugin`. Verification of the success path lands either via emulator manual test or by reshaping the override mechanism (ContentProvider-based, Binder-based hint, or per-process Application class that reads a global) — out of scope for step 5.
- **`PluginHtmlShell` is duplicated** between :feature:plugin-host (in-process loader) and :feature:plugin-sandbox. Step 6 deletes the in-process loader; deduping before then would be wasted work.
- **In-process loader still active.** The `:feature:plugin-host` legacy WebView path is the production loader today. Sandbox path is fully wired but PluginLoader / PluginRegistry / PluginHostActivity do not call SandboxRouter yet. Step 6 does that flip.

## Concerns I want a second opinion on

1. **`onPageFinished` as ready signal.** Fires once per load via the `reported` guard. Question: can `loadDataWithBaseURL` cause `onPageFinished` to fire BEFORE the bundle script tags have executed (e.g., if there's any deferred parsing)? If so, hooks dispatched immediately after `onPluginReady` would hit a not-yet-installed `__syncler_internal_dispatch`.

2. **`shouldOverrideUrlLoading` for the initial `loadDataWithBaseURL`.** Does Android invoke `shouldOverrideUrlLoading` for the synthesized base-URL load itself? If yes, the current `return request?.url?.toString() != PluginHtmlShell.INITIAL_URL` returning true for the initial load would block the page from rendering at all.

3. **JsBridge race with destroy.** `destroy()` nulls `hostSignalsRef` synchronously on the calling thread (Binder thread typically), then posts WebView teardown to main. Concurrently a `@JavascriptInterface call` may be in flight on Android's private JS-bridge thread: it null-checks `hostSignalsRef`, gets non-null, then calls `signals.bridgeCall` — which dispatches through AIDL to a host that may have already moved on. AIDL adapter swallows RemoteException, so this should be safe, but worth confirming.

4. **`SandboxBridgeDelivery.deliver` fire-and-forget.** `scope.launch { router.deliverBridgeResult(...) }`. If the scope is cancelled (host shutting down) the result never reaches JS and the JS promise hangs until plugin timeout. The doc comment notes this. Acceptable?

5. **Bundle file path access from `:plugin` process.** `PluginLoadParcel.bundleFilePath` points to a file in the host process's `noBackupFilesDir`. Same UID + same app data dir → `:plugin` should read it. Any failure mode I'm missing (e.g., per-process FS namespace on some OEMs)?

6. **connectedAndroidTest scope.** Failure paths + load/unload cross-process round-trip. Is this enough to ship step 5 with reasonable confidence, or do you want at least one positive-flow test (even if it requires manual emulator validation as a follow-up)?

## Test status

- `:feature:plugin-sandbox:testDebugUnitTest` — 6 tests pass (added `reportReadyFromWebViewTransitionsToReady`, `reportErrorFromWebViewTransitionsToErrored`).
- `:feature:plugin-host:testDebugUnitTest` — passes.
- `:feature:plugin-sandbox:compileDebugAndroidTestKotlin` — compiles. Has not been run on a device (no Android emulator/device wired to this dev box).

## Output

Per reviewer, terse:

1. Verdict on step 5 as a whole (5a + 5b + 5c + 5d): GREEN / YELLOW / RED + items.
2. Any of the 6 concerns above I should fix before step 6.
3. Anything new I missed.

If dual-GREEN, step 6 begins (strip in-process loader; rewire PluginLoader / PluginRegistry / PluginHostActivity through SandboxRouter).

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
