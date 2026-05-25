=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 132 — Phase 11a: native Kotlin plugin runtime (DESIGN v1)

V1.5 #2 from `docs/ROADMAP.md`:

> "Native Kotlin plugin runtime (out-of-process). A second
> plugin format alongside the JS bundle: a signed AAR loaded
> into the isolated process. Performance + platform-API parity
> for plugin authors who want a native experience, with the
> IPC boundary preventing in-process ClassLoader poisoning."

Phase 10 (multi-process plugin host) and Phase 9 (per-device
envelope encryption) are shipped. Phase 11 builds on Phase 10's
`:plugin` subprocess boundary.

Stage: design only. Asking for: spec review, alternative-
architecture flags, missed threat-model items.

## What ships today (background)

- `:plugin` subprocess hosts WebView-backed JS plugins.
  `PluginSandboxService` (AIDL `IPluginSandbox.Stub`) accepts
  `PluginLoadParcel(sandboxToken, pluginId, renderer="script",
  bundleFilePath, bundleHashHex, ...)` and dispatches via
  `RealPluginWebViewHost`.
- Manifest carries `renderer: Literal["script", "template"]`.
  `template` is host-side native Compose render (no plugin
  code runs). `script` is JS-in-WebView.
- Plugin bundle is currently a single JS file the WebView
  shell wraps via `PluginHtmlShell.render(bundleJs)`.
- Capability dispatch: JS calls
  `window.__syncler_native__.call(method, argsJson, callbackId)`;
  the host's `PluginBridge` runs the capability and delivers a
  result back via `SandboxRouter.deliverBridgeResult`.

## Goal — Phase 11

Add a third renderer mode: `renderer = "native_kotlin"`. The
plugin bundle is a signed AAR (Android Archive) instead of a
JS file. The plugin entry-point is a Kotlin class implementing
a `SynclerPlugin` SDK interface. The plugin runs in the same
`:plugin` subprocess Phase 10 established, isolated by its own
`ClassLoader`. Capability calls become typed Kotlin coroutine
calls instead of JSON over a JS bridge.

## Proposed design

### Wire format extensions

- `renderer: Literal["script", "template", "native_kotlin"]` —
  add the third value.
- New manifest field: `entry_class: str` — fully-qualified
  Kotlin class name (e.g. `com.example.weather.WeatherPlugin`).
  Required when `renderer == "native_kotlin"`, forbidden
  otherwise.
- `bundle_hash` semantics extended to AAR. The signed bundle
  is the AAR file; SHA-256 of the bytes is what `bundle_hash`
  hashes. Identical to today's JS bundle path.
- Manifest signing: unchanged — same Ed25519 over canonical
  manifest + bundleHash bytes.

### Plugin loading

`PluginLoadParcel` already carries `renderer: String`
(currently only "script"). Sandbox dispatches on it:

```
renderer = "script" → RealPluginWebViewHost (existing)
renderer = "native_kotlin" → RealNativePluginHost (NEW)
renderer = "template" → host-side; no sandbox invocation needed
```

`RealNativePluginHost` is the new piece:

```kotlin
class RealNativePluginHost(private val appContext: Context) : PluginWebViewHost {

    @Volatile private var plugin: SynclerPlugin? = null
    @Volatile private var pluginScope: CoroutineScope? = null

    override fun startLoad(parcel: PluginLoadParcel, hostSignals: HostSignals) {
        // 1. Verify bundle hash (defense in depth, same as
        //    RealPluginWebViewHost).
        // 2. Build PathClassLoader rooted at the AAR file path
        //    with appContext.classLoader as parent. The parent
        //    gives access to platform APIs (Android SDK, Kotlin
        //    stdlib, syncler-plugin-sdk-runtime) but NOT to host
        //    process internals — `:plugin` doesn't import the
        //    `pluginhost` package classes.
        // 3. Resolve `entry_class` via classLoader.loadClass(...)
        //    and instantiate via no-arg constructor.
        // 4. Verify the resolved class implements SynclerPlugin.
        // 5. Build a PluginContext (capability dispatch handle,
        //    coroutine scope, audit logger).
        // 6. Call plugin.onInit(context). Plugin returns Result;
        //    on Success → reportReady; on Failure → reportError.
    }

    override fun dispatchHook(hook, payloadJson, callbackId) {
        // Translate hook + payloadJson into a typed call:
        //   "init" / "inbox" / "action" / etc → plugin method.
        // Plugin processes via coroutine; result feeds back
        // through HostSignals.bridgeCall for the result delivery
        // (same machinery WebView uses for native_*.call).
    }

    override fun destroy() {
        pluginScope?.cancel()
        plugin = null
        // ClassLoader is GC'd once no class references remain.
    }
}
```

### Plugin SDK (new `:plugin-sdk-runtime` module on Android)

A small library plugins compile against (provided scope, like
Android SDK):

```kotlin
interface SynclerPlugin {
    suspend fun onInit(context: PluginContext): Result<Unit>
    suspend fun onInbox(event: InboxEvent): Result<Unit> = Result.success(Unit)
    suspend fun onAction(action: ActionEvent): Result<Unit> = Result.success(Unit)
    // Open for additional hook types in future phases.
}

interface PluginContext {
    val pluginId: String
    val capabilities: Set<String>

    suspend fun network(request: NetworkRequest): NetworkResponse
    suspend fun storage(): PluginStorage
    suspend fun showNotification(notification: Notification): Result<Unit>
    // ... mirror the platform.* JS surface
}

data class InboxEvent(val payloadJson: String, val expiresAt: Instant, ...)
data class ActionEvent(val actionId: String, val payloadJson: String)
```

Plugins implement `SynclerPlugin` with a no-arg constructor.

### Capability dispatch (native side)

The host's existing `PluginBridge` is the capability dispatcher
for the JS path — it runs capability code in the host process,
hands results back via `BridgeDelivery`. Native plugins route
through the same `PluginBridge` instance for that token: the
`PluginContext` implementation translates typed Kotlin calls
into the same `method` / `argsJson` shape, awaits the result.

This is the key design choice — **native plugins go through the
EXACT same capability-dispatch path as JS plugins**, just with
typed Kotlin wrappers instead of `__syncler_native__.call`. The
audit log, rate limits, capability grant checks all reuse Phase
10's machinery byte-for-byte.

### AAR isolation invariants

1. **ClassLoader**: each plugin gets its own `PathClassLoader`
   rooted at its AAR with `appContext.classLoader` as parent.
   No `BaseDexClassLoader.findClass` quirks; parent-first
   resolution.
2. **No reflection on host classes**: plugin code can't reach
   `app.syncler.android.pluginhost.*` because that package
   isn't on the `:plugin` classpath (sandbox module doesn't
   depend on plugin-host module). Confirmed by inspecting
   `:feature:plugin-sandbox/build.gradle.kts`.
3. **No JNI**: AAR can declare `<uses-feature>` for native
   libs, but the plugin-host explicitly extracts ONLY the
   classes.dex + assets, NOT the libs/ directory. JNI not
   in scope for V1.5.
4. **No `System.loadLibrary`**: blocked by ClassLoader
   parent-first resolution + no libs/ extraction.
5. **No file system access outside the plugin's sandbox
   directory**: capability calls go through `platform.file.*`
   which audits, so direct java.io.File would only see what
   `:plugin` itself can read (which is the AAR + the staged
   bundle file). Acceptable for V0.1 but documented as
   trust model.

### What the plugin SDK does NOT expose

- Direct context access (Android `Context` not handed to
  plugins).
- Direct Activity / View access (rendering goes through host
  template renderer for cards, or returns plaintext payload
  the host renders).
- ContentResolver / SystemService access (only via audited
  capability calls).
- Network access (only via `platform.network.fetch`).

### Manifest examples

JS plugin (today, unchanged):
```json
{
  "id": "com.example.lottery",
  "renderer": "script",
  "signed_bundle_url": "https://.../plugin.bundle.js",
  ...
}
```

Native Kotlin plugin (new):
```json
{
  "id": "com.example.weather",
  "renderer": "native_kotlin",
  "entry_class": "com.example.weather.WeatherPlugin",
  "signed_bundle_url": "https://.../plugin.aar",
  ...
}
```

### Migration / coexistence

V1.5 #1 + #2 ship the multi-process boundary AND native
runtime. No existing JS plugins break — they keep their
`"renderer": "script"` manifests. New native plugins opt in
via `"renderer": "native_kotlin"`. Both render their cards
through the same template / WebView paths.

## Open questions

1. **AAR vs DEX**: AAR is the conventional Android library
   format, but raw DEX is simpler to load + verify. DEX is
   ~50 LOC of `PathClassLoader` setup; AAR involves
   resource merging. Recommend DEX for V1.5 simplicity; AAR
   can come later if needed.

2. **Same `:plugin` process or per-plugin process**: today's
   `:plugin` is shared across all plugins (each plugin gets a
   `sandboxToken`-keyed `PluginTokenCoordinator`, but they all
   share the JVM heap). Adding native plugins to the same heap
   means a buggy native plugin can OOM the others. Per-plugin
   process is true isolation but adds bind+RPC overhead per
   plugin. Recommend same process for V1.5; per-plugin process
   is a V2+ refinement.

3. **Bundle hash semantics on AAR/DEX**: SHA-256 of the
   entire file? Or canonical form (jar/zip with metadata
   stripped)? Server doesn't care — it just stores the bytes
   the sender signed. Recommend SHA-256 of the raw bytes
   (same as JS bundle today).

4. **Threading**: the WebView path uses :plugin's main looper
   for WebView ops. Native plugins are coroutine-based — no
   main looper dependency. Recommend native plugins run on
   `Dispatchers.Default` (or a dedicated worker pool); per-
   plugin coroutine scope cancelled on unload.

5. **Crash isolation**: a native plugin throwing an uncaught
   exception is the same as today's WebView renderer crash —
   `:plugin` process dies, `SandboxRouter.handleSandboxDeath`
   fires, host re-binds. The whole-process kill is the same
   penalty for both renderer kinds. Acceptable.

6. **SDK distribution**: the `:plugin-sdk-runtime` module is
   on Android. Plugin authors compile against it as
   `compileOnly` (gradle) so the AAR doesn't ship duplicate
   copies of the SDK classes; `:plugin` provides them at load
   time. Standard Android library pattern.

7. **Native API surface vs JS API surface**: native plugins
   should expose the SAME capability set as JS (camera,
   gallery, file, location, network, storage, notification,
   message). The Kotlin signatures wrap the JSON-over-bridge
   underneath, so the audit log + rate limits are identical.
   Confirm.

8. **What's missing?** Anything I haven't flagged.

## Files to read for context

- `docs/ROADMAP.md` V1.5 #2
- `docs/plugin-host-multi-process.md` (Phase 10 spec)
- `android/feature/plugin-sandbox/.../RealPluginWebViewHost.kt`
  (the existing JS-side reference impl)
- `android/feature/plugin-sandbox/.../PluginSandboxService.kt`
  (the AIDL stub + state machine)
- `android/feature/plugin-host/.../PluginBridge.kt`
  (capability dispatch)
- `server/app/schemas.py` (manifest schema, renderer field)
- `sdk-plugin/src/...` (existing JS plugin SDK; useful for
  comparing API surface)

## Output

Per reviewer, terse:

1. Verdict on Phase 11a design v1: GREEN / YELLOW / RED + items.
2. Architectural alternatives I missed (AAR vs DEX,
   process model, etc).
3. Threat-model gaps in the ClassLoader isolation.
4. Answers to the 8 open questions.

Multiple iterations expected — Phase 9a took 5, Phase 10a took
4. Substance over speed.

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
