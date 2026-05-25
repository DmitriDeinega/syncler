=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 129 — Phase 9b close-out review

Phase 9b implementation is end-to-end shipped. This is the
final close-out review covering the complete V2 per-device
envelope encryption stack: server, Python SDK, Android.

## Commit history since the last triad (128)

| Commit  | Step | Content |
|---------|------|---------|
| 1c9cf85 | 7c-pre | Triad 128 fixups: inbox INNER JOIN on delivery_status (V1 LEFT JOIN was returning undecryptable items to newly-enrolled devices), plugin-scoped card upsert (V1 keyed by sender+user+card_key allowed cross-plugin collision), tightened DB unique to (sender, user, plugin, card_key). |
| 0619bdb | 7b   | Android DeviceEncryptionKeyStore for X25519 keypair persistence via SecurePrefs/EncryptedSharedPreferences. |
| 816dbfc | 7c+7d | Android V2 wire + InboxRepository V2 decrypt path. DeviceEnrollRequest gains encryption_public_key; new RecipientEnvelopeDto + V2 inbox DTOs (envelope_kind discriminator); InboxRepository picks own envelope by device_id, HPKE-opens CEK, AES-GCM-decrypts payload. Drops V1 candidate-pairing-key loop. DeviceEncryptionKeyStore refactored to interface for testability. |

## What ships for review

Triad 128 already reviewed the server + SDK + Android HPKE
module + DeviceEncryptionKeyStore (Gemini GREEN, Codex YELLOW
with 2 fixed items). This consult focuses on the new code at
1c9cf85 + 816dbfc:

### Server fixups (1c9cf85)

- `server/app/services/messages.py:inbox_for_device` — INNER
  JOIN on DeliveryStatus (was LEFT JOIN). Server fans out
  DeliveryStatus rows at publish time for every active device;
  devices enrolled after a publish won't have a row and
  therefore won't see undecryptable items.
- `server/app/services/cards.py:upsert_live_card_v2` — lookup
  now includes plugin_id.
- `server/app/models.py:LiveCard.__table_args__` — unique now
  `(sender_id, user_id, plugin_id, card_key)`.
- `server/alembic/versions/0011_per_device_encryption.py` —
  drops old `uq_live_cards_sender_user_key` and creates the
  new `uq_live_cards_sender_user_plugin_key`. Data wipe at the
  top of the same migration guarantees no orphan rows when the
  constraint flips.

### Android V2 wire + decrypt path (816dbfc)

- `android/core/network/.../SynclerApi.kt`:
  - `DeviceEnrollRequest` adds `encryptionPublicKey` (base64
    32-byte X25519).
  - `DeviceEncryptionKeyRotateRequest` (new) for
    `PUT /v1/auth/devices/me/encryption_key`.
  - `RecipientEnvelopeDto` (new) — base64 hpke_kem_output (32B)
    + hpke_ciphertext (48B) + device_id.
  - `MessageInboxItemDto` / `LiveCardItemDto` rewritten to V2
    shape (protocol_version, envelope_kind, payload_nonce,
    payload_ciphertext, recipient_envelopes,
    recipient_directory_version, envelope_signature).
  - `InboxFeedItemDto` restructured as discriminated record on
    envelope_kind; card-specific fields populated only for
    live_card_upsert.
- `android/core/storage/.../DeviceEncryptionKeyStore.kt`
  refactored from `open class` to interface + concrete
  `SecurePrefsDeviceEncryptionKeyStore`. Hilt @Binds wiring.
  Tests use a `FakeDeviceEncryptionKeyStore` without needing
  Context.
- `android/core/auth/.../AuthRepository.kt` — `enrollDevice`
  now sends both Ed25519 + X25519 pubkeys.
- `android/core/crypto/.../Aad.kt` — V2Aad object: canonical
  bytes for event/live_card payload AAD and per-recipient
  HPKE info. Byte-identical to server `envelopes_v2.py`.
- `android/feature/inbox/.../InboxRepository.kt` — V2 decrypt
  path:
  1. `openV2EnvelopeOrNull(dto, hpkeInfoFactory, payloadAad)`:
     finds own envelope by device_id, HPKE-opens CEK with
     `DeviceEncryptionKeyStore.getOrCreateKeypair()`, then
     AES-GCM-decrypts payload using CEK + payload_nonce +
     payload_aad.
  2. `decryptEventMessage` / `decryptLiveCard` — V2 entry
     points. PairedSender list consulted only for the sender
     display name. V1's candidate-pairing-key loop deleted.
  3. Silent drop if no recipient_envelope matches this device
     (defense-in-depth; the server INNER JOIN fix should
     prevent the case from reaching the client).

## Tests passing

- `server/tests/test_phase9_hpke.py` — 10 (HPKE module).
- `server/tests/test_phase9_recipient_classifier.py` — 10
  (recipient classifier matrix; Postgres test DB).
- `server/tests/test_devices.py` — 9 (V2 enroll body).
- `sdk-python/tests/` — 38.
- `android/core/crypto/.../HpkeTest.kt` — cross-platform
  vector + keypair generation + sha256.
- `android/core/auth/testDebugUnitTest` — all pass.
- `android/core/storage/testDebugUnitTest` — passes.
- `android/feature/inbox/testDebugUnitTest` — passes.
- `:app:assembleDebug` — succeeds.

## Known outstanding work (non-blocking)

- **V1 server test cleanup**: `test_crypto`, `test_messages`,
  `test_phase3`, `test_phase8_state`, `test_cards` reference
  the V1 wire shape (encrypted_body / nonce / encrypted_payload)
  that 50f7fdc deleted. They fail at parse time. Either delete
  them or rewrite to V2. Tracked as a follow-up; not blocking
  Phase 9b close-out because V0.1 dev-mode tolerates the
  testing gap.
- **PluginLoader / inbox-side SynclerApi.rotateDeviceEncryptionKey
  caller**: the endpoint exists on Android's Retrofit API
  surface, but no caller wires it to user UI yet. Rotation is
  triggered manually only (DeviceEncryptionKeyStore.rotate()
  call sites are zero outside tests). UX is a Phase 11+ /
  V2 feature — not Phase 9b scope.
- **No "ready" SSE handler for `devices.changed`**: the server
  emits the event when a device enrolls/rotates/revokes (spec
  §11.13). The Android client doesn't yet refetch its own
  device list on receipt. Minor — refresh-on-next-pull picks
  up changes within at most one inbox poll cycle.

## Concerns I want a second opinion on

1. **Open-V2-envelope failure modes.** When HPKE.open throws,
   InboxRepository.openV2EnvelopeOrNull logs and returns null;
   the inbox item is silently dropped from the visible list.
   Same for AES-GCM. The user sees "no message" rather than
   "couldn't decrypt." Should the UI surface "this message
   failed to decrypt" so the user knows something is off?
   (Currently no UI hint at all — a key rotation gap would
   silently lose messages.)

2. **Ed25519 signature verification on the recipient side.**
   I'm NOT verifying the envelope's Ed25519 signature in the
   Android decrypt path. The HPKE info binds
   `payload_ciphertext_sha256` so a swapped ciphertext fails
   HPKE-open. The HPKE info also binds device_id so an
   envelope_swap fails. But a malicious server could forge an
   entire envelope with a fake Ed25519 signature — the device
   would HPKE-open successfully if it had been sealed to the
   real device's pubkey, but the binding of `sender_id` in
   the info means it'd have to know what sender_id to claim.
   Need to add Ed25519 verify on the device for full §11.8
   compliance. Block before close-out, or follow-up?

3. **Recipient-set freshness on the device side.** The device
   receives `recipient_envelopes` but doesn't currently check
   that its own device_id is INCLUDED. Of course if it's not
   included the `openV2EnvelopeOrNull` returns null and the
   item drops — but a stricter check (assert own device_id is
   in the array) would catch a class of server bugs. Worth
   adding?

4. **Cross-platform vector test maintenance.** The 80-byte
   HPKE-vector hex bytes in
   `HpkeTest.kt:crossPlatformVector_opens_cek_and_decrypts_payload`
   are pinned to a specific PyCA 48.0.0 generation. Future
   `cryptography>=48.x` could in principle change KEM output
   layout (unlikely but possible). Pin the version range in
   `server/pyproject.toml` to `cryptography>=48.0.0,<49`? Or
   leave loose and rely on the test to flag any future drift?

5. **Plugin-scoped delete + V1 carryover.** Codex 128 #2 was
   fixed for upsert + DB constraint, but the V1
   `LiveCardDeleteRequest` schema (pre-Phase-9b shipped at
   Phase 12) does NOT carry plugin_id. The new
   `LiveCardDeleteRequestV2` DOES. The old V1 schema sits in
   `app/schemas.py` as dead code now — should I delete it as
   part of Phase 9c, or leave it for the V1 test cleanup?

## Output

Per reviewer, terse:

1. Verdict on Phase 9b close-out: GREEN / YELLOW / RED + items.
2. Anything blocking Phase 9 from closing.
3. Answers to the 5 concerns above.

If dual-GREEN, Phase 9 (per-device envelope encryption) closes.
Phase 11 (V1.5 #2 native Kotlin plugin runtime) is the next
unblocked track.

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
