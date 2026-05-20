# M4b — Secure WebView plugin host (Android)

You completed M1–M3 (server) and M4a (JS plugin SDK). M4b implements the Android-side host that:
- Loads signed plugin bundles
- Verifies the Ed25519 signature against the sender's public key
- Runs the plugin in an isolated WebView with a strict JS bridge
- Enforces declared capability + endpoint allowlists
- Provides the `platform.*` API surface defined by M4a

Workspace-write granted to `d:\Projects\syncler\`. Touch only `android/`.

## Scope of M4b

Add to the existing Android multi-module project a new module `feature:plugin-host` plus supporting changes in other modules.

### `android/feature/plugin-host/build.gradle.kts` — NEW
Depends on `:core:crypto`, `:core:storage`, `:core:network`, AndroidX WebKit, AndroidX Security, BouncyCastle, Compose.

### Module structure

```
android/feature/plugin-host/
  src/main/AndroidManifest.xml
  src/main/kotlin/app/syncler/android/pluginhost/
    PluginHostActivity.kt           # bounded isolated process for plugin execution
    PluginWebViewClient.kt          # blocks navigation, file URLs, http: in production
    PluginBridge.kt                 # @JavascriptInterface implementations (the platform.* API)
    PluginLoader.kt                 # download bundle, verify signature, write to per-plugin storage
    PluginSignatureVerifier.kt      # Ed25519 verification + manifest hash check
    PluginManifest.kt               # data class mirroring M4a's PluginManifest
    PluginInstance.kt               # holds the loaded plugin's WebView + bridge + state
    PluginRegistry.kt               # in-memory map of plugin_id -> PluginInstance
    PluginPermissionStore.kt        # which capabilities user granted per-plugin per-device
    EndpointMatcher.kt              # implements the same wildcard matching as M4a network.ts
    capabilities/
      NetworkBridge.kt              # platform.network.fetch routed through native OkHttp w/ allowlist
      StorageBridge.kt              # platform.storage.{get,set,delete} backed by encrypted Room
      NotificationBridge.kt         # platform.showNotification routes to NotificationManager
      CameraBridge.kt
      GalleryBridge.kt
      FileBridge.kt
      LocationBridge.kt
      MessageBridge.kt              # platform.message.{respond,dismissBehavior}
  src/main/AndroidManifest.xml
  src/test/kotlin/app/syncler/android/pluginhost/
    PluginSignatureVerifierTest.kt
    EndpointMatcherTest.kt
    PluginLoaderTest.kt              # round-trip: sign, verify, load
```

## Critical security requirements (per Gemini's Round 7 attack)

### 1. Bounded isolated plugin-host process
Plugin host runs in a separate Android process via `android:process=":pluginhost"` on `PluginHostActivity` in the manifest. The host process has its own data dir, separate from the main app. Inter-process communication via `Messenger` or `AIDL` for the bridge calls.

### 2. WebView hardening
- `WebSettings.setJavaScriptEnabled(true)` (required for plugin JS to run) — that's the only relaxation
- `setAllowFileAccess(false)`
- `setAllowFileAccessFromFileURLs(false)`
- `setAllowUniversalAccessFromFileURLs(false)`
- `setAllowContentAccess(false)`
- `setMixedContentMode(MIXED_CONTENT_NEVER_ALLOW)`
- Custom `WebViewClient` that:
  - `shouldOverrideUrlLoading`: returns `true` for ANY URL (no navigation allowed)
  - `shouldInterceptRequest`: blocks all requests except the initial `file:///plugin/index.html` self-load
- No `WebChromeClient` callbacks granted (no geo, no media unless explicitly required by capability + user grant)

### 3. JavaScriptInterface narrowness
- ONE `@JavascriptInterface` named `__syncler_native__` exposing a single method:
  ```kotlin
  @JavascriptInterface
  fun call(method: String, argsJson: String, callbackId: String): Unit
  ```
- All calls serialize through this single entrypoint. NO multi-method bridges.
- Response is delivered asynchronously via `WebView.evaluateJavascript("window.__syncler_internal_callback('$callbackId', $resultJson)", null)`

### 4. Bundle verification
- `PluginLoader.load(manifestUrl: String, expectedSenderPublicKey: ByteArray): Result<PluginInstance>`:
  1. Fetch manifest JSON (no caching).
  2. Compute `canonical_manifest_without_signature` per the crypto spec.
  3. Concatenate with hex-decoded `bundleHash`.
  4. Verify Ed25519 signature against `expectedSenderPublicKey`.
  5. If signature invalid → return Error, don't even download the bundle.
  6. Fetch bundle from `signed_bundle_url`.
  7. Compute SHA-256 of bundle bytes; compare to `manifest.bundleHash`.
  8. If hash mismatch → reject.
  9. Write bundle to per-plugin storage (encrypted, isolated path).
  10. Create `PluginInstance` with new WebView + bridge bound to the granted capabilities.

### 5. Capability enforcement
- Every `__syncler_native__.call("platform.X.Y", ...)` checks the plugin's granted capabilities BEFORE delegating to the capability bridge.
- Missing capability → callback receives `{ error: "capability_not_granted", capability: "X" }`.

### 6. Endpoint allowlist
- `NetworkBridge.fetch(plugin, url, options)`:
  - Run `EndpointMatcher.matches(url, plugin.manifest.declaredEndpoints)`. False → reject with `endpoint_not_declared`.
  - Otherwise use OkHttp with strict TLS + no cookie jar shared with the main app.

### 7. Crash containment
- WebView is wrapped in a try/catch over its lifecycle. On render crash (`onRenderProcessGone`), the plugin is unloaded and the user notified (later UI; M4b just logs).
- A per-call timeout of 30s on bridge invocations. Timeout → callback receives `{ error: "timeout" }`.

### 8. Audit log
- All denied bridge calls (`capability_not_granted`, `endpoint_not_declared`, signature failure, etc.) logged to a dedicated `pluginhost_audit.log` via Timber tagged `PLUGIN_AUDIT`.

## Bridge call protocol (matches M4a)

```
Host -> Plugin (load):
  WebView.loadData(htmlShell, "text/html", "utf-8")
  where htmlShell embeds the bundled plugin.bundle.js + a tiny init script
  that calls plugin.onMessage(payload) and returns via __syncler_internal_callback
```

```
Host -> Plugin (dispatch a hook):
  WebView.evaluateJavascript("__syncler_internal_dispatch('onMessage', [<payloadJson>], '<callbackId>')", null)
  Plugin runs, eventually calls window.__syncler_native__.call("platform.X", argsJson, callbackId)
```

```
Plugin -> Host (call platform API):
  window.__syncler_native__.call(method, argsJson, callbackId)

Host -> Plugin (deliver result):
  WebView.evaluateJavascript("window.__syncler_internal_callback('<callbackId>', <resultJson>)", null)
```

## Tests

- `PluginSignatureVerifierTest`: round-trip with known keypair (from M2 test vectors); tampered manifest fails; tampered signature fails; tampered bundleHash fails
- `EndpointMatcherTest`: exact match, wildcard path, wildcard subdomain, mismatch
- `PluginLoaderTest`: end-to-end load of a tiny example plugin (assets test resource); reject on tampered manifest; reject on bundle hash mismatch

## Constraints
- Min SDK 26; uses AndroidX WebKit safe-browsing APIs gated by `WebViewFeature.isFeatureSupported`
- All async via coroutines
- No external WebView libraries (just AndroidX); no Crosswalk
- All capability bridges respect a `Dispatchers.IO` boundary for I/O
- The plugin's WebView is recreated on each load — no reuse across plugins

## Print summary
- Module files created
- Process isolation method confirmed (`:pluginhost` process)
- How to run tests + the example plugin load
- Audit log path
