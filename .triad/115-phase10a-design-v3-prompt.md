=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 115 — Phase 10a design v3 (post-114 rewrite)

Triad 114 verdicts: both YELLOW. Findings folded into v3 at
commit `66f349d`. Counts: Codex 3 numbered items + 3 missing-list
items + 3 risk responses; Gemini 1 critical (fs TOCTOU) + 2
missing items + 4 risk responses.

## v3 deltas vs v2

### 1. `unloading` state added to the lifecycle FSM (Codex 114 #1)

Because `unloadPlugin` is `oneway`, host-visible "unloaded" and
sandbox-side cleanup (WebView destroy, JS heap teardown, staged-
file wipe ACK) can skew. v3 splits them: sandbox owns
`unloading → unloaded`; host treats `unloading` as in-progress.

### 2. State machine table now covers `loadPlugin` + `querySandboxState` (Codex 114 #2 + missing #3)

Same-`pluginId` `loadPlugin` during `loading` / `ready` /
`unloading` returns structured rejection codes
(`concurrent_load_in_progress`,
`concurrent_unload_in_progress`). The documented host sequence
for reload is: `unloadPlugin(N)` → wait for
`querySandboxState(N) == "unloaded"` or for `onPluginCrashed(N)`
→ `loadPlugin(...)` (which allocates token N+1).

### 3. `sandboxToken` defined as session ID, not derived from `pluginId` (Gemini 114 #2 + Codex 114 missing #1 — generation fencing)

Successive loads of the same `pluginId` get DIFFERENT tokens. The
host's `IPluginHostCallback` routes by token; a stale `bridgeCall`
from token N after the host has moved on to N+1 lands in a
drained slot and is silently dropped.

### 4. Session-keyed bundle staging path (Gemini 114 critical + Codex 114 missing #2)

Path is now:

```
{noBackupFilesDir}/plugin-sandbox/{pluginId}_{sandboxToken}/bundle.js
```

`pluginId`-only paths raced the async unload wipe against a
freshly-staged new bundle. Per-token paths make the two
operations operate on disjoint directories.

### 5. WebView per-process state isolation between same-`pluginId` reloads (Gemini 114 missing #1)

Sandbox calls `WebStorage.getInstance().deleteAllData()` +
`CookieManager.getInstance().removeAllCookies()` when the last
`ready` token for a given `pluginId` enters `unloaded`. Real
per-plugin storage scoping deferred to Phase 10c.

### 6. `loadPlugin` error semantics structured into codes (Codex 114 #3)

v2 had a contradiction: doc said "manifest NOT parsed sandbox-
side" but later said "loadPlugin throws if manifestJson parse
fails sandbox-side". v3 enumerates the real failure codes:
`parcel_malformed`, `bundle_hash_mismatch`,
`unsupported_renderer`, `diagnostic_field_oversize`.
`diagnosticManifestJson` is **never** deserialized for behavior;
a malformed diagnostic field is dropped + warn-logged, NOT a
load failure.

### Accepted as-is

- Shared-UID trade-off (both reviewers GREEN on the framing).
- 64 KB diagnostic cap (both fine).
- Per-token coroutine + channel queue (Gemini "idiomatic and
  extremely lightweight"; Codex "sane for tens of plugins").

## Files

- `docs/plugin-host-multi-process.md` — v3 rewrite at commit
  `66f349d` (102 ins, 23 del vs v2).

## Output

Per reviewer, terse:

1. Verdict on v3: GREEN / YELLOW / RED + items.
2. Anything still missing.
3. Anything new.

If dual-GREEN, Phase 10b implementation begins.

**Reply text only. Do NOT call any write/mutation tool. Do not
commit, edit, or stage anything.**
