# Multi-Process Plugin Host (V1.5 runtime #1, Phase 10)

Status: **design** — Phase 10a (this document) locks the AIDL contract,
process lifecycle, and migration plan. Phase 10b implements.

## Why

The current plugin runtime runs every plugin's JavaScript bundle inside
a `WebView` in the host UI process (`app.syncler.android`). A buggy or
malicious plugin can:

- Crash the host process and take down the inbox UI.
- Starve the main thread with synchronous JS work.
- Reach into the host's address space via WebView/JNI bugs.
- Stay alive past the user's explicit removal because the OS can't
  selectively kill it.

Moving plugin execution to a dedicated Android process (`:plugin`)
fixes all four:

- A plugin crash kills only `:plugin`. The host stays up.
- The OS can kill `:plugin` for memory pressure without touching the
  inbox.
- The `ProcessRecord` boundary blocks memory-corruption / type-
  confusion exploits from leaving the sandbox.
- We can kill `:plugin` deterministically when the user revokes a
  pairing.

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│ Host process (app.syncler.android)                             │
│                                                                │
│   PluginHostActivity / inbox UI                                │
│              │                                                 │
│              ▼                                                 │
│   PluginRegistry  ←──────┐                                     │
│              │            │ result callbacks                   │
│              ▼            │                                    │
│   PluginBridge (capability dispatch)                           │
│       │  │  │                                                  │
│       │  │  └──→ NetworkBridge, StorageBridge, …               │
│       │  │                                                     │
│       │  ▼  (AIDL: IPluginSandbox)                             │
│  ╔════╪══════════════════════════════════════════════════════╗ │
│  ║    │   :plugin subprocess (app.syncler.android:plugin)    ║ │
│  ║    │                                                       ║│
│  ║    │  PluginSandboxService (BoundService)                  ║│
│  ║    │           │                                           ║│
│  ║    │           ▼                                           ║│
│  ║    │  WebView (one per plugin id)                          ║│
│  ║    │           │                                           ║│
│  ║    └──────────▶│ JavascriptInterface __syncler_internal    ║│
│  ║                │                                           ║│
│  ║                └─ AIDL: IPluginHostCallback ───────────────╫┘
│  ╚═══════════════════════════════════════════════════════════╝
└────────────────────────────────────────────────────────────────┘
```

### Process

`AndroidManifest.xml` declares the sandbox process by attribute:

```xml
<service
    android:name="app.syncler.feature.pluginhost.sandbox.PluginSandboxService"
    android:process=":plugin"
    android:exported="false" />
```

The leading colon makes it a private process owned by the host app —
it shares the host's UID and package signature, so we don't pay any
permission cost. It's a separate `ProcessRecord` for the OS scheduler
and OOM-killer.

### Two AIDL interfaces

`IPluginSandbox` — host → subprocess. Methods are called by the host
on the bound service to drive the sandbox.

```aidl
interface IPluginSandbox {
    // Lifecycle
    int loadPlugin(in PluginLoadRequest request);  // returns sandboxToken
    void unloadPlugin(int sandboxToken);
    void registerHostCallback(int sandboxToken, IPluginHostCallback callback);

    // Host → plugin event delivery
    void dispatchHook(int sandboxToken, String hook, String payloadJson, String callbackId);
    void deliverBridgeResult(int sandboxToken, String callbackId, String resultJson);
}
```

`IPluginHostCallback` — subprocess → host. The host implements it
and passes it via `registerHostCallback`. The subprocess calls back
into the host when the WebView's `__syncler_internal.call(...)`
fires or the plugin reports a lifecycle event.

```aidl
interface IPluginHostCallback {
    // Plugin → host bridge call
    void bridgeCall(String method, String argsJson, String callbackId);

    // Lifecycle / diagnostics
    void onWebViewError(String code, String message);
    void onPluginReady();
    void onPluginCrashed(String reason);
}
```

### Parcelable types

`PluginLoadRequest` — what the host hands the sandbox to mount a
plugin. Stays minimal so the IPC payload is small and the sandbox
doesn't need to know about host-side concepts (sessions, keys, etc.):

```aidl
parcelable PluginLoadRequest {
    String pluginId;          // canonical id from manifest
    String bundleFilePath;    // absolute path the sandbox can read
    String bundleHashHex;     // for integrity reverify in the sandbox
    String manifestJson;      // raw JSON the SDK validated
    long timeoutMillis;       // load timeout
}
```

The bundle is staged to a path readable by both processes
(`getCacheDir()` under the shared app UID works). The sandbox
**re-verifies** the bundle hash before evaluating JS. The host
performs the same check before staging — the duplication is
defense-in-depth against a corrupt bundle path or a TOCTOU race
during staging.

## Lifecycle

1. **Bind.** First plugin load triggers
   `bindService(PluginSandboxService.intent(), connection, BIND_AUTO_CREATE)`.
   Connection is held by a `PluginSandboxConnection` singleton.

2. **Register callback.** Host calls
   `registerHostCallback(sandboxToken, ...)` immediately after
   `loadPlugin` returns the token.

3. **Steady state.** Host dispatches hooks, sandbox calls back for
   bridge methods. Each capability call round-trips through the host.

4. **Unload.** When the user revokes a pairing or the plugin
   misbehaves, host calls `unloadPlugin(sandboxToken)`. Sandbox
   destroys the WebView and clears its handle.

5. **Sandbox death.** If `:plugin` dies (OOM, native crash, manual
   `Process.killProcess`), `ServiceConnection.onServiceDisconnected`
   fires in the host. The host invalidates every outstanding
   `sandboxToken` and surfaces a single `onPluginCrashed("process_died")`
   event per loaded plugin. The next plugin operation re-binds.

6. **Host death.** Less interesting — the OS tears down `:plugin`
   when the bound client goes away.

## Threading

- **AIDL transactions on `IPluginSandbox`** arrive on a Binder thread
  in the sandbox process. The sandbox marshals to its main thread
  before touching the WebView (which is single-threaded).
- **Bridge callbacks on `IPluginHostCallback`** arrive on a Binder
  thread in the host process. The PluginBridge dispatches to its
  existing `CoroutineScope` so capability calls don't block Binder.
- **Hook delivery and result delivery are fire-and-forget oneway**
  on the AIDL methods (`oneway` keyword). Capability results come
  back via `deliverBridgeResult` on the next round trip.

## Migration path (Phase 10b implementation order)

1. Add a new `:feature:plugin-sandbox` Gradle module that ships the
   `PluginSandboxService`. New module so `:plugin` doesn't pull in
   the host's UI / coroutine / DI graph.
2. Extract `WebView`-touching code (currently in `PluginInstance` +
   `PluginLoader`) into the sandbox module.
3. Define AIDL files in `:core:plugin-aidl` (new module) so both
   sides share generated stubs.
4. Re-wire `PluginRegistry` + `PluginBridge` to talk through
   `PluginSandboxConnection` instead of holding `WebView` directly.
5. Strip the in-process `WebView` from the existing
   `:feature:plugin-host`.
6. End-to-end test: load → dispatch → bridge call → result → unload,
   then assert killing `:plugin` recovers cleanly.

Each step is its own commit; Phase 10b ships incrementally.

## Security boundaries

- **The sandbox cannot read host SharedPreferences, Hilt singletons,
  or the user session.** It only sees `PluginLoadRequest` payloads.
- **Capabilities stay in the host.** The sandbox never gets a
  network handle, camera handle, or the master key. Every privileged
  operation crosses an AIDL boundary that the host gates against
  the plugin's manifest-declared capabilities.
- **Bundle integrity is re-verified in the sandbox.** A compromised
  bundle file path (TOCTOU between host stage and sandbox read)
  trips the hash check.
- **The sandbox process runs under the same UID as the host.** That
  is intentional — separate UIDs would block file sharing without
  helping the threat model (a UID-jail wouldn't prevent the plugin
  from talking to attacker-controlled servers via the network
  capability anyway, and we already gate that at the host bridge).

## Error semantics

- **AIDL call throws RemoteException.** Sandbox is gone. Host
  fires `onPluginCrashed("remote_exception")` and rebinds.
- **`onWebViewError` from sandbox.** Logged to the existing
  `AuditLogger`; plugin marked errored; no further hooks dispatched
  until reload.
- **Bridge call timeout.** Existing `CALL_TIMEOUT_MS` still applies
  on the host side. The sandbox doesn't enforce its own timeout —
  the host is the authority.
- **Process kill while load in flight.** `loadPlugin` becomes a
  `RemoteException` mid-call; host treats it as a generic load
  failure and the UI surfaces a retry.

## Non-goals for Phase 10

- **Multi-process for renderer-only template plugins.** Templates
  don't run JS. They render via Compose in the host UI and have
  no attack surface needing process isolation.
- **Multi-process across distinct plugins.** Phase 10 puts ALL
  plugins in the single `:plugin` process. A second isolation
  level (one process per plugin id) is a future "plugin-per-process"
  follow-up if real-world abuse warrants it.
- **Native Kotlin AAR loading.** That's V1.5 #2 / Phase 11 —
  builds on the boundary Phase 10 establishes but is its own
  implementation track.

## Test plan

- Unit tests: AIDL `Parcelable` round-trip for `PluginLoadRequest`.
- Integration: bind + load + dispatch + unload happy path against
  the test sandbox build flavor (no actual WebView, just records
  AIDL calls).
- Resilience: `Process.killProcess(plugin process pid)` mid-dispatch
  → host fires `onPluginCrashed` and recovers on next operation.
- Capability: bridge call from sandbox → host → response round-trip
  matches the existing in-process behavior byte-for-byte.

## Rollout

Phase 10b ships behind no feature flag — it's a full replacement
of the in-process path. V0.1 means no production users; the
existing test fixtures will be updated alongside the implementation.

Phase 10c (future, optional) adds telemetry on sandbox bind /
unbind / crash rates so we can size the timeout constants.
