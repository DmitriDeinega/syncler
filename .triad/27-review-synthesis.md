# M5 review synthesis (Claude+Gemini+Codex)

## Fix NOW (show-stoppers, before M6)

| ID | Title | Source | Action |
|---|---|---|---|
| S1 | AAD/envelope contract mismatch | Both | Reconcile crypto-spec: drop message_id/created_at/schema_version from required AAD (sender can't know them). AAD = canonical envelope bytes only. Update docs/crypto-spec.md + server/app/crypto/aead.py + android/core/crypto/Aad.kt. |
| S2 | Missing replay check at /send | Both | Wire `NonceRegistry` from M2 into `store_message`. (sender_id, nonce) pair seen → 409 Conflict. |
| S3 | Plugin ownership not verified | Codex | `services/messages.py:_plugin()` adds `Plugin.sender_id == sender_id` filter. |
| S4 | Android Hilt bindings missing → won't compile | Codex | Add stub `app`-module implementations of PluginMessagePipeline, FcmDispatcher, FcmTokenRegistrar. Stubs return no-ops until M6/M7 wire real ones. |
| S5 | /send succeeds with no FCM-tokened device | Codex | `store_message` requires at least one device with `fcm_token IS NOT NULL`. Otherwise 410. |

## Fix NOW (mediums that affect correctness)

| ID | Title | Source | Action |
|---|---|---|---|
| F1 | `expires_at` not in signed envelope | Both | Add to envelope canonical bytes. Sender supplies; server validates `expires_at > now` and `expires_at <= now + 30d` cap. |
| F2 | /send rate limit by X-Sender-ID header is spoofable | Both | Switch to IP-based rate limit for /send (post-auth per-sender bucket can come V1.1 once we verify sig before counting). |
| F3 | Dismiss `device_id` belongs to user not verified | Both | services/messages.mark_dismissed: confirm device.user_id == user.id. |
| F4 | Expired messages accepted/fetched | Codex | Reject past `expires_at` at store_message; filter `expires_at > now` in inbox/get. |
| F5 | PluginNotificationService scope cancellation | Codex | Cancel full scope on destroy, not only latest workJob. Track jobs in a set. |
| F6 | SynclerFcmService fire-and-forget async | Codex | Use goAsync() or WorkManager for dismiss + token registration. |

## Defer to M11 (polish, not show-stoppers)

- Silent timeout in PluginNotificationService → post "delivery failed" notification
- Battery optimization helper: use general settings intent + doc warning
- Inline pointer parser hardening (validate=True, length checks)
- WorkManager full migration for all FCM-side async

## Sources

- `.triad/27-gemini-review.txt`
- `.triad/27-codex-review.txt`
- `.triad/27-review-m5-prompt.md` (my self-flagged items)
