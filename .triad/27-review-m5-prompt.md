# Review of M5 (Push + delivery + dynamic notifications)

The build is now in the "Claude builds, others review" protocol (lead switched from Codex after Codex hit usage limit). I (Claude) just authored M5 across two commits:

- `927359c` M5 (server): push delivery + messages + senders endpoints
- `f29c3ba` M5 (android): :core:push module - FCM service + foreground service

Review focus: security, contract adherence, edge cases. Items I want pressure-tested below.

## What landed

**Server (`server/`):**
- `app/services/senders.py` — register/get/get_active/revoke
- `app/services/messages.py` — store_message (validates pairing, plugin, devices; 410 if none), inbox_for_device, get_message_for_user, mark_dismissed; encrypted_body_pointer "inline:" scheme
- `app/services/push.py` — Firebase Admin lazy init; dev-mode no-op; FCM data payload contains ONLY (type, message_id, plugin_id, min_plugin_version) — never plaintext
- `app/routers/senders.py` — POST /v1/senders/register (rate-limited like signup)
- `app/routers/messages.py` — POST /send (sender-signed envelope verified via Ed25519), GET /inbox, GET /{id}, POST /{id}/dismiss
- `app/main.py`, `app/config.py`, `app/schemas.py`, `.env.example`, `pyproject.toml` updates
- `tests/test_messages.py` — happy path + 401 bad-sig + 410 no-device + dismiss

**Android (`android/core/push/`):**
- `SynclerFcmService` (FirebaseMessagingService) — routes data["type"] to PluginNotificationService or DismissEventHandler
- `PluginNotificationService` (Foreground Service, dataSync type on API 34+) — fetches body, decrypts, dispatches to PluginRegistry, builds final user notification
- `BatteryOptimizationCheck` — directs user to disable optimization for the app
- `PluginMessagePipeline` interface (host app implements)
- `FcmDispatcher`, `FcmTokenRegistrar` interfaces (host app implements)
- `DefaultNotificationFactory` + Hilt module
- Updated `core/network/SynclerApi.kt` with getMessage, inbox, dismissMessage endpoints
- AndroidManifest: declares both services with proper permissions

## Things I want you to pressure-test

1. **Envelope canonical bytes.** `routers/messages.py` builds `envelope_bytes` from `{sender_id, user_id, plugin_id, encrypted_body, nonce, min_plugin_version}` with sort_keys + separators(",", ":"). Sender SDK (not yet written — M9) MUST produce byte-identical bytes when signing. Is the field set complete? Should `expires_at` or a server-generated `message_id` be in the AAD/envelope? Trade-off: more fields = more replay protection, fewer fields = sender doesn't need to predict server state.

2. **AAD vs envelope.** The crypto-spec defines AAD = (message_id, sender_id, user_id, plugin_id, min_plugin_version, created_at, schema_version). But message_id is server-generated AFTER the sender signs. How does the sender include message_id in AAD if they don't know it yet? Either the AAD must be relaxed for V1 OR the sender generates a client-side message_id and the server adopts it. Currently the schema_version + created_at + message_id fields are likely DROPPED in my impl. Check.

3. **Replay window on /send.** Server doesn't dedupe by anything sender-supplied (e.g., idempotency_key). A network adversary intercepting one message can replay it. Server's `NonceRegistry` (M2) is in-memory only; tests use a fresh process. Real impact = duplicate delivery (plugin handles). Confirmed acceptable per Round 11 fix-up notes?

4. **encrypted_body_pointer inline scheme.** Stored as `inline:<b64_nonce>|<b64_ct>|<b64_sig>` in a TEXT column. Server parses it on inbox/fetch. Is the parsing robust? What if a malicious actor manages to register a sender and POST a body with the literal `|` separator in their base64 — wait, base64 doesn't include `|`. OK safe.

5. **Push payload leak surface.** Confirm I never include plaintext content in FCM data payload. Search `services/push.py` for plaintext leakage paths.

6. **Foreground service uptime budget.** PluginNotificationService has MAX_WORK_MS = 30s. If plugin code hangs, service aborts and user gets nothing. Is 30s the right number? Should this surface a "delivery failed" notification on timeout?

7. **Notification channel design.** Two channels: `syncler.foreground.delivery` (IMPORTANCE_MIN) and `syncler.user.messages` (IMPORTANCE_DEFAULT). User can disable either independently. Is this the right separation?

8. **Battery optimization UX.** `BatteryOptimizationCheck.requestIgnoreIntent` opens system settings. The Android manifest doesn't declare the related permission (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) — Play Store has policy restrictions on apps that request this. Is the helper enough for V1 (where user is dev) or does it need policy doc?

9. **POST /send rate limit by sender_id from header X-Sender-ID.** Header is unauthenticated input before signature verification. A spammer could send a flood of requests with random X-Sender-ID values bypassing the per-sender bucket. Counter-argument: each request still has to hit signature verification which is CPU-bound, but the bucket itself can be flooded. Is this OK for V1 or do we move to IP rate limit?

10. **Dismiss endpoint authn.** Currently JWT-auth via `current_user` and `device_id` is a query param (not validated to belong to the user). A user could dismiss messages by spoofing another device's device_id. Severity: own-account scope only. Should we add `device_id IN user's devices` check?

## Output format

```
=== AGREEMENTS WITH CLAUDE'S SELF-FLAGGED ITEMS ===
<which of items 1-10 you concur with + ID + 1-line take>

=== ADDITIONAL FINDINGS ===
- <severity> <title>
  evidence: <file:line>
  proposal: <fix>

=== SHOW-STOPPERS ===
<things that must be fixed before M6, or "none">

=== SAFE / NOT-AN-ISSUE ===
<things you pressure-tested and ruled out>
```

Be sharp. Don't restate the items above as findings — those are MY questions to you. Either confirm or disagree.
