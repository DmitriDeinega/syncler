=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 122 — Phase 10b step 6 fixups (post-121)

Triad 121: Gemini GREEN. Codex RED with blocker + secondary +
nit. All addressed at `4624533`.

## v2 deltas

1. **Generation fence on registry eviction** (Codex 121 RED blocker).
   On reload, the old instance's destroy() fires
   handle.unload() async; later onPluginUnloaded for the old
   token reached `RegistryUnloadListener` whose previous impl
   called `PluginRegistry.unload(pluginId)` — evicting the
   NEW instance. Fix:

   - `PluginRegistry.handleSandboxTerminated(pluginId, sandboxToken)`:
     guards removal on
     `instances[pluginId].sandboxHandle?.sandboxToken == sandboxToken`.
     Late callbacks for stale tokens skip.
   - `RegistryUnloadListener` takes sandboxToken in its ctor;
     `onPluginCrashed` + `onPluginUnloaded` both call
     `handleSandboxTerminated` keyed by that token.
   - `handleSandboxTerminated` nulls the matched instance's
     `sandboxHandle` so any subsequent `destroy()` (e.g.
     `PluginRegistry.clear()` at app shutdown) is a no-op on
     the AIDL leg — sandbox is already torn down. Closes
     Codex's secondary "re-acquire to issue redundant unload"
     concern.

2. **Thread real manifest JSON** (Codex 121 nit).
   `PluginInstanceFactory.create(…)` gains a `manifestJson:
   String` parameter. `SandboxedPluginInstanceFactory` passes
   it through to `PluginLoadParcel.diagnosticManifestJson`
   (truncated to `DIAGNOSTIC_MANIFEST_BYTES_CAP = 64 KB`).
   The wire layer also truncates — belt-and-suspenders so a
   buggy upstream can't blow the Binder transaction.
   `PluginLoaderTest` updated to match the new signature.

## Deferred (non-blocking — acknowledged in Codex 121)

- `PluginLoader.android()` minting a fresh
  connection/router/dispatcher per call. No production callers
  yet — this factory is wiring scaffolding. Hilt module will
  enforce singleton when that lands.
- `auditLogger.denied(pluginId, "webview_error_$code", message)`
  shape — fine for the current `AuditLogger` API (reason +
  detail text only). A structured field would be cleaner; not
  blocking.

## Files

- `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/PluginRegistry.kt`
- `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/PluginLoader.kt`
- `android/feature/plugin-host/src/test/kotlin/app/syncler/android/pluginhost/PluginLoaderTest.kt`

## Test status

- `:feature:plugin-host:testDebugUnitTest` passes (`PluginLoaderTest`
  updated for the new signature).
- `:feature:plugin-sandbox:testDebugUnitTest` passes.
- `:app:assembleDebug` succeeds.

## Output

Per reviewer, terse:

1. Verdict on the fixups: GREEN / YELLOW / RED + items.
2. Anything still missing before Phase 10b closes.
3. Anything new.

If dual-GREEN, Phase 10 (multi-process plugin host) is done.
Next track is Phase 11 (native Kotlin runtime) or Phase 9
(per-device envelope encryption).

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
