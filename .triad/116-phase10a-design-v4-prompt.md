=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 116 — Phase 10a design v4 (post-115 fixups)

Triad 115: Codex **RED** with three blocking findings; Gemini
**GREEN** (and impersonated Codex in its reply, which I noticed
when the two verdicts disagreed). Codex's findings are the
authoritative read. All three folded into v4 at commit `e341c15`.

## v4 deltas vs v3

### 1. Token/path contradiction resolved (Codex 115 #1)

v3 said `sandboxToken` was "sandbox-generated and returned by
`loadPlugin`" AND that the host stages a `{pluginId}_{sandboxToken}`
path BEFORE calling `loadPlugin`. Impossible — the host can't
stage a token-keyed path before it knows the token.

v4: **host allocates the token**. `PluginRegistry` mints a fresh
monotonically-increasing `Int`, stages to the token-keyed path,
includes the token in `PluginLoadParcel.sandboxToken`, then calls
`loadPlugin(parcel, callback)`. Sandbox adopts verbatim as its
AIDL routing key. The AIDL signature keeps `int loadPlugin(...)`
returning the token for API ergonomics — the host verifies the
returned value matches what it sent and treats a mismatch as a
fatal sandbox bug.

### 2. Cleanup ACK added to AIDL (Codex 115 #2)

v3 prose referenced a "cleanup-complete callback" on the
`unloading → unloaded` transition, but `IPluginHostCallback` had
no such method. v4 adds:

```aidl
oneway void onPluginUnloaded(int sandboxToken);
```

Host treats it as authoritative for staged-bundle wipe + for
accepting a subsequent `loadPlugin(pluginId)` without the
`concurrent_unload_in_progress` rejection. Same-`pluginId` reload
sequence is now: `unloadPlugin(N)` → wait for `onPluginUnloaded(N)`
→ `loadPlugin(...)` with token N+1. `querySandboxState` polling is
kept for diagnostics only.

### 3. WebView global-wipe trigger corrected (Codex 115 #3)

v3 fired `WebStorage.deleteAllData()` +
`CookieManager.removeAllCookies()` on "last ready token for a
given `pluginId`". But those APIs are PROCESS-global; unloading
plugin A would nuke plugin B's cookies / IndexedDB if B were
still ready.

v4: wipe is ONLY fired when the sandbox has **zero ready or
loading tokens at all** — i.e. just before the connection's
idle-unbind timer is poised to fire. While other plugins remain
live in `:plugin`, individual `unloadPlugin` does NOT touch the
shared WebView state. Real per-plugin storage isolation deferred
to Phase 10c.

## Files

- `docs/plugin-host-multi-process.md` v4 at commit `e341c15`.
  70 insertions, 26 deletions vs v3.

## Output

Per reviewer, terse:

1. Verdict on v4: GREEN / YELLOW / RED + items.
2. Anything still missing.
3. Anything new.

If dual-GREEN, Phase 10b implementation begins. Asking each
reviewer to **double-check the cross-references** between
`PluginLoadParcel.sandboxToken`, the `int loadPlugin` return
value, the staged-path computation, and the `onPluginUnloaded`
ACK — those are the load-bearing pieces and v4 is a fresh
rewrite of all of them.

**Reply text only. Do NOT call any write/mutation tool. Do not
commit, edit, or stage anything.**
