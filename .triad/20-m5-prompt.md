# M5 — Push delivery + dynamic notifications

You completed M1–M4b. M5 wires FCM data messages through the server to all of a user's devices, with the plugin's JS code running at receive time to generate the notification text (Q18 = code-based dynamic).

Workspace-write granted to `d:\Projects\syncler\`. Touch both `server/` and `android/`.

## Server-side (M5.1)

### Outbound: `POST /v1/messages/send`
Sender authenticates via signed envelope (verify with `senders.public_key`). Request body:
```json
{
  "user_id": "<UUID>",
  "plugin_id": "<UUID>",
  "encrypted_body": "<base64>",        // ciphertext_with_tag (no nonce — that's separate)
  "nonce": "<base64 12 bytes>",
  "min_plugin_version": "1.0.0",
  "envelope_signature": "<base64 Ed25519 over canonical envelope>"
}
```
Server:
1. Verifies signature against `senders.public_key` for the bound sender_id
2. Checks `senders.revoked_at IS NULL`
3. Checks active pairing exists with target user
4. Checks recipient has the plugin installed on at least one device. If not → `410 Gone` (per I6)
5. Inserts into `messages` table; inserts `delivery_status` rows per device
6. Triggers FCM fan-out (M5.2)
Returns: `{ "message_id": "<UUID>", "expires_at": "..." }`

### FCM fan-out (M5.2)
- `server/app/services/push.py` — NEW: async function `push_to_devices(session, message_id)`:
  1. Loads message + device list with FCM tokens
  2. For each device with `fcm_token IS NOT NULL AND revoked_at IS NULL`:
     - Sends an FCM data message with payload `{ "message_id": "...", "plugin_id": "...", "min_plugin_version": "..." }` — NO body content (server is blind)
     - Uses Firebase Admin SDK (`firebase-admin>=6.4.0`)
  3. Updates `delivery_status.delivered_at` after FCM acks
  4. On FCM failure (token invalid): mark device's `fcm_token = NULL`; log

### FCM credentials
- `server/app/config.py` — UPDATE: add `firebase_service_account_path: str | None = None`. If unset, push is a no-op (development mode). Production must set it.
- `server/.env.example` — UPDATE: add `FIREBASE_SERVICE_ACCOUNT_PATH=/secrets/firebase.json`
- `server/app/services/push.py` lazy-loads the Firebase Admin SDK app on first push call

### Inbox fetch
- `GET /v1/messages/inbox?since=<timestamp>` — device-paged
  Returns:
  ```json
  {
    "messages": [
      {
        "id": "<UUID>",
        "sender_id": "<UUID>",
        "plugin_id": "<UUID>",
        "min_plugin_version": "1.0.0",
        "encrypted_body": "<base64>",
        "nonce": "<base64>",
        "envelope_signature": "<base64>",
        "sent_at": "..."
      }
    ],
    "next_since": "..."
  }
  ```
- Rate-limited per device
- Returns only messages not yet delivered to the requesting device OR all messages since `since` if specified

### Dismiss endpoint
- `POST /v1/messages/{id}/dismiss` — sets `delivery_status.dismissed_at = now()` for the calling device
- Per Gap 1: if plugin's `dismissBehavior == DISMISS_ALL`, server fans the dismiss event to other devices via FCM data message type=`dismiss`

## Android-side (M5.3)

### FCM integration
- `android/core/network/.../FirebaseMessagingService.kt` — `class SynclerFcmService : FirebaseMessagingService()`:
  - `onMessageReceived(remoteMessage)`:
    - Extract `message_id`, `plugin_id`, `min_plugin_version`
    - Acquire wake lock briefly
    - Start `PluginNotificationService` (Foreground Service) with the message metadata
  - `onNewToken(token)`: PATCH device's FCM token via API

### Foreground service for plugin dispatch
- `android/core/push/.../PluginNotificationService.kt` — `ForegroundService` that:
  1. Starts a foreground notification ("Receiving message...") for the 10-30s window during which it executes plugin code
  2. Fetches the encrypted message body from the server via `GET /v1/messages/{id}` (NEW endpoint — single message fetch by ID)
  3. Decrypts via `core:crypto` `Aead.decrypt` using the user's pairing key for that sender
  4. Verifies signature
  5. Routes to the `PluginRegistry` (M4b): finds the plugin instance for `plugin_id`, calls `instance.dispatchOnMessage(decrypted_payload)`
  6. Plugin returns `NotificationDescriptor { title, body, importance, groupId }`
  7. Service builds the actual `Notification` and posts via `NotificationManager`
  8. If plugin's installed version < `min_plugin_version`: post the "tap to update" placeholder notification (Gap 3)
  9. Stops the foreground service

### Battery / OEM hardening
- On startup, check `PowerManager.isIgnoringBatteryOptimizations(packageName)`. If false, show a one-time settings prompt directing user to disable battery optimization for the app (required on Samsung/OnePlus for reliable background FCM).
- Use `setForegroundServiceType` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (Android 14+).

### New endpoints needed
- `GET /v1/messages/{id}` — server endpoint that returns single message (encrypted body + envelope) for an authenticated device. Required for the foreground service to fetch the body after FCM wake.

## Tests

### Server
- `server/tests/test_messages.py` — NEW: signed envelope → /send → message stored → FCM call (mocked) → delivery_status populated; rejection on revoked sender / bad signature / no recipient device with plugin
- `server/tests/test_dismiss.py` — NEW: dismiss endpoint flow + DISMISS_ALL fan-out

### Android
- `android/core/push/src/test/.../PluginNotificationServiceTest.kt` — NEW: with a fake bridge, confirm a decrypted payload routes to a mock plugin and the resulting NotificationDescriptor becomes a posted Notification (use Robolectric)
- `android/core/network/src/test/.../FcmServiceTest.kt` — NEW: assert RemoteMessage parsing extracts correct fields

## Constraints
- All async via coroutines on Android, asyncio on server
- No silent failures — every failed delivery logs at WARN with reason
- Server `push.py` must NEVER include plaintext content in FCM payload
- Foreground service uptime budget: 30s max per message; abort otherwise
- All new deps pinned in pyproject.toml / libs.versions.toml

## Print summary
- Files created on server side + Android side
- Confirmation that the only FCM payload is opaque metadata
- How to inject a fake Firebase Admin client for tests
