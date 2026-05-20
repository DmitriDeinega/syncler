# M7 — Multi-device sync (encrypted state, merge strategy, dismiss/version sync)

You completed M1–M6. M7 wires the multi-device experience: encrypted user state synced across devices, dismiss-event fan-out (Gap 1), plugin install state synced, plugin-version-mismatch placeholder (Gap 3).

Workspace-write granted. Touch `server/`, `android/`, and the plugin SDK (`sdk-plugin/`).

## Server-side (M7.1)

### Encrypted user state
The `encrypted_user_state` table from M1.3 stores a single blob per user containing:
- Installed plugins list (per-device install state — which plugins exist on which device)
- Sender pairings index (mirroring the `pairings` table for sender-known plugin pairings)
- Per-plugin settings (granted permissions, dismissBehavior overrides)
- Notification dismiss state (`{ message_id, dismissed_at }` recent entries)
- Per-key plugin storage with `scope: user` (I1)

The server treats the blob as opaque. Clients decrypt locally with the master key.

### Sync endpoints

`GET /v1/state` — returns `{ state_version: int, encrypted_blob: base64, updated_at }`. Cheap; can be polled.

`PUT /v1/state` — atomic compare-and-swap:
```json
{
  "expected_state_version": 5,
  "new_encrypted_blob": "<base64>"
}
```
Returns:
- 200 on success with `{ new_state_version: 6 }`
- 409 with `{ current_state_version: 7, current_encrypted_blob: "<base64>" }` on version mismatch — client merges locally and retries

This is the version-vector pattern; concurrency under multiple devices uses optimistic concurrency control. Clients merge on conflict (M7.3).

### Dismiss propagation (Gap 1, DISMISS_ALL case)
When `POST /v1/messages/{id}/dismiss` is called and the plugin's `dismissBehavior == DISMISS_ALL`:
- Server sends FCM data message `type=dismiss, message_id=...` to all OTHER devices
- Devices receive and locally update their notification state without re-running plugin code

For `MARK_READ_ALL`, same fan-out but devices update local read state without dismissing visually.

For `CUSTOM_CALLBACK`, the dismissing device invokes the plugin's `onDismiss` hook locally; the result determines what to fan out (the plugin can choose what other devices do).

### Plugin install sync
Adding/removing a plugin updates the user's encrypted state blob. Other devices pulling state see the change on next sync.

When a plugin is installed via marketplace/URL, the device:
1. Verifies signature locally
2. Adds to encrypted_user_state install list (PUT /v1/state with CAS)
3. Requests permissions from user (per Q16 + I6 — per-device permission grants stored locally, not in user state)
4. On sync from another device, the other device sees the plugin in the install list, downloads the bundle, verifies signature, then prompts user for permissions (per-device).

### Files
- `server/app/routers/state.py` — NEW: GET + PUT with CAS
- `server/app/services/state.py` — NEW: load/upsert with optimistic concurrency
- `server/app/services/push.py` — UPDATE: add `dismiss_fanout` helper
- `server/app/routers/messages.py` — UPDATE: dismiss route calls fan-out when plugin's behavior is DISMISS_ALL
- `server/tests/test_state.py` — NEW: CAS happy path + 409 conflict + retry
- `server/tests/test_dismiss_fanout.py` — NEW: assert other devices receive FCM data message of type=dismiss

## Android-side (M7.2)

### State sync service
- `android/core/storage/.../EncryptedUserState.kt` — local mirror of the encrypted blob:
  ```kotlin
  data class UserStateV1(
    val installedPlugins: List<InstalledPluginRef>,   // (plugin_id, sender_id, version)
    val dismissedMessages: List<DismissedMessageEntry>,  // recent N
    val pluginSettings: Map<String, PluginSettings>,
    val userStorage: Map<String, Map<String, String>>,   // plugin_id -> key -> value (I1 scope=user)
  )
  ```
- `UserStateSyncer.kt`:
  - On app foreground: `GET /v1/state`, decrypt, diff against local, merge changes
  - On local change: serialize, encrypt with master_key, `PUT /v1/state` with current version
  - On 409: pull current, merge (M7.3), retry
  - Runs every 5 min while app is foregrounded

### Merge strategy (M7.3)
Three-way merge over the structured fields:
- `installedPlugins`: merge by `plugin_id`, last-installed wins (`installed_at` newer keeps)
- `dismissedMessages`: union (a dismissal anywhere is final)
- `pluginSettings`: merge by `plugin_id`, last-modified wins
- `userStorage[plugin_id][key]`: per-key LWW with monotonic timestamps

If structural conflict (different plugin_id at same position): explicit reconciliation via user prompt is V1.5; for V1 we LWW.

### Dismiss handling
- `core:push/...DismissEventHandler.kt` — receives FCM data message type=dismiss, cancels matching notification, updates local state
- `feature:inbox/.../InboxViewModel.kt` — observes encrypted user state for dismissed list, hides those messages locally

### Plugin install sync
- On `state.installedPlugins` change observed via sync: for each new plugin_id, prompt user with the install permission screen (Q16), then download bundle, verify, install via M4b
- Removed plugins: silently uninstall locally

### Files
- `android/core/storage/.../EncryptedUserState.kt`
- `android/core/storage/.../UserStateSyncer.kt`
- `android/core/storage/.../StateMerger.kt`
- `android/core/push/.../DismissEventHandler.kt`
- `android/feature/inbox/.../InboxViewModel.kt` — UPDATE
- Unit tests for `StateMerger` with conflict cases

## Plugin SDK changes (M7.4)

The SDK's `platform.message.dismissBehavior` getter/setter manipulates the plugin's chosen behavior; the host reads it via the manifest's static field by default and via this runtime API when plugins want to switch dynamically.

- `sdk-plugin/src/notifications.ts` — UPDATE: include `groupId` field (I2 default if plugin doesn't set: `${sender_id}::${plugin_id}`)
- `sdk-plugin/src/storage.ts` — UPDATE: confirm `scope` param is documented per I1
- Update example minimal plugin to demonstrate dismiss behavior choice

## Gap 3 — Plugin version mismatch UX (server already injects min_plugin_version)

Android side:
- If receiving a message with `min_plugin_version > installed version`: don't dispatch to plugin
- Render a "tap to update" placeholder card in inbox
- Tapping triggers plugin update flow (M8)

Implementation in `android/feature/inbox/.../IncomingMessageRouter.kt`:
```kotlin
if (PluginVersionComparator.isOlder(installedVersion, message.minPluginVersion)) {
  inboxStore.appendPlaceholder(message.id, plugin.name, message.minPluginVersion)
  return
}
```

## Tests

- StateMerger conflict cases (4 scenarios at minimum)
- 409 retry flow on PUT /v1/state
- Dismiss fan-out → other device cancels notification
- Min-version placeholder rendered when out of date

## Constraints
- All sync is async, never blocks UI
- Conflicts resolve locally; server never decides merge logic
- The encrypted blob format is versioned (`schema_version` field inside) for future migrations
- Test the merge by hand with sample blobs

## Print summary
- Files created
- The blob's internal schema_version constant
- How merge handles each conflict class
