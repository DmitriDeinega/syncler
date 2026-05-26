=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 161 — deferred quick-wins post-work

Triad 160 pre-work cleared all five items + gemini's
bonus. Shipped at commit 41286de. This is the close-out
check — was each implemented correctly vs the design
proposal you signed off on?

## Items shipped (one commit, six changes)

1. **`sdk-python/syncler/client.py:Client.live_push(...)`**
   — V3 #14 typed helper. Channel-name regex pre-check
   matches server's `_valid_channel_name`. Returns the
   server's `delivered` count.

2. **`sdk-python/syncler/client.py:Client.publish_plugin(...,
   live_inbound_url=None)`** — new kwarg. Includes
   `live_inbound_url` in the body only when non-None
   (mirrors the existing conditional-include pattern for
   renderer/template/card_type/card_key_path).

3. **`server/app/routers/server_info.py:webhook_public_key()`**
   — new module mounted under `/v1/server`. Returns the
   Ed25519 public key derived from `SERVER_SIGNING_SEED_B64`.
   503 (per codex preference) when seed unset / malformed /
   wrong length. Unauthenticated. 5 focused tests in
   `tests/test_v3_14_webhook_public_key.py`.

4. **`android/feature/plugin-host/.../live/SessionDeviceJwtProvider.kt`**
   — extracted the inline lambda from `PluginLoader.android()`.
   Distinct messages + `AUDIT_KEY_NO_SESSION_WIRED` audit
   key for the wiring-gap branch. 3 tests in
   `SessionDeviceJwtProviderTest.kt`.

5. **`android/feature/settings/.../LostDeviceFlow.kt`** —
   `Done.affectedSenders` renamed to `Done.sendersToReview`,
   populated from `PairedSenderStore.pairedSenders.value`.
   `:feature:settings` now depends on `:core:storage`.

6. **`sdk-python/syncler/client.py:Client.get_sender_info()`**
   — gemini's bonus first-run validation helper. Hits
   `GET /v1/senders/{id}`.

Also: `docs/integration-guide.md` §12 ships a new "The full
live-channel integration loop" ordered checklist (codex's
"missed partner-facing risk").

## What I'm asking for

For each item:
1. Did the shipped code match the design you signed off?
2. Any drift or accuracy bug?
3. Any cross-impact (test surface, other call sites,
   docs) you flagged in 160 that wasn't addressed?

Cap response at 300 words. Goal: "ship" or a short
list of last-mile FIXes.
