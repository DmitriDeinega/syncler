# M8 — Plugin update UX (prompt-to-update, defer, block-on-min-version)

You completed M1–M7. M8 implements the plugin update flow per Q23 (prompt-to-update for V1; auto-update with marketplace gate is V1.5).

Workspace-write granted. Touch `server/`, `android/`, and `sdk-plugin/`.

## Server-side (M8.1)

### Plugin version index
- `GET /v1/plugins/{plugin_id}/latest` — returns the latest published version's manifest (no auth — public for any installed plugin). For V1, "latest" = highest semver from non-revoked rows.
- `POST /v1/plugins/publish` — sender-authenticated (signed by sender's private key). Uploads a new version's manifest + bundle URL. Validates:
  - Manifest matches the signature
  - Version is > existing versions for this `(sender_id)`
  - bundle URL is reachable (HEAD request; not full download)
- New entries go into `plugins` table

### Files
- `server/app/routers/plugins.py` — UPDATE: add `latest`, `publish`, `revoke`
- `server/app/services/plugins.py` — NEW
- `server/tests/test_plugin_publish.py` — NEW

## Android-side (M8.2)

### Update check service
- `android/core/network/.../PluginUpdateChecker.kt`:
  - Runs on app foreground + once daily via WorkManager
  - For each installed plugin: `GET /v1/plugins/{plugin_id}/latest`
  - If newer version available: stores `PluginUpdateAvailable(plugin_id, current, latest, manifest_hash, signed_bundle_url)` in local pending updates store
  - Triggers a notification: *"3 plugin updates available"* tappable to PluginUpdatesScreen

### Update UI
- `android/feature/settings/.../PluginUpdatesScreen.kt`:
  - List of pending updates with current → latest version + release notes (if manifest has them)
  - Per-row "Update" button: kicks off download, signature verify (M4b), capability re-grant if new caps requested, install
  - "Update All" button
- Capability changes: if new version declares additional capabilities, the install flow re-prompts user (Q16). User can decline → update is rejected and stays as "available."

### Block-on-min-version (Gap 3 close-out)
- When a message's `min_plugin_version > installed`: render the placeholder card with a prominent "Update LotteryPlugin to view" button that opens directly to PluginUpdatesScreen filtered to that plugin
- After successful update + permissions accepted, the placeholder messages are re-dispatched through the plugin (the encrypted body is still on the server until expiry)

### Defer / postpone
- "Remind me later" button on the update prompt — pushes the reminder 24h forward
- After 7 days deferred + a pending blocked message exists: prompt escalates (modal not snackbar)

### Files
- `android/feature/settings/.../PluginUpdatesScreen.kt` — NEW
- `android/feature/settings/.../PluginUpdatesViewModel.kt` — NEW
- `android/core/network/.../PluginUpdateChecker.kt` — NEW
- `android/core/storage/.../PendingUpdatesStore.kt` — NEW
- `android/app/.../UpdateNotificationFactory.kt` — NEW

## Plugin SDK changes (M8.3)

- `sdk-plugin/src/manifest.ts` — add optional `releaseNotes: string` field
- `tools/sign-bundle.ts` — accepts a `--release-notes` flag

## Tests

- Server publish endpoint accepts higher version, rejects same-or-lower
- Android update checker correctly identifies updates available; min_plugin_version placeholder shows correct UI
- Defer escalation logic (mock clock)
- Capability change → re-grant prompt; declined → update not applied

## Constraints
- Updates per Q23 = prompt only in V1 — no auto-install
- Capability re-grant is mandatory on capability change
- Bundle verification uses M4b's `PluginSignatureVerifier`, no shortcuts
- Defer state is per-device (not synced — local preference)

## Print summary
- Files created
- The "remind me later" escalation timer (hours)
- Confirm capability-change re-grant flow is mandatory
