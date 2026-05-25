=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 114 — Phase 10a design v2 (post-113 rewrite)

Triad 113 verdicts: both YELLOW. Substantial overlap between
Codex's 7 numbered items + Codex's 4 missing-list items and
Gemini's 5 items. All findings consolidated into v2 at commit
`08f0826`.

## v2 deltas vs v1

### Security framing (both reviewers, critical)

v1 claimed the shared-UID `:plugin` "blocks memory-corruption /
type-confusion exploits" and that UID-jail "wouldn't help". Both
false. v2:

- Added a Threat Model table that lists which threats Phase 10
  DOES address (plugin spin / OOM / native crash isolation /
  cross-plugin pref reads via capability gate) and which it does
  NOT (native exploit escaping the renderer sandbox into host
  data).
- Explicitly accepts the shared-UID trade-off.
- Notes `isolatedProcess` + `ParcelFileDescriptor` as a future
  Phase 10c option (Codex 113 #1 confirmed this is viable).

### Manifest grant source (both reviewers)

v1 shipped raw `manifestJson` across IPC and parsed it both sides.
v2 ships `PluginLoadParcel` with pre-parsed fields:

  - `pluginId`, `pluginIdentifier`, `version`, `renderer`
  - `bundleFilePath`, `bundleHashHex`
  - `declaredCapabilities: String[]`
  - `declaredEndpoints: String[]`
  - `dismissBehavior`, `timeoutMillis`
  - `diagnosticManifestJson` — retained for telemetry only, NOT
    a grant source

Eliminates the parser-divergence capability-confusion risk
Codex 113 #4 flagged.

### Bundle storage (Codex 113 #5)

`noBackupFilesDir` to match `AndroidEncryptedBundleStore`; v1's
`getCacheDir()` had a real cache-eviction TOCTOU race.
ParcelFileDescriptor handoff explicitly listed as a Phase 10c
follow-up.

### AIDL contract tightening

- **Every `IPluginHostCallback` method carries `sandboxToken`**
  (Codex 113 #2). Host routes per-plugin without trusting bare
  IBinder identity.
- **`oneway` marked explicitly** in the AIDL snippet (Codex
  113 #3). `loadPlugin` is the one synchronous call, returns
  token; the callback is now registered as a `loadPlugin`
  argument so there's no race between token-return and first
  lifecycle event (Codex 113 #6).
- **Per-token serial queue** in the sandbox so concurrent
  `oneway` calls from different host coroutines stay ordered
  (both reviewers).
- **IPC payload caps** (Codex 113 missing #2): 64 KB for
  `diagnosticManifestJson`, 256 KB for hook/bridge args/results.
  Caps enforced on sender; receiver double-checks. Large
  payloads use `ParcelFileDescriptor` side-channel.

### Lifecycle

- **State machine** (Codex 113 missing #1): `loading` / `ready`
  / `errored` / `unloaded` / `process_dead` with a per-AIDL-
  method behavior table.
- **Pending callback cleanup** (Codex 113 missing #3): host
  completes outstanding `bridgeCall` callbacks with typed
  `plugin_unloaded` / `plugin_crashed` errors on
  `unloadPlugin` or `onPluginCrashed` so calls don't hang.
- **Connection teardown** (Gemini 113 + Codex 113 missing #4):
  `PluginSandboxConnection` ref-counts loaded plugins; schedules
  `unbindService` after a 30-second idle window so `:plugin`
  can be reaped when no plugins are loaded.

### Migration

- **Step 5 (e2e test) now precedes step 6 (strip in-process
  WebView)** so the in-process path is the rollback target
  until the multi-process path is proven (both reviewers).

## Files

- `docs/plugin-host-multi-process.md` — full rewrite (commit
  `08f0826`). 286 insertions, 148 deletions vs v1.

## Risks I want eyes on (now in v2)

1. **The shared-UID trade-off acceptance.** v2 is honest about
   what it does NOT defend against. Is the explicit acceptance
   acceptable for V1.5, given V0.1 has no production users?
   Or should Phase 10b drop directly into `isolatedProcess` +
   ParcelFileDescriptor?

2. **`diagnosticManifestJson` 64 KB cap.** Plugins SHOULD be
   well under this in practice. A hostile manifest could push
   it; we truncate + log. Worth flagging if the cap should be
   smaller.

3. **`unloadPlugin` is `oneway`.** The host doesn't block on
   sandbox-side cleanup. A subsequent `loadPlugin` for the same
   `pluginId` while cleanup is in flight is the open race. The
   sandbox needs an explicit "in-cleanup" state that rejects
   `loadPlugin` until cleanup completes. v2 covers state via
   the table but doesn't call out the unload→load race
   explicitly — should we add it?

4. **State machine completeness.** The table covers
   `dispatchHook`, `deliverBridgeResult`, `unloadPlugin`. What
   about `loadPlugin` arriving when the token is already in
   `ready`? (Probably: returns a fresh token + old state
   marked `unloaded`.)

5. **Per-token serial queue in the sandbox.** A coroutine + a
   `Channel` per token. Memory profile: one heap object per
   active plugin. Sane for the 10s-of-plugins range. Worth
   flagging if you'd expect different.

## Output

Per reviewer, terse:

1. Verdict on v2: GREEN / YELLOW / RED + items.
2. Anything missing.
3. Anything new.

If dual-GREEN, I move to Phase 10b implementation.

**Reply text only. Do NOT call any write/mutation tool. Do not
commit, edit, or stage anything.**
