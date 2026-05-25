=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 121 — Phase 10b step 6 (strip in-process WebView)

Step 6 shipped at `1cc445a`, cleanup at `40466fa`. Triad 120 was
dual-GREEN on step 5, clearing this strip.

## What step 6 does

Removes the in-process WebView path from `:feature:plugin-host`
entirely. All plugin JS now runs in the `:plugin` subprocess
(landed in step 5). The host is a thin AIDL client + capability
dispatcher.

### Production wiring (new)

`PluginLoader.android(context, scope)` builds the singleton
graph:

```
PluginSandboxConnection(appContext)
  ├─ owns the bindService lifecycle + ref counting (step 3)
  └─ death callback → SandboxRouter.handleSandboxDeath

SandboxBridgeDispatcher
  ├─ ConcurrentHashMap<sandboxToken, PluginBridge>
  ├─ ConcurrentHashMap<sandboxToken, LifecycleListener>
  └─ bridgeCall(sandboxToken, ...) → bridges[token].call(...)

SandboxRouter(connection, dispatcher)
  ├─ allocateToken() — monotonic Int per host process
  ├─ loadPlugin(parcel) — suspend; pre-registers handle, fires AIDL
  ├─ IPluginHostCallback.Stub inner class — generation-fenced
  │   dispatch into bridgeDispatcher
  └─ handleSandboxDeath() — atomic remove per token + dispatch

SandboxedPluginInstanceFactory
  ├─ allocateToken
  ├─ build PluginInstance (sandboxHandle=null initially)
  ├─ build SandboxBridgeDelivery + PluginBridge
  ├─ registerBridge + registerLifecycleListener (RegistryUnloadListener)
  ├─ build PluginLoadParcel (incl. sha256 of bundle bytes)
  ├─ sandboxRouter.loadPlugin(parcel)   <- AIDL across processes
  │   on throw: unregisterBridge + rethrow
  └─ assign instance.sandboxHandle = handle; return instance
```

### What got deleted

- `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/PluginWebViewClient.kt`
  (entire file — isolation rules now live in the sandbox's
  `RealPluginWebViewHost.ReadinessWebViewClient`, ported in
  step 5e).
- The private `PluginHtmlShell` object inside `PluginLoader.kt`
  (the duplicate in `:feature:plugin-sandbox` is the only copy
  now).
- The deprecated `PluginBridge(plugin, webViewProvider: () -> WebView?, ...)`
  secondary constructor.
- `WebViewBridgeDelivery` (the legacy in-process [BridgeDelivery]
  impl) — only `SandboxBridgeDelivery` remains.
- `PluginInstance.webView: WebView?` field — replaced with
  `sandboxHandle: SandboxHandle?`.
- `AndroidPluginInstanceFactory` (which built a host-process
  WebView) — replaced with `SandboxedPluginInstanceFactory`.
- `implementation(libs.androidx.webkit)` in
  `:feature:plugin-host/build.gradle.kts`.

### What changed

- `PluginInstance` now holds `var sandboxHandle: SandboxHandle?`
  and `var bridge: PluginBridge?`. `dispatchHook` / `destroy`
  fire-and-forget through the handle's coroutine path.
- `PluginBridge` is no longer a `@JavascriptInterface` — plain
  capability dispatcher invoked by `SandboxBridgeDispatcher.bridgeCall`.

## Files in scope

- `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/PluginLoader.kt`
- `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/PluginInstance.kt`
- `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/PluginBridge.kt`
- `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/BridgeDelivery.kt`
- `android/feature/plugin-host/build.gradle.kts`
- Deletion: `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/PluginWebViewClient.kt`

## Concerns I want a second opinion on

1. **`RegistryUnloadListener.onPluginCrashed` calls
   `PluginRegistry.unload(pluginId)` which fires
   `instance.destroy()` which fires `sandboxHandle.unload()`.**
   But the sandbox already died (that's what triggered the
   crash). `sandboxHandle.unload()` calls
   `sandboxRouter.unload(handle)` which does
   `connection.acquire()` → `sandbox.unloadPlugin(handle.sandboxToken)`.
   `acquire()` may try to re-bind a dead service. Worth tightening?

2. **PluginRegistry.put eagerly destroys the existing instance
   for the same pluginId** (existing behavior pre-step-6). With
   the new fire-and-forget destroy, the old instance's
   `sandboxHandle.unload()` races the new load's
   `sandboxRouter.loadPlugin()` — different `sandboxToken` for
   each, so they shouldn't conflict at the AIDL layer, but the
   ref-counted connection might see acquire → acquire → release
   → release where the orderings interleave. Triad 117/118 ref-
   count fixes should cover this, but worth eyes.

3. **`PluginLoader.android()` minted singletons per call**
   (every call constructs a new SandboxRouter etc.). That's
   probably fine because no caller currently invokes this
   factory (it's wiring scaffolding for a future Hilt module),
   but if it grew callers it'd break — two `SandboxRouter`s
   would each have their own token namespace racing the same
   `PluginSandboxConnection`. Should it be `@Singleton` already?

4. **`SandboxedPluginInstanceFactory.create` builds the
   `PluginLoadParcel` with `diagnosticManifestJson = ""`.** The
   Phase 10a v4 spec carries the manifest JSON for telemetry
   only (never as a grant source). Should I be threading the
   real JSON through here?

5. **`auditLogger.denied(pluginId, "webview_error_$code", message)`**
   in `RegistryUnloadListener.onWebViewError` — encoding the
   error code into the type field. Acceptable shape for the
   auditor, or should the dispatcher have its own error type?

## Test status

- `:feature:plugin-host:testDebugUnitTest` passes (PluginLoaderTest
  unchanged — its mock `PluginInstanceFactory` builds bare
  `PluginInstance(manifest, capabilities, path)` with no
  handle).
- `:feature:plugin-sandbox:testDebugUnitTest` passes (6 tests).
- `:app:assembleDebug` succeeds.
- No `android.webkit.WebView` import remains in
  `:feature:plugin-host` (only references are `onWebViewError`
  AIDL method names + doc comments).

## Output

Per reviewer, terse:

1. Verdict on step 6: GREEN / YELLOW / RED + items.
2. Anything still missing before this can ship.
3. Anything new.

If dual-GREEN, V1.5 Phase 10 (multi-process plugin host) closes.
Next track is Phase 11 (native Kotlin plugin runtime) or Phase 9
(per-device envelope encryption).

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
