# Multi-Process Plugin Host (V1.5 runtime #1, Phase 10)

Status: **design v2** — Phase 10a (this document) locks the AIDL
contract, process lifecycle, and migration plan. Phase 10b implements.
v2 revisions land the triad 113 findings (security framing, callback
identity, oneway ordering, payload limits, lifecycle race, bundle
storage).

## Why

The current plugin runtime runs every plugin's JavaScript bundle inside
a `WebView` in the host UI process (`app.syncler.android`). A buggy or
malicious plugin can:

- Crash the host process and take down the inbox UI.
- Starve the main thread with synchronous JS work.
- Stay alive past the user's explicit removal because the OS can't
  selectively kill it.

Moving plugin execution to a dedicated Android process (`:plugin`)
fixes those three:

- A plugin crash kills only `:plugin`. The host stays up.
- The OS scheduler + OOM-killer treat `:plugin` independently — we
  can `Process.killProcess` deterministically when the user revokes
  a pairing.
- The `:plugin` `ProcessRecord` boundary gives the OS a clean target
  to reap on memory pressure without touching the inbox.

What it does **NOT** do (corrected from v1):

- **It does NOT protect host application data from a native exploit
  inside the WebView.** `:plugin` inherits the host's UID (it's a
  `android:process=":plugin"` declaration, not `isolatedProcess`),
  so anything in `/data/data/app.syncler.android/` is reachable from
  a code-exec exploit that escapes the WebView's renderer sandbox.
  The shared UID is an explicit V1.5 trade-off — see
  [§ Threat model](#threat-model) below.

## Threat model

The threat-classes Phase 10 addresses are **liveness and resource
isolation**:

| Threat | Phase 10 helps? | How |
|---|---|---|
| Plugin JS infinite-loops / spins the main thread | YES | spin happens in `:plugin`'s thread, not the host UI |
| Plugin JS OOM | YES | OOM-killer can reap `:plugin` independently |
| Native crash via WebView renderer bug | YES (host stays up) | crash kills only `:plugin` |
| Plugin JS reads other plugins' SharedPreferences | YES (via capability gate) | all I/O routes through host-side bridge |
| **Native exploit escapes the renderer sandbox into ARM code** | **NO** | shared UID means host data is reachable |
| Plugin tries to read host's master key from memory | NO | already failed before Phase 10; capabilities never expose it |

The shared-UID trade-off is deliberate. A separate UID
(`android:isolatedProcess="true"`) would defeat the post-native-
exploit data-read class, but it would also block direct file-
descriptor sharing for bundles. We could work around that with
`ParcelFileDescriptor`-over-AIDL — and Phase 10b should keep
that as a follow-up if real-world abuse shows up. For V1.5 the
plugin-bundle signature check + WebView's own renderer sandbox
are the primary defense against the native-exploit class, and
Phase 10 inherits both.

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
│       │  ▼  (AIDL: IPluginSandbox, oneway calls)               │
│  ╔════╪══════════════════════════════════════════════════════╗ │
│  ║    │   :plugin subprocess (app.syncler.android:plugin)    ║ │
│  ║    │                                                       ║│
│  ║    │  PluginSandboxService (BoundService)                  ║│
│  ║    │      │                                                ║│
│  ║    │      └─ per-token serial queue                        ║│
│  ║    │           │                                           ║│
│  ║    │           ▼                                           ║│
│  ║    │  WebView (one per sandboxToken)                       ║│
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
    android:name="app.syncler.feature.pluginsandbox.PluginSandboxService"
    android:process=":plugin"
    android:exported="false" />
```

Leading colon = private process owned by the host app, same UID,
same signature, different `ProcessRecord`.

## AIDL contract

Two interfaces, one shared Parcelable. **Every callback from the
sandbox to the host carries the `sandboxToken`** so the host can
route per-plugin without trusting a stashed pointer (triad 113
finding: bridge calls without token in a single shared `:plugin`
process risk token confusion).

`IPluginSandbox` — host → subprocess.

```aidl
interface IPluginSandbox {
    // Lifecycle (synchronous — caller blocks on Binder until the
    // sandbox has acknowledged. Callback is registered AS PART OF
    // loadPlugin to close the registration race that v1 had).
    int loadPlugin(in PluginLoadParcel request, in IPluginHostCallback callback);
    oneway void unloadPlugin(int sandboxToken);

    // Host → plugin event delivery (oneway = no return value, the
    // sandbox cannot block the host UI on slow plugin code).
    oneway void dispatchHook(int sandboxToken, String hook, String payloadJson, String callbackId);
    oneway void deliverBridgeResult(int sandboxToken, String callbackId, String resultJson);

    // Diagnostics (synchronous — only called from settings UI).
    String querySandboxState(int sandboxToken);
}
```

`IPluginHostCallback` — subprocess → host. Every method carries
`sandboxToken`.

```aidl
interface IPluginHostCallback {
    // Plugin → host bridge call.
    oneway void bridgeCall(int sandboxToken, String method, String argsJson, String callbackId);

    // Lifecycle / diagnostics.
    oneway void onWebViewError(int sandboxToken, String code, String message);
    oneway void onPluginReady(int sandboxToken);
    oneway void onPluginCrashed(int sandboxToken, String reason);
}
```

All event-delivery methods are explicitly `oneway`. The lifecycle
`loadPlugin` is the one synchronous call so the host knows the
returned token is valid before it dispatches anything.

### Parcelable

`PluginLoadParcel` — pre-parsed payload. **Manifest is NOT shipped
as raw JSON.** Triad 113 finding: parser divergence between host
and sandbox is a real risk for capability enforcement. The host
parses the manifest once (existing
`PluginSignatureVerifier` + `PluginManifest`), normalizes it, and
ships these fields:

```aidl
parcelable PluginLoadParcel {
    String pluginId;
    String pluginIdentifier;        // reverse-DNS, manifest-declared
    String version;                  // SemVer string
    String renderer;                 // "script" | "template"
    String bundleFilePath;           // absolute, in the host's noBackupFilesDir
    String bundleHashHex;            // SHA-256 hex; sandbox re-verifies
    String[] declaredCapabilities;   // canonical capability names
    String[] declaredEndpoints;      // network bridge allow-list
    String dismissBehavior;          // template-renderer behavior hint
    long timeoutMillis;              // load-timeout the sandbox honors
    String diagnosticManifestJson;   // raw JSON; logging/telemetry only
                                     // — NEVER consulted for grants
}
```

The `diagnosticManifestJson` field is retained for runtime telemetry
parity with the existing in-process loader — but the design
explicitly forbids using it as a source of grant authority. Grants
flow from `declaredCapabilities` + `declaredEndpoints` only.

### Bundle storage

Bundles live in **`noBackupFilesDir`**, NOT `getCacheDir()`. This
matches the existing `AndroidEncryptedBundleStore` location and
removes the cache-eviction race (triad 113: OS may purge cacheDir
between the host's verification and the sandbox's read).

The host writes the decrypted JS bundle to
`getNoBackupFilesDir()/plugin-sandbox/{pluginId}/bundle.js` before
calling `loadPlugin`. The sandbox reads from the same path. The
host wipes the staged file on `unloadPlugin` and on process exit.

Future migration path: pass the bundle bytes via
`ParcelFileDescriptor` opened on the host side and read-only on the
sandbox side. That would let us move to `isolatedProcess` later
without re-doing the staging. Phase 10b uses the staged-file path;
the ParcelFileDescriptor variant is a Phase 10c optimization.

### IPC payload caps

Binder enforces ~1 MB per transaction. Three places this matters:

- **`PluginLoadParcel`**: small. `diagnosticManifestJson` capped at
  64 KB at the host (truncate + log) so a hostile manifest can't
  blow the transaction.
- **`dispatchHook` `payloadJson`**: capped at 256 KB. Plugins that
  legitimately need larger payloads (rare — most are short JSON
  patches) get a `payload_too_large` error code in the bridge
  result and a corresponding log.
- **`deliverBridgeResult` `resultJson`** and **`bridgeCall`
  `argsJson`**: same 256 KB cap. Capabilities that legitimately
  return large data (e.g. a future `gallery.read` that streams an
  image) use a `ParcelFileDescriptor` side-channel, not the JSON
  argument bus. The bridge contract defines which methods are
  large-payload methods.

Caps are enforced on the SENDING side. Receiving side double-checks
and rejects oversize payloads with a typed `payload_too_large`
result.

## Lifecycle + state machine

A loaded plugin's `sandboxToken` is in one of these states. Every
AIDL method's behavior is defined per state:

```
                ┌──────────┐
                │  none    │
                └────┬─────┘
                     │ loadPlugin returns token
                     ▼
                ┌──────────┐
       ┌────────│ loading  │──── error during eval ───┐
       │        └────┬─────┘                          │
       │             │ onPluginReady                   │
       │             ▼                                 │
       │        ┌──────────┐                           │
       │        │  ready   │──── onWebViewError ──────▶│
       │        └────┬─────┘                           ▼
       │             │ unloadPlugin              ┌──────────┐
       │             ▼                           │ errored  │
       │        ┌──────────┐                     └────┬─────┘
       │        │ unloaded │                          │ unload
       │        └──────────┘                          ▼
       │                                       ┌──────────┐
       └─── process dies (RemoteException) ───▶│ process_ │
                                               │  dead    │
                                               └──────────┘
```

AIDL behavior per state:

| State | `dispatchHook` | `deliverBridgeResult` | `unloadPlugin` |
|---|---|---|---|
| `loading` | buffered (sandbox replays after `onPluginReady`) | buffered | OK; cancels load |
| `ready` | delivered | delivered | OK |
| `errored` | dropped + warn-log | dropped | OK; clears state |
| `unloaded` | dropped + warn-log (caller bug) | dropped + warn-log | no-op |
| `process_dead` | dropped + `onPluginCrashed` fires once | dropped | no-op |

The sandbox's per-token serial queue (one coroutine + channel per
`sandboxToken`) serializes hooks + bridge-results so concurrent
`oneway` calls from different host threads stay ordered. Triad 113
finding: Binder preserves per-IBinder per-client order, but multi-
coroutine host can interleave without an explicit queue.

### Pending callback cleanup

Each `bridgeCall` from the sandbox produces a `callbackId`. The
host maintains a per-token `Map<callbackId, ContinuationOrFuture>`.
On `unloadPlugin` or `onPluginCrashed`, the host completes every
outstanding callback with a `plugin_unloaded` or `plugin_crashed`
error so capability calls don't hang until the existing
`CALL_TIMEOUT_MS`. Triad 113 finding.

### Connection teardown

`PluginSandboxConnection` is a singleton that holds the
`ServiceConnection`. Reference-counted:

- `loadPlugin` count goes 0 → 1: `bindService(BIND_AUTO_CREATE)`.
- `unloadPlugin` count goes 1 → 0: schedule an `unbindService`
  after a 30-second idle window. New `loadPlugin` during the
  window cancels the unbind.

Triad 113 finding: without the idle-unbind, `:plugin` lives forever
even with no plugins loaded, wasting memory.

## Migration path (Phase 10b implementation order)

Triad 113 finding: e2e proves the multi-process path BEFORE the
in-process path is removed, so we have a clean git-revert.

1. **AIDL module** (`:core:plugin-aidl`). Generated stubs + the
   `PluginLoadParcel` Parcelable. Both host and sandbox depend on
   it.
2. **Sandbox module** (`:feature:plugin-sandbox`). Ships
   `PluginSandboxService`, the per-token state machine, the
   serial queue, and a build flavor that records AIDL calls
   without instantiating a real `WebView` (for tests).
3. **Connection layer** (`PluginSandboxConnection`). Singleton in
   the host that owns the binding + idle-unbind logic.
4. **`PluginBridge` rewire.** Bridge gets a `SandboxRouter`
   constructor arg that talks AIDL. WebView-touching methods are
   removed; capabilities stay in place.
5. **E2E test pass** (`:feature:plugin-host:connectedAndroidTest`
   plus a unit-level test harness in `:feature:plugin-sandbox`).
   Coverage: load → dispatch → bridge call → result → unload,
   `Process.killProcess(:plugin)` recovery, capability boundary
   round-trips.
6. **Strip in-process WebView** from `:feature:plugin-host`. Only
   after step 5 is green.

Each step is its own commit. Step 6 is the only destructive one;
the order keeps it last so step 5 failures don't leave the host
without a plugin runtime.

## Security boundaries (reconfirmed)

- **Capabilities stay in the host.** The sandbox never gets a
  network handle, camera handle, or session token. Every privileged
  op crosses the AIDL boundary that the host gates against the
  `declaredCapabilities` list in `PluginLoadParcel`.
- **Bundle integrity is re-verified in the sandbox.** Defense-
  in-depth against a corrupt staging path or TOCTOU race.
- **`diagnosticManifestJson` is logging-only.** The host's parsed
  capability list is the only grant authority.
- **`sandboxToken` carried on every callback.** The host can route
  per-plugin without trusting the IBinder identity alone.

## Error semantics

- **`loadPlugin` throws `IllegalStateException`** if `manifestJson`
  parse fails sandbox-side or the bundle hash mismatches. Host
  surfaces `load_failed` to the UI.
- **AIDL `RemoteException` mid-call.** Sandbox died. Host fires
  `onPluginCrashed("remote_exception")` for every active token,
  drains the pending-callback map with `plugin_crashed` errors,
  and rebinds on the next `loadPlugin`.
- **`onWebViewError`.** Plugin's state transitions to `errored`;
  the host logs via `AuditLogger`; no further hooks dispatched.
- **Bridge call timeout.** Existing `CALL_TIMEOUT_MS` still applies
  on the host side. The sandbox does NOT enforce its own timeout.
- **Process kill while load in flight.** `loadPlugin` returns
  `RemoteException`; host treats it as a generic load failure.

## Non-goals for Phase 10

- **Per-plugin process isolation.** Phase 10 puts ALL plugins in
  the single `:plugin` process. Per-plugin process is a future
  follow-up.
- **`isolatedProcess` UID isolation.** Phase 10b uses the shared-
  UID `:plugin` form; the `ParcelFileDescriptor` bundle handoff
  needed for `isolatedProcess` is Phase 10c.
- **Native Kotlin AAR loading.** V1.5 #2 / Phase 11.

## Test plan

- **Parcelable round-trip**: `PluginLoadParcel.writeToParcel` →
  `createFromParcel` preserves every field, including the capped
  `diagnosticManifestJson`.
- **State machine**: every (state × method) cell in the table above
  has at least one assertion.
- **Serial queue**: 100 concurrent `dispatchHook` calls from
  different host threads → sandbox sees them in submission order
  per token.
- **Connection teardown**: load → unload → wait 31 s → assert
  unbindService fired; load again → assert rebind.
- **Crash recovery**: `Process.killProcess(:plugin)` mid-dispatch
  → `onPluginCrashed` fires once per token; pending callbacks
  complete with `plugin_crashed`; next `loadPlugin` rebinds.
- **Capability**: round-trip bridge call matches in-process
  behavior byte-for-byte against the existing `NetworkBridge` +
  `StorageBridge` test fixtures.
- **Payload cap**: a 1 MB `payloadJson` dispatch → host-side cap
  hit, `payload_too_large` returned to the calling capability.

## Rollout

V0.1 means no production users; the in-process WebView is removed
in step 6 of the migration order. No feature flag — full
replacement.
