=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 128 — Phase 9b mid-track review

Phase 9a (per-device envelope encryption design) closed at triad
127 dual-GREEN. Phase 9b implementation has shipped the
following commits:

| Commit  | Step | Content |
|---------|------|---------|
| 416572b | 1    | crypto-spec.md §11 + ROADMAP correction |
| c7267c7 | 2a   | Alembic 0011: devices.encryption_public_key BYTEA NULL, devices.updated_at, users.device_directory_version BIGINT; data migration drops V1 messages/cards |
| e9819e8 | 3    | server HPKE module + 10 unit tests (cryptography>=48) |
| 28dcec7 | 4a   | V2 Pydantic schemas |
| 6b3e0de | 4b   | device enroll + rotate + sender directory endpoints, spec §11.9 amended to POST signed body |
| 50f7fdc | 4c+4d | messages.send V2 + cards.upsert V2 + cards.delete V2 + recipient classifier + V2 envelope service; live_cards schema rewrite |
| 4125d03 | 5    | 10-test recipient classifier matrix |
| 4fbf1d5 | 6    | Python SDK V2 (seal + directory fetch + retry once on 409) |
| e220af8 | 7a   | Android HPKE (BouncyCastle 1.80) + cross-platform vector test (Android opens a PyCA-sealed envelope, recovers CEK, AES-GCM-decrypts payload) |
| 0619bdb | 7b   | Android DeviceEncryptionKeyStore (X25519 keypair in EncryptedSharedPreferences) |

## What's still pending in Phase 9b

- **Step 7c**: Update Android SynclerApi.kt with V2 enroll body
  shape + new directory + rotation endpoints.
- **Step 7d**: Rewrite Android InboxRepository decrypt path —
  parse V2 envelope, verify Ed25519, find own
  recipient_envelope by device_id, HPKE-open CEK, AES-GCM-decrypt
  payload.
- **V1 test cleanup**: `test_crypto.py`, `test_messages.py`,
  `test_phase3.py`, `test_phase8_state.py`, `test_cards.py` all
  reference V1 wire shapes — broken since 50f7fdc.

## Files in scope for this review

### Server

- `server/app/crypto/hpke.py` — Suite, build_hpke_info, build_payload_aad,
  seal_cek_for_device, open_cek_for_device, encrypt_payload,
  decrypt_payload, sha256_hex.
- `server/app/services/envelopes_v2.py` — classify_recipient_set
  (8-row matrix), build_event_envelope_bytes /
  build_live_card_upsert_envelope_bytes /
  build_live_card_delete_envelope_bytes, build_v2_pointer /
  parse_v2_pointer.
- `server/app/routers/messages.py` — V2 publish + V2 inbox.
- `server/app/routers/cards.py` — V2 upsert + V2 delete.
- `server/app/routers/devices.py` — V2 enroll, rotation.
- `server/app/routers/senders.py` — directory fetch endpoint.
- `server/app/services/devices.py` — enroll/rotate/revoke bump
  device_directory_version transactionally.
- `server/app/services/cards.py` — V2 upsert + V2 delete with
  plugin_id scoping.
- `server/app/schemas.py` — V2 Pydantic models.
- `server/alembic/versions/0011_per_device_encryption.py`.
- `server/app/models.py` — Device + User + LiveCard updates.

### SDK Python

- `sdk-python/syncler/crypto.py` — V2_HPKE_SUITE,
  seal_v2_envelopes, build_v2_hpke_info, build_v2_payload_aad,
  assemble_event_envelope_v2 / live_card_upsert / delete,
  assemble_directory_fetch_envelope.
- `sdk-python/syncler/client.py` — fetch_device_directory (60s
  cache), send_to V2 with retry, upsert_card V2, delete_card V2
  (adds plugin_id).

### Android

- `android/core/crypto/src/main/kotlin/.../Hpke.kt` —
  generateX25519Keypair, openCekForDevice (BouncyCastle 1.80
  HPKE Base, empty aad to match PyCA).
- `android/core/crypto/src/test/kotlin/.../HpkeTest.kt` —
  cross-platform vector confirming canonical info bytes +
  HPKE seal/open + AES-GCM-decrypt interoperate across Python
  PyCA 48 and BC 1.80.
- `android/core/storage/src/main/kotlin/.../DeviceEncryptionKeyStore.kt`
  — wraps SecurePrefs (androidx EncryptedSharedPreferences) for
  the X25519 keypair.

## Concerns I want a second opinion on

1. **HPKE aad always-empty.** PyCA's single-shot HPKE API takes
   only `info` (not RFC 9180's separate `aad`). To match
   cross-platform, BC's HPKE call passes empty aad too. This
   means ALL per-recipient authenticated context — including
   `device_id`, `payload_ciphertext_sha256`, etc. — is in HPKE
   `info`. Acceptable, or is there a security argument for
   adding aad on the BC side that I'm missing? (RFC 9180
   permits aad but doesn't require it; the canonical info bytes
   already bind everything the design needs.)

2. **Verify-before-trust ordering** (Codex 127 guardrail #4).
   The publish path now does:
   - Read `sender_id` from the unsigned body
   - Look up sender's public key
   - Verify Ed25519 over `build_event_envelope_bytes(payload)`
   - ONLY THEN run rate-limit / nonce-replay / classifier / store
   The `sender_id` read is unsigned, but the verify uses the
   server's stored key for THAT sender_id — so a forged body
   with mismatched sender_id can only spoof the EXISTING
   sender's key (and fail signature). Adequate?

3. **Recipient classifier ordering.** The 8-row matrix executes
   in a specific order:
   1. duplicate device_id → 400
   2. unknown device_id → 400
   3. directory_version > server → 400
   4. missing active device → 409
   5. directory_version < server (with complete set) → 409
   6. recently_revoked extras → tolerated (logged)
   7. all active devices covered → success
   8. user not found → 404
   Test matrix in `tests/test_phase9_recipient_classifier.py`
   covers all 10 cases (8 above + null-encryption-key device
   treated as unknown + long-revoked treated as unknown). Any
   row I'm missing?

4. **Migration safety.** 0011 hard-deletes V1 messages +
   delivery_status + live_cards rows and drops the live_cards
   V1 columns (encrypted_payload, nonce) replacing with
   encrypted_body_pointer + card_type + min_plugin_version.
   V0.1 dev-mode tolerates this; production would need a more
   careful coexistence path. Acceptable for now given "we still
   developing on my local pc. so everything is v0.1"?

5. **Pointer storage.** Message.encrypted_body_pointer column
   (TEXT) now stores `"v2:" + base64(canonical_json(<v2 wire
   fields>))`. The V1 inline format is gone. The inbox endpoint
   reconstructs the full envelope on every fetch. Bandwidth +
   storage overhead acceptable, or worth moving to a separate
   blob table?

6. **SDK retry pattern.** send_to / upsert_card refetch
   directory ONCE on 409 stale_recipient_set, then re-encrypt
   + re-publish. Repeated 409 raises SynclerError. Aggressive
   enough? Or should it retry more times with backoff in case
   of a busy device-rotation period?

7. **DeviceEncryptionKeyStore.** Stores raw 32-byte X25519
   private key in EncryptedSharedPreferences under the master
   key wrapping that file. Android Keystore-backed approach
   would be stronger (key never leaves TEE), but Android
   Keystore doesn't support X25519 directly. BC's keypair
   primitives work in pure software. Trade-off OK for V0.1?

8. **Android HPKE library choice.** Gemini 125 recommended
   Tink. I went with BC 1.80 (already on classpath, no new
   dep). The cross-platform vector test confirms BC and PyCA
   interoperate. Worth flagging? The Tink advantage was
   ergonomics, which doesn't matter once the Hpke.kt API
   surface is stable.

## Test status

- `server/tests/test_phase9_hpke.py` — 10 pass.
- `server/tests/test_phase9_recipient_classifier.py` — 10 pass
  (real Postgres test DB).
- `server/tests/test_devices.py` — 9 pass (V2 enroll body).
- `server/tests/test_crypto.py` / `test_messages.py` /
  `test_phase3.py` / `test_phase8_state.py` / `test_cards.py` —
  broken since 50f7fdc; reference V1 wire formats. Will be
  migrated in the V1 test cleanup pass.
- `sdk-python/tests/` — 38 pass.
- `android/core/crypto/.../HpkeTest.kt` — passes (cross-platform
  vector + keypair generation + sha256 sanity).

## Output

Per reviewer, terse:

1. Verdict on Phase 9b so far: GREEN / YELLOW / RED + items.
2. Anything blocking before step 7c (Android API + InboxRepository
   rewire) lands.
3. Answers to the 8 concerns above.

Step 7c/7d should land before Phase 9b closes. If dual-GREEN
here, those steps proceed; if anything's RED, fix first then
re-consult.

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
