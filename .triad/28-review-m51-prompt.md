# Review of M5.1 (review fix-ups from M5 review round)

I (Claude, current build lead) just applied 11 fixes from the prior Codex+Gemini review of M5. Commit `412e8cd`. Your job: confirm the fixes are correct and didn't introduce new bugs.

## What was fixed

**Show-stoppers (S1-S5):**
- S1: AAD/envelope contract reconciled. AAD is now 5 fields (no encrypted_body, no nonce). Envelope (Ed25519 signing input) is 7 fields (AAD + encrypted_body + nonce). Server-generated fields (message_id, created_at, schema_version) removed entirely. See `docs/crypto-spec.md` §4 + §4.1.
- S2: Nonce replay check wired into `/v1/messages/send` AFTER signature verification (so attackers can't OOM the registry). `get_global_registry()` in `server/app/crypto/nonce.py`.
- S3: `services/messages._plugin()` enforces `Plugin.sender_id == sender_id`. Sender X can't use sender Y's plugin.
- S4: `android/app/.../push/PushHostBindings.kt` has stub @Singleton bindings for PluginMessagePipeline, FcmDispatcher, FcmTokenRegistrar. App should now compile.
- S5: `_active_devices_with_fcm()` — `/send` returns 410 if no device has an fcm_token (separate from "no active device" general check).

**Mediums:**
- F1: `expires_at` required in MessageSendRequest; included in signed envelope; rejected if past or > 30d cap.
- F2: `/send` rate-limited by IP via new `message_send_ip` config.
- F3: dismiss calls `assert_device_belongs_to_user`. 404 on foreign device.
- F4: inbox + get_message filter `Message.expires_at > now`.
- F5: PluginNotificationService tracks Set of active jobs; cancels supervisor on destroy. `postDeliveryFailed` notification on timeout.
- F6: SynclerFcmService cancels supervisor on destroy.

## Files of particular concern (please verify)

- `server/app/crypto/aead.py` — assemble_aad + assemble_envelope split
- `server/app/routers/messages.py` — full rewrite. Note replay check ordering: signature verify → nonce check → store_message.
- `server/app/services/messages.py` — _plugin ownership check, _active_devices_with_fcm, ExpiredEnvelopeError, expires_at filter on inbox/get
- `server/tests/test_crypto.py` — vectors updated. NB: ciphertext hex assertion removed (depends on AAD); round-trip retained.
- `server/tests/test_messages.py` — new tests for replay, plugin ownership, expired, no-FCM-device, dismiss foreign device.
- `android/core/crypto/Aad.kt` — MessageAad + MessageEnvelope split
- `android/core/push/PluginNotificationService.kt` — scope tracking + delivery-failed notification
- `android/app/.../push/PushHostBindings.kt` — Hilt stubs

## What I want from you

1. Sanity-check each fix vs the original finding. Anything I missed or fixed wrong?
2. New bugs introduced by these changes? (Test changes, schema changes, contract changes are the riskiest.)
3. Is the AAD/envelope split clean enough that the upcoming server SDK (M9) can produce byte-identical bytes to Android?
4. Does the test for "no FCM token → 410" actually exercise that path correctly?
5. Anything still wrong with the rate-limit IP fallback for /send?

## Output format

```
=== AGREEMENTS WITH FIXES ===
S1: <ok | concern: ...>
S2: ...
...

=== NEW BUGS INTRODUCED ===
<bugs the fixes caused, or "none">

=== STILL OPEN FROM M5 REVIEW ===
<items from prior review still unfixed, or "all addressed">

=== SHOW-STOPPERS FOR M6 ===
<must-fix-before-M6, or "none">

=== READY FOR M6? ===
<yes | no>
```
