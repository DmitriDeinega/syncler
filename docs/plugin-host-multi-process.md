# Multi-Process Plugin Host (V1.5 runtime #1, Phase 10)

Status: **design v3** — Phase 10a (this document) locks the AIDL
contract, process lifecycle, and migration plan. Phase 10b implements.
v3 revisions land the triad 114 findings (unload→reload race,
state-machine completeness, generation fencing, staged-path
collision, manifest-error wording, WebView-data isolation).
v2 revisions landed the triad 113 findings (security framing,
callback identity, oneway ordering, payload limits, lifecycle race,
bundle storage).

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

`sandboxToken` is an opaque session ID — **host-allocated** by the
`PluginRegistry` before staging, included as a field in
`PluginLoadParcel`, and adopted verbatim by the sandbox as its
AIDL routing key. v4 (triad 115 Codex #1) corrects v3's
contradiction: v3 said both "sandbox-generated and returned by
`loadPlugin`" AND "host stages a `{pluginId}_{sandboxToken}` path
before calling `loadPlugin`", which is impossible. Host
allocation is the single source of truth.

Concretely:

  1. Host's `PluginRegistry.load(pluginId, ...)` allocates a fresh
     monotonically-increasing `Int` (process-local; not persisted
     across host restarts).
  2. Host stages the decrypted bundle to the session-keyed path
     (see Bundle Storage below) under that token.
  3. Host calls `loadPlugin(parcel, callback)` with the token in
     `parcel.sandboxToken`.
  4. Sandbox uses the token as its per-plugin routing key for the
     lifecycle of the load.
  5. `loadPlugin` returns the SAME token on success (a small
     redundancy — the AIDL signature predates v4 and we keep the
     return for API ergonomics, but the host MUST verify it
     matches the value it sent and treat a mismatch as a fatal
     sandbox bug).

It is NOT derived from `pluginId`. Successive loads of the same
`pluginId` get DIFFERENT tokens — Phase 10's "generation fence".
Every callback the host receives is keyed by token, so a late
`bridgeCall` from a stale token (e.g. the previous load that we're
still tearing down) gets routed to a slot the host has already
drained and is silently dropped. Triad 114 finding: bare
`pluginId` keying would let stale callbacks confuse a
freshly-loaded plugin.

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

    // v4 (triad 115 Codex #2): explicit ACK that the sandbox has
    // finished cleanup for an unload. Fired once when the sandbox
    // transitions `unloading -> unloaded`. The host treats receipt
    // as authoritative for wiping the staged-bundle directory and
    // for accepting a subsequent loadPlugin(pluginId) without the
    // `concurrent_unload_in_progress` rejection.
    oneway void onPluginUnloaded(int sandboxToken);
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
    int sandboxToken;                // v4: host-allocated session ID,
                                     // used by both sides as the
                                     // routing key. Sandbox MUST adopt
                                     // verbatim.
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

The host writes the decrypted JS bundle to a **session-keyed**
path:

```
getNoBackupFilesDir()/plugin-sandbox/{pluginId}_{sandboxToken}/bundle.js
```

The directory name carries `sandboxToken` (not just `pluginId`) so
a rapid unload→reload of the same `pluginId` produces a different
staged path. Triad 114 finding (Gemini critical): because
`unloadPlugin` is `oneway` and triggers async wipe, a `pluginId`-
only path would race — the new load's freshly-staged bundle could
be overwritten by the old unload's wipe. Per-token paths make the
two operations operate on disjoint directories.

The host wipes the staged directory for token N immediately after
the sandbox fires `onPluginUnloaded(N)` (the cleanup-complete
callback added to `IPluginHostCallback`). If the sandbox dies
before ACKing, the host wipes every staged directory on the
rebind in `PluginSandboxConnection.onServiceDisconnected`.
Process-exit hooks (`Runtime.addShutdownHook`) on the host catch
the residual case where the host itself is killed.

The sandbox does NOT auto-clear WebView state between same-
`pluginId` reloads (triad 114 Gemini missing #1): Android's
WebView shares a per-process cookie / cache / IndexedDB directory
across all WebView instances in `:plugin`. v4 (triad 115 Codex
#3): the wipe is process-global, so triggering it on "last
`ready` token for a given `pluginId`" would also nuke OTHER
plugins' state if they're still running. Phase 10b's
`PluginSandboxService` calls `WebStorage.getInstance().deleteAllData()`
+ `CookieManager.getInstance().removeAllCookies()` ONLY when the
sandbox has **no `ready` or `loading` tokens at all** — i.e. just
before the sandbox process is about to be idle and the
connection's idle-unbind timer is poised to fire. While other
plugins are live in `:plugin`, an individual `unloadPlugin` does
NOT touch the shared WebView state. We accept this as a V1.5
trade-off; real per-plugin storage isolation is Phase 10c.

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
       │        ┌───────────┐                    └────┬─────┘
       │        │ unloading │                         │ unloadPlugin
       │        └────┬──────┘                         ▼
       │             │ cleanup completes      ┌───────────┐
       │             ▼                        │ unloading │
       │        ┌──────────┐                  └────┬──────┘
       │        │ unloaded │◀─── cleanup ─────────┘
       │        └──────────┘
       │
       └─── process dies (RemoteException) ───▶┌──────────┐
                                               │ process_ │
                                               │  dead    │
                                               └──────────┘
```

`unloading` was added in v3 (triad 114 Codex #1): because
`unloadPlugin` is `oneway`, the host-visible "unloaded" view and
the sandbox's actual cleanup (WebView destroy, JS heap teardown,
staged-file wipe ACK) can be skew. The sandbox owns the
`unloading → unloaded` transition; the host treats `unloading`
as "in-progress" and `unloaded` as "done".

AIDL behavior per state:

| State | `loadPlugin` (same `pluginId`) | `dispatchHook` | `deliverBridgeResult` | `unloadPlugin` | `querySandboxState` |
|---|---|---|---|---|---|
| `loading` | rejected, `concurrent_load_in_progress` | buffered (sandbox replays after `onPluginReady`) | buffered | OK; cancels load → `unloading` | returns `"loading"` |
| `ready` | rejected — caller MUST `unloadPlugin` first | delivered | delivered | OK → `unloading` | returns `"ready"` |
| `errored` | OK; allocates new token, old slot stays in `errored` | dropped + warn-log | dropped | OK → `unloading` | returns `"errored"` |
| `unloading` | rejected, `concurrent_unload_in_progress` (host SHOULD await `unloaded` then retry) | dropped + warn-log | dropped + warn-log | no-op (re-entrant) | returns `"unloading"` |
| `unloaded` | OK; allocates new token | dropped + warn-log (caller bug) | dropped + warn-log | no-op | returns `"unloaded"` |
| `process_dead` | OK after rebind; allocates new token | dropped + `onPluginCrashed` fires once | dropped | no-op | returns `"process_dead"` |

The "rejected" rows return `IllegalStateException` over Binder with
a structured error code. The host's `PluginRegistry` is the one
caller of `loadPlugin`; it serializes loads per `pluginId` so the
rejection rows are reachable mostly via bugs / tests, but the
sandbox MUST enforce them defensively because a buggy or
adversarial host could hammer.

**Same-`pluginId` reload race**: the documented sequence is
`unloadPlugin(N)` → wait for `onPluginUnloaded(N)` (or for
`onPluginCrashed(N)`) → then `loadPlugin(...)` with a freshly-
allocated token N+1 in `PluginLoadParcel.sandboxToken`, against a
freshly-staged `{pluginId}_{N+1}` directory. `onPluginUnloaded` is
the authoritative ACK; the slower `querySandboxState` polling path
remains for diagnostics. The host's `PluginRegistry` enforces this
sequence; the sandbox's `unloading` row above is the backstop.

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

- **`loadPlugin` `IllegalStateException` with structured code.**
  v3 (triad 114 Codex #3): the failure modes are
  `parcel_malformed` (Parcelable fields missing / wrong type),
  `bundle_hash_mismatch` (sandbox-side hash verify against
  `bundleHashHex` failed), `unsupported_renderer` (e.g. sandbox
  build doesn't ship the requested renderer), and
  `diagnostic_field_oversize` (capped JSON field exceeded the
  64 KB cap mid-stream). **Manifest is NOT parsed sandbox-side**
  — Phase 10a v2 moved grant authority to the parsed
  `PluginLoadParcel` fields. The `diagnosticManifestJson` field
  is logged but never deserialized for behavior; a malformed
  diagnostic field is dropped + warn-logged, NOT a load failure.
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
