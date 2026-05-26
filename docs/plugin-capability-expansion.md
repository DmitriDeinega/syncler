# Plugin Capability Expansion (V2 #10, Phase 12)

V1's plugin bridge already supports `network` and `notification`.
Phase 9b shipped `storage` (SQLCipher-backed K/V per plugin).
V2 #10 adds the four remaining capabilities surfaced in the SDK
runtime contract for native plugins (`docs/plugin-host-native-kotlin.md`)
and exposed via the JS `platform.*` bridge:

- `camera` — capture a photo via `MediaStore.ACTION_IMAGE_CAPTURE`.
- `gallery` — pick existing media via the Photo Picker (API 33+) /
  `ACTION_OPEN_DOCUMENT` fallback.
- `file` — pick a non-media document via `ACTION_OPEN_DOCUMENT`.
- `location` — single-fix coarse or fine via Fused Location Provider.

Each capability lands with:
1. **Per-grant user prompt** — the first time a plugin invokes the
   capability, the host shows a dialog explaining what's about to
   happen and which plugin is asking. Grant is per-(device, plugin
   row, capability); revocable from the settings sheet.
2. **OS permission acquisition** — for capabilities the manifest's
   capability declaration alone doesn't unlock (camera, location),
   the host launches the runtime permission request synchronously
   with the grant prompt.
3. **Audit log entry** — every invocation logs `(plugin_row_id,
   capability, sandbox_token, outcome)` via the existing
   `AuditLogger`. Outcome = `granted` / `denied` / `os_denied` /
   `success` / `failure`.
4. **Bridge return shape** — success returns the capability's
   typed payload (bytes, mime, gps, etc.); denial returns
   `{"error":"capability_denied"}` and the plugin sees
   `Result.failure(CapabilityDeniedException)` in Kotlin or a
   rejected promise in JS.

## Capability matrix

| Cap        | Manifest declares | OS perm needed       | Surface | Returns                              |
|------------|-------------------|----------------------|---------|--------------------------------------|
| `camera`   | yes               | CAMERA               | Activity-result | `{bytes, mime}`               |
| `gallery`  | yes               | none (Photo Picker)  | Activity-result | `{items: [{bytes, mime}]}`     |
| `file`     | yes               | none (SAF)           | Activity-result | `{bytes, name, mime}`          |
| `location` | yes               | ACCESS_COARSE/FINE   | Service call    | `{latitude, longitude, accuracyMeters}` |

## Per-grant prompt model

Capability grants live in a new Room table `plugin_capability_grants`:

```sql
CREATE TABLE plugin_capability_grants (
  plugin_row_id  TEXT NOT NULL,
  capability     TEXT NOT NULL,
  granted_at_ms  INTEGER NOT NULL,
  PRIMARY KEY (plugin_row_id, capability)
);
```

The bridge's grant check becomes:
1. Manifest declares the capability? (existing check)
2. Per-plugin grant row exists?
3. If not: show the per-grant prompt; insert row on accept.
4. OS permission state allows? (camera/location only)
5. If not: launch the runtime permission request.

Revoking a grant in the settings sheet deletes the row; next
invocation re-prompts.

## Activity-result plumbing

Camera/gallery/file all need an `Activity` to launch an Intent
and await its result. The plugin sandbox runs in `:plugin` (JS)
or `:nativePlugin` (DEX) — neither has an Activity. The host
process owns the foreground Activity, so the bridge call routes
to host code which:

1. Records a `pending_capability_call(call_id → continuation)`.
2. Calls into a singleton `CapabilityActivityCoordinator` in the
   host process that holds a weak reference to the currently
   foregrounded Activity.
3. If no Activity is foregrounded, returns
   `{"error":"no_foreground_activity"}` immediately. Plugin can
   retry when the user returns to the app.
4. Activity launches the intent via the ActivityResultLauncher
   API; the registered callback resolves the continuation.

The coordinator handles process death across the await: if the
host process dies mid-await, the sandbox sees `onPluginCrashed`
and the plugin coroutine is cancelled; no orphan continuations.

## JS bridge shape

Existing `platform.cameraCapture(opts)` / `platform.galleryPick(opts)`
/ `platform.filePick(opts)` / `platform.locationCurrent(opts)`
already exist as bridge entries (stubbed today). Phase 12
implements them; the wire format is unchanged.

## Native bridge shape

`BridgePluginContext.cameraCapture(...)` etc. already marshal
via `bridgeCall("platform.cameraCapture", argsJson, callbackId)`.
Same single dispatcher serves both renderers; the implementation
work is purely host-side.

## Implementation order (Phase 12)

1. Spec digest (this file).
2. Room migration: `plugin_capability_grants` table.
3. `CapabilityGrantStore` (DAO + in-memory cache, settings-sheet
   wiring for revoke).
4. `CapabilityActivityCoordinator` (weak-ref to Activity,
   pending-call map, ActivityResultLauncher plumbing).
5. Host UI: `CapabilityGrantDialog` (Compose Material 3,
   explainer + Allow/Deny buttons).
6. `LocationBridge` — fused location provider (no Activity
   needed once permission is granted; foreground Service may be
   needed if we want background fixes, but single-fix is fine
   for V2 #10).
7. `FileBridge` — `ACTION_OPEN_DOCUMENT` round-trip.
8. `GalleryBridge` — Photo Picker on API 33+, `ACTION_OPEN_DOCUMENT`
   with `image/*` filter on 26-32.
9. `CameraBridge` — `ACTION_IMAGE_CAPTURE` writing to a temp
   FileProvider URI, then read bytes + delete.
10. Audit-log integration for each (5 entry points).
11. Settings sheet: per-plugin grant list with revoke.
12. Tests: dialog dismissal denies, grant-stored-but-OS-denied
    surfaces `os_denied`, idempotent grant re-prompt only on
    capability change, etc.
13. Mid-track triad consult.

## Non-goals for Phase 12

- **Background location.** Single foreground fix only. Background
  location grants need a separate UX track (and a foreground
  service).
- **Multi-item camera.** Single capture per call; gallery picker
  handles multi-select.
- **File save** (`platform.fileSave`). Picker is read-only this
  phase; write-out is V3+.
- **PIP / mediaprojection / screen capture.** Out of scope.
- **Audio recording.** Out of scope; needs a separate dialog
  track and continuous-recording protocol.

## Threat model deltas

- A malicious plugin can request a capability on every
  invocation (DoS via dialog spam). Mitigation: rate-limit the
  prompt per (plugin, capability) — at most one prompt every
  60 seconds for the same plugin+capability combo. Subsequent
  prompts within the window return `{"error":"capability_denied"}`
  immediately without flooding the user.
- Camera/gallery results contain arbitrary user-chosen bytes. The
  bridge passes them verbatim to the plugin; the plugin must
  treat them as untrusted input. Documented in the SDK contract.
- Location grants do NOT log the actual coordinates to the audit
  log (privacy). Only the invocation + outcome.
