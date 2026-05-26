=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 137 — Phase 12 design (V2 #10, capability expansion)

Phase 11 closed at 02efe9d. Next on the roadmap: V2 #10 plugin
capability expansion — camera / gallery / file / location. Same
v0.1 dev posture as 132-136.

## Spec under review

`docs/plugin-capability-expansion.md` (commit 30602b7).
Capability matrix:

| Cap        | Manifest declares | OS perm needed       | Surface             | Returns                                   |
|------------|-------------------|----------------------|---------------------|-------------------------------------------|
| `camera`   | yes               | CAMERA               | Activity-result     | `{bytes, mime}`                           |
| `gallery`  | yes               | none (Photo Picker)  | Activity-result     | `{items: [{bytes, mime}]}`                |
| `file`     | yes               | none (SAF)           | Activity-result     | `{bytes, name, mime}`                     |
| `location` | yes               | ACCESS_COARSE/FINE   | Service call        | `{latitude, longitude, accuracyMeters}`    |

Per-grant prompt model stored in new Room table
`plugin_capability_grants(plugin_row_id, capability, granted_at_ms)`.

Activity-result work routed via a singleton
`CapabilityActivityCoordinator` in the host process that holds
a WeakReference to the currently-foregrounded Activity. Sandbox
processes (`:plugin` JS, per-token `:nativePlugin` isolated)
have no Activity, so the bridge call routes to host code which
records a `pending_capability_call(call_id → continuation)` and
hands off to the Activity coordinator. No-foreground-Activity
returns `no_foreground_activity` immediately.

Implementation order (13 steps): grant store → activity
coordinator → grant dialog → 4 bridges → audit + settings +
tests → mid-track triad.

## Context

Existing infrastructure already in place:
- `AuditLogger` for per-invocation audit rows.
- `PluginPermissionStore::grantedCapabilities` for the manifest-
  declared capability set.
- `StorageBridge` (V1.5, shipped) — fully implemented K/V over
  SQLCipher; serves as the template for how a real bridge fits
  into the dispatcher.
- `CameraBridge` / `FileBridge` / `GalleryBridge` / `LocationBridge`
  exist as 11-line stubs returning `not_implemented`.
- Phase 11 `BridgePluginContext` in native sandbox marshals
  these calls into AIDL `bridgeCall(...)`; the host's
  `PluginBridge` is the single dispatcher for JS + native.

## Concerns I want a second opinion on

1. **WeakReference-to-Activity coordinator design.** Standard
   Android pattern but it has known gotchas around config
   change (Activity recreated → weak ref nulled mid-await),
   process death (host process dies → entire continuation map
   nuked), and re-entrancy (Activity launches a capability,
   gets paused, plugin sees no_foreground_activity). What's the
   right answer? Alternatives I considered:
   - (a) Use a foregroundService to mediate. Heavyweight, but
     survives Activity recreation.
   - (b) ActivityResultRegistry at the Application level (API
     30+). Lighter but still bound to whichever Activity
     last registered.
   - (c) Refuse capability calls when no Activity is
     foregrounded; let the plugin retry. Simplest; UX is
     "open the Syncler app, your plugin's request will run."

2. **Grant prompt rate-limit.** Spec says "at most one prompt
   every 60 seconds for the same (plugin, capability) combo."
   The window is per (device, plugin_row, capability); a denied
   prompt sets a cooldown. Is 60s right? Should the cooldown be
   per-Activity-foreground-session (i.e. zero-out when the user
   re-opens the app)? What about clear-once-on-foreground vs.
   leaky-bucket?

3. **OS permission + grant prompt sequencing.** Camera/location
   need BOTH a per-grant in-app prompt AND a runtime OS
   permission. Two prompts feels redundant — the in-app one
   could pre-explain why the OS prompt is about to fire. But:
   - Plugin already declared `camera` capability in manifest →
     user agreed at install/pair time.
   - Per-invocation in-app prompt explains "Plugin X wants to
     take a photo right now."
   - OS prompt is system-level "Allow Syncler to access camera?"
   The user might say yes to the in-app prompt and no to the
   OS prompt; we surface `os_denied`. Is the two-prompt sequence
   correct? Or should declared-capability + OS-grant be enough,
   skipping the per-invocation in-app prompt for capabilities
   the OS already gates?

4. **Photo Picker vs. SAF for gallery.** Photo Picker is API 33+
   but Google ships a backport in androidx for 26+ via
   `PickVisualMedia` ActivityResultContract. Should we use the
   backport unconditionally, or branch on SDK_INT? The backport
   is well-tested and means one code path.

5. **Result byte transfer size.** Capabilities return bytes
   directly via `bridgeCall` result JSON (base64-encoded). A
   2MB image base64-encodes to ~2.7MB. Binder transactions
   over 1MB are unreliable. Should we instead:
   - (a) Return a content:// URI the sandbox can `ContentResolver`-
     open. But the isolated UID can't read content URIs without
     explicit grant flags per Intent.
   - (b) Stage to a host-owned file, return its path + a
     short-lived host-mediated FD. Sandbox reads via the host's
     PluginContext.fileBytes(handle).
   - (c) Hard size cap (1 MB) on results; plugins handle
     compression themselves.
   What's the right shape?

6. **CameraBridge tempfile lifecycle.** ACTION_IMAGE_CAPTURE
   writes to a FileProvider URI we give it. We then read bytes
   and delete. If the host process is killed between capture
   and read, the tempfile leaks. Periodic cleanup of stale
   `plugin-camera-*` files in `cacheDir` is the obvious answer.
   Is there a more correct approach?

7. **Location capability granularity.** Spec lumps
   `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` under
   one `location` capability. Should the manifest declare
   them separately (`location.fine` vs `location.coarse`)?
   Plugins that only need city-level shouldn't be triggering
   the fine-grained OS prompt.

8. **Audit log entry per invocation vs. per grant.** Spec says
   "every invocation logs (plugin_row_id, capability,
   sandbox_token, outcome)." That's potentially high-volume
   for a plugin that polls location every 30s. Should the
   audit be sample-based (1 in N) or aggregated (one row per
   plugin per hour)? Or is per-invocation correct and we
   manage volume via retention policy?

9. **Settings-sheet revoke UX.** Phase 12 step 11. Should the
   list be per-plugin (settings → plugin X → capabilities)
   or per-capability (settings → camera → which plugins)?
   Per-plugin is more discoverable for "what does this plugin
   have access to?"; per-capability is better for "who's using
   my location?"

10. **Manifest capability validation gap.** Currently the
    server's `PluginPublishRequest.capabilities` validates
    against a Literal["camera", "gallery", ...]. Phase 12 adds
    `location.fine` / `location.coarse` if we go with #7's
    split. Do we bump a manifest version? Add a new
    capability `location` that maps to fine-only for backwards
    compat? Reject mixed manifests?

## What I'm asking for

For each numbered concern, a verdict + recommendation:
- **OK** — spec's choice is defensible.
- **NIT** — minor improvement.
- **FIX** — change before implementation starts.
- **DESIGN** — disagree with the design; expand.

Plus anything missing from the spec that you'd want to see
before greenlighting implementation. Focus on:
- Sandbox/host boundary correctness (continuation lifetime,
  process death races).
- Permission UX clarity (when does the user see what, and why).
- Binder transaction size + content URI grants.

Skip cosmetics; flag substance.
