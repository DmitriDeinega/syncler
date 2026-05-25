# Native Kotlin Plugin Runtime (V1.5 runtime #2, Phase 11)

Native Kotlin plugins are a second plugin format alongside the existing JS/WebView path. Plugins ship as a signed DEX (Dalvik Executable) and run in their own Android isolated process — distinct from the V1.5 #1 `:plugin` process that hosts WebView-backed JS plugins.

Design converged across four triad iterations (132 → 135) at `.triad/135-phase11a-design-v4-prompt.md`. This document is the spec digest.

## Why

JS plugins (Phase 10) bottom out at WebView's hardening surface. That's fine for capability-mediated logic but pays a performance + ergonomic tax for pure compute, async I/O orchestration, or plugins that want typed Kotlin coroutines instead of JSON over a bridge. Native Kotlin plugins keep the SAME capability dispatch path as JS (audit log, rate limits, grant checks all reuse Phase 10 byte-for-byte) but expose it as typed Kotlin function calls.

Two non-negotiable security goals:

1. **No access to host app data.** A malicious native plugin must not read `/data/data/<host_pkg>/...`, the user's master key, or any other host process state.
2. **No bypassing the capability dispatch.** Direct `java.net.URL.openConnection()` or `java.io.File("/sdcard/x")` must fail at the OS, not in the SDK.

These are achieved by running each native plugin in `android:isolatedProcess="true"`. The isolated UID has no INTERNET, no host data access, no most-system-services. The ONLY way out is the AIDL Binder back to the host process.

## Threat model

The synthesized isolated UID is the security boundary. Everything inside the plugin's process is plugin-controlled; everything outside is OS-enforced.

**What the isolated process protects against:**

- Host app data theft (different UID, no `/data/data/<host_pkg>` access).
- Direct network access (no INTERNET grant).
- Direct system service abuse (camera, location, contacts — only the audited capability path reaches them via the host).
- Direct file system writes outside the plugin's ephemeral private dir.
- Process-global state mutation that affects the host or sibling plugins.

**What it does NOT protect against:**

- A plugin OOMing its own process (host re-binds; only THAT plugin dies).
- A plugin holding the bounded coroutine dispatcher hostage (host enforces `onInit` timeout; per-hook timeouts in V2+).
- A plugin reading its own DEX bytes back from memory (it has them anyway — they're its own code).
- ClassLoader isolation is NOT the security boundary. It's organizational only.

**Cross-plugin isolation:** each native plugin runs in its OWN isolated process via `Context.bindIsolatedService(instanceName=sandboxToken.toString())`. Two native plugins NEVER share heap, ClassLoader, or coroutine scopes. Plugin A crashing/being malicious affects only Plugin A.

## Bundle format

DEX (Dalvik Executable) bytes only. No AAR (resources/manifests/native libs are V2+ scope). The published bundle is the DEX file; SHA-256 of the bytes is the manifest's `bundleHash`.

**Size cap**: 4 MB. Enforced at host staging time AND in the sandbox while reading the FD. Both fail with `LoadFailureCodes.DEX_TOO_LARGE`.

**Forbidden class-name prefixes**: plugins MUST NOT declare classes under any of these:

- `app.syncler.*` — host + SDK runtime; shadowing would let a plugin spoof `app.syncler.plugin.runtime.PluginContext`.
- `android.*`, `androidx.*` — framework.
- `kotlin.*`, `kotlinx.*` — stdlib + coroutines.
- `java.*`, `javax.*` — Java SE.

The host scans the DEX header at staging time and rejects with `forbidden_package_prefix`. The sandbox repeats the scan as defense-in-depth before instantiating the ClassLoader.

## Manifest fields

Two new fields on `PluginManifest`, both required when `renderer == "native_kotlin"`:

```json
{
  "renderer": "native_kotlin",
  "entry_class": "com.example.weather.WeatherPlugin",
  "native_sdk_abi": 1,
  ...
}
```

- `entry_class`: fully qualified Kotlin/Java binary class name. Regex `^([A-Za-z_$][A-Za-z0-9_$]*\.)*[A-Za-z_$][A-Za-z0-9_$]*$`, max 256 chars. Must be present in the DEX. Must implement `app.syncler.plugin.runtime.SynclerPlugin`. Must have a no-arg constructor.
- `native_sdk_abi`: integer matching the host's `NATIVE_SDK_ABI` constant. Mismatch fails with `unsupported_sdk_abi` BEFORE the DEX is loaded.

V1.5 ships `NATIVE_SDK_ABI = 1`. Every breaking change to `SynclerPlugin` / `PluginContext` bumps the int.

## PluginLoadParcel additions

```kotlin
data class PluginLoadParcel(
    ...,
    val renderer: String,       // "script" | "native_kotlin" | "template" (template = host-side, no sandbox call)
    val bundleFilePath: String, // used by JS path; native uses bundleFd instead
    val bundleHashHex: String,
    val entryClass: String,     // NEW — required when renderer == "native_kotlin"
    val nativeSdkAbi: Int,      // NEW — required when renderer == "native_kotlin"
    ...,
) : Parcelable
```

AIDL `loadPlugin` gains a third argument:

```aidl
int loadPlugin(
    in PluginLoadParcel request,
    in IPluginHostCallback callback,
    in ParcelFileDescriptor bundleFd  // NEW; nullable for JS plugins
);
```

For native plugins, `bundleFd` carries the DEX bytes. The host opens the staged file in the host process (host UID has access); the sandbox reads via `AutoCloseInputStream(bundleFd).use { ... }`. For JS plugins, `bundleFd` is null and the existing `bundleFilePath` path remains (the `:plugin` process is not isolated and can read host storage).

## Process model

```
Host process (UID 10042)
 ├─ PluginSandboxConnection
 ├─ SandboxRouter
 │   ├─ bindService(:plugin) for JS — shared, NOT isolated
 │   └─ bindIsolatedService(:nativePlugin, instanceName=token) per native plugin
 ├─ PluginBridge instances (capability dispatch) — one per sandboxToken

:plugin process (UID 10042, shared with host)
 └─ Phase 10b WebView-backed JS plugins

:nativePlugin process(es) — one isolated UID per active native plugin
 └─ Each: PluginNativeSandboxService.Stub →
          RealNativePluginHost(InMemoryDexClassLoader(verified bytes)) →
          plugin instance (SynclerPlugin entry_class).
```

`bindIsolatedService` was added in API 29. Native plugins require minSdk 29. JS plugins keep minSdk 26. On older devices the renderer-routing layer rejects native publish with `native_only_api_29`.

## Per-plugin lifecycle

Each native plugin's lifecycle is independent:

```
loadPlugin(token, parcel, callback, bundleFd)
  → host opens FD, host validates DEX size + forbidden prefixes
  → bindIsolatedService(name=:nativePlugin, instanceName=token.toString())
  → spawn synthesized-UID process
  → PluginNativeSandboxService.onBind returns IPluginSandbox.Stub
  → host calls sandbox.loadPlugin(parcel, callback, fd)
  → sandbox re-verifies SHA-256 against parcel.bundleHashHex
  → sandbox re-scans forbidden prefixes
  → sandbox checks parcel.nativeSdkAbi == NATIVE_SDK_ABI; mismatch fails BEFORE class load
  → sandbox builds InMemoryDexClassLoader(bytes, parent=SDK runtime classloader)
  → sandbox resolves parcel.entryClass via classloader.loadClass
  → sandbox instantiates entry class via no-arg constructor
  → sandbox creates bounded coroutine dispatcher (1..4 threads)
  → sandbox launches plugin.onInit(context) under withTimeoutOrNull(10_000)
  → on success → hostSignals.reportReady() → callback.onPluginReady(token)
  → on failure → hostSignals.reportError(code, msg) → callback.onWebViewError(...)
                  (then cancel init child job, NOT the whole scope)
  → on timeout → hostSignals.reportError(INIT_TIMEOUT, "") FIRST, THEN cancel job

unloadPlugin(token)
  → sandbox.scope.cancel()
  → process becomes idle
  → ref-counted unbind triggers Android to kill the isolated process

process death (ServiceConnection.onServiceDisconnected)
  → that token's binding fires onDeath(token)
  → SandboxRouter removes ONLY token's handle; siblings unaffected
```

## Capability dispatch (unchanged from Phase 10)

The plugin's `PluginContext` methods are typed Kotlin wrappers over the same `bridgeCall(method, argsJson, callbackId)` AIDL that JS plugins use. The host's `PluginBridge` dispatches capability work in the HOST process (because the isolated UID can't actually do network / camera / storage anyway) and returns the result via `IPluginSandbox.deliverBridgeResult`.

Audit log, rate limit, capability grant check — all reused from Phase 10 byte-for-byte. The only difference is the JS plugin marshals the call via `__syncler_native__.call(...)` while the native plugin marshals via a typed Kotlin method.

**Bridge call attribution: by host-allocated `sandboxToken`, never by plugin-supplied identifiers.** The sandbox's IPluginSandbox.Stub forwards the call with the token it was bound to; the host's PluginHostCallbackStub already has `expectedToken` pinned (Phase 10 design).

## SDK runtime API

The new `:plugin-sdk-runtime` Android library module is parent-provided (not bundled in the plugin DEX). Plugins compile against it as `compileOnly`.

```kotlin
package app.syncler.plugin.runtime

interface SynclerPlugin {
    suspend fun onInit(ctx: PluginContext): Result<Unit>
    suspend fun onInbox(ctx: PluginContext, event: InboxEvent): Result<Unit> =
        Result.success(Unit)
    suspend fun onAction(ctx: PluginContext, event: ActionEvent): Result<Unit> =
        Result.success(Unit)
}

interface PluginContext {
    val pluginId: String
    val grantedCapabilities: Set<String>

    suspend fun networkFetch(req: NetworkRequest): NetworkResponse
    suspend fun storage(): PluginStorage
    suspend fun showNotification(n: Notification): Result<Unit>
    suspend fun cameraCapture(opts: CameraOptions): Result<CameraResult>
    suspend fun galleryPick(opts: GalleryOptions): Result<GalleryResult>
    suspend fun filePick(opts: FileOptions): Result<FileResult>
    suspend fun locationCurrent(opts: LocationOptions): Result<LocationResult>
    suspend fun messageRespond(actionId: String, payload: ByteArray): Result<Unit>
}
```

API surface matches the JS `platform.*` set so the audit / grant model is identical.

## LoadFailureCodes (Phase 11 additions)

```kotlin
const val DEX_TOO_LARGE = "dex_too_large"
const val UNSUPPORTED_SDK_ABI = "unsupported_sdk_abi"
const val INIT_TIMEOUT = "init_timeout"
const val FORBIDDEN_PACKAGE_PREFIX = "forbidden_package_prefix"
const val ENTRY_CLASS_NOT_FOUND = "entry_class_not_found"
const val ENTRY_CLASS_INVALID = "entry_class_invalid"  // not a SynclerPlugin, no no-arg ctor, etc.
const val NATIVE_ONLY_API_29 = "native_only_api_29"
```

All Phase 10 codes (`parcel_malformed`, `bundle_hash_mismatch`, etc.) remain.

## Implementation order (Phase 11b)

1. Spec digest (this file).
2. Server manifest extensions + migration 0012.
3. AIDL: PluginLoadParcel adds entryClass + nativeSdkAbi (WIRE_VERSION bump to 2); loadPlugin gains bundleFd.
4. New `:feature:plugin-sandbox` service `PluginNativeSandboxService` with `android:isolatedProcess="true"`; `RealNativePluginHost` with DEX loader + bounded dispatcher.
5. New `:plugin-sdk-runtime` module with `SynclerPlugin` / `PluginContext` interfaces.
6. SandboxRouter routing by renderer; per-token ServiceConnection via `bindIsolatedService`.
7. Per-token death tracking decoupled from JS shared-process death.
8. Tests: per-token native death (siblings survive), JS shared death reaps all JS, forbidden-prefix rejection, ABI mismatch before code load, init timeout cancellation order.
9. Mid-track triad on the implementation.

## Non-goals for Phase 11

- **AAR support.** Resources, manifest merging, native libraries (.so) are V2+.
- **Multi-DEX bundles.** A plugin ships one classes.dex; multi-dex requires merging which adds complexity.
- **JNI.** Native code (.so) isn't loadable from `InMemoryDexClassLoader` and the isolated UID can't `System.load(path)` anyway. V2+ if real demand surfaces.
- **Plugin-to-plugin communication.** Each native plugin is isolated from others by design.
- **Persistent plugin state outside the audited `platform.storage` capability.** Plugins can write to their isolated process's ephemeral dir but it disappears on process death.
- **Native plugin UI surfaces beyond what the existing template renderer covers.** Native plugins can return payloads for the host's template renderer; they can't bring their own Views.
- **Forward-compatible ABI**. Every breaking change to `SynclerPlugin` / `PluginContext` bumps `NATIVE_SDK_ABI` and requires plugin re-publish. No ABI compatibility shims.

## Spec history

Phase 11a design: triad consultations 132 (v1 RED), 133 (v2 YELLOW), 134 (v3 YELLOW), 135 (v4 GREEN). Source-of-truth artifact is `.triad/135-phase11a-design-v4-prompt.md`. Codex GREEN at 135; Gemini GREEN at 135 (quota recovered).
