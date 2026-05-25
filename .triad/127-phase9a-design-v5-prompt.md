=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 127 — Phase 9a design v5 (PyCA HPKE API correction)

Triad 126: Gemini GREEN. Codex YELLOW on two items. v5 addresses
both.

## v5 deltas vs v4

### Block 1: PyCA HPKE API correction + drop AAD (Codex 126 #1 + #2)

Codex 126 web-searched the PyCA HPKE 47/48 docs. The actual
single-shot API:

```python
from cryptography.hazmat.primitives.hpke import Suite, KEM, KDF, AEAD

SUITE = Suite(KEM.X25519, KDF.HKDF_SHA256, AEAD.AES_256_GCM)

# Seal (sender):
enc_concat_ct = SUITE.encrypt(
    plaintext=cek_bytes,
    public_key=recipient_x25519_pub,
    info=hpke_info_canonical_bytes,
)
# enc_concat_ct = enc || ciphertext (X25519 enc = 32 bytes,
# ciphertext = CEK_size + AEAD tag = 32 + 16 = 48 bytes,
# total 80 bytes).

# Open (recipient):
cek = SUITE.decrypt(
    ciphertext=enc_concat_ct,
    private_key=device_x25519_private_key,
    info=hpke_info_canonical_bytes,
)
```

PyCA's single-shot API exposes `info` but NOT a separate `aad`.
RFC 9180 SealBase has both; PyCA folds them or only supports
info. Either way, for cross-platform compatibility (PyCA on
server/SDK + Tink on Android), **v5 drops the HPKE `aad` field
entirely. ALL authenticated context goes into HPKE `info`.**

Updated per-recipient HPKE info (canonical JSON, sorted keys,
UTF-8, no whitespace separators):

```json
{
  "card_key": "...",            // ONLY for live_card_upsert; omit otherwise
  "card_type": "...",           // ONLY for live_card_upsert; omit otherwise
  "device_id": "<uuid>",
  "envelope_kind": "event" | "live_card_upsert",
  "expires_at": "...",
  "min_plugin_version": "...",
  "payload_ciphertext_sha256": "<hex>",
  "payload_nonce": "<b64>",
  "plugin_id": "...",
  "protocol_version": 2,
  "sender_id": "...",
  "sequence_number": 17,        // ONLY for live_card_upsert; omit otherwise
  "user_id": "..."
}
```

The presence/absence of card-specific fields is determined by
`envelope_kind`. Canonical JSON omits missing keys (rather than
emitting null) — a swap from event to upsert thus produces a
fundamentally different info, so an attacker can't replay an
HPKE wrap across envelope kinds.

Wire framing: the server still SPLITS the PyCA `encrypt()`
output into `hpke_kem_output` (first 32 bytes) and
`hpke_ciphertext` (remaining 48 bytes) for the JSON wire format
+ schema validation cleanliness. On open, server concatenates
`hpke_kem_output || hpke_ciphertext` and passes the 80-byte
blob to `SUITE.decrypt()`.

Android (Tink): Tink's `HpkeContext` single-shot API does
expose `applicationInfo` and `aad`. We pass empty `aad` to
match PyCA's behavior; `applicationInfo` carries the same
canonical `info` bytes. Cross-platform test vectors confirm
byte-equivalence.

### Block 2: recipient classification rule (Codex 126 nit)

Server-side recipient validation runs in this order:

```
Given: incoming recipient_envelopes[i].device_id list
       active_devices = users.devices WHERE
         revoked_at IS NULL AND
         encryption_public_key IS NOT NULL
       recently_revoked = users.devices WHERE
         revoked_at > now() - INTERVAL '5 minutes'
       all_known = active_devices UNION recently_revoked

(1) Reject duplicates within recipient_envelopes
    → 400 duplicate_device_id

(2) Reject device_ids NOT in all_known
    → 400 unknown_recipient
    (covers: never-existed, belongs to a different user,
     long-revoked = revoked_at <= now() - 5 minutes)

(3) Compute missing = active_devices - recipient_envelope_set
    If missing is non-empty → 409 stale_recipient_set
    with X-Stale-Directory-Version header + JSON body
    listing missing_device_ids + current_directory_version.

(4) recently_revoked devices in recipient_envelopes:
    tolerated. No error. Logged at INFO level for
    auditing. (Sender's directory cache was 4 minutes old
    when this device was revoked; the next refresh corrects.)

(5) Verify recipient_directory_version >= server's stored
    users.device_directory_version - 1 (one-version-old
    tolerance for in-flight races). Smaller → 409 stale.
    Larger → 400 (sender is reading from a stale REPLICA or
    is lying; either way, untrustworthy).
```

Test matrix (Phase 9b):

| Case                                   | Status | Code                     |
|----------------------------------------|--------|--------------------------|
| Duplicate device_id                    | 400    | duplicate_device_id      |
| Unknown device_id (long-revoked)       | 400    | unknown_recipient        |
| Unknown device_id (belongs to user B)  | 400    | unknown_recipient        |
| Missing active device                  | 409    | stale_recipient_set      |
| Stale directory_version (too old)      | 409    | stale_recipient_set      |
| Directory_version too new              | 400    | invalid_directory_version|
| Recently-revoked extra                 | 200    | (logged only)            |
| All active devices covered exactly     | 200    | (success)                |

### Carry-overs from v4 (unchanged)

- Wire format split into `hpke_kem_output` + `hpke_ciphertext`.
- `recipient_directory_version` in Ed25519 canonical envelope.
- Sender directory endpoint
  `GET /v1/senders/me/devices?user_id={uuid}`.
- Complete-fanout enforcement.
- Endpoint prefix `/v1/auth/devices/enroll` + new
  `PUT /v1/auth/devices/me/encryption_key`.
- HPKE Base mode + Ed25519 envelope sig.
- Card-specific envelope_kind / card_key / card_type /
  sequence_number.
- Delete envelope adds `plugin_id` (Codex 125).
- Trusted device directory trust model.
- BYTEA NULL during migration.
- Recipient cap = 32.
- V1 historical cards deleted in migration.
- Re-enrolled devices can't decrypt pre-enrollment payloads.
- Tink for Android HPKE.
- SSE for user devices only; senders refresh on TTL/409.
- SDK migration: pair returns directory path, not pairing_key.

## Updated wire format (final)

### Event publish

```json
{
  "protocol_version": 2,
  "envelope_kind": "event",
  "sender_id": "...",
  "user_id": "...",
  "plugin_id": "...",
  "expires_at": "...",
  "min_plugin_version": "...",
  "payload_nonce": "<b64 12-byte>",
  "payload_ciphertext": "<b64>",
  "recipient_envelopes": [
    {
      "device_id": "<uuid>",
      "hpke_kem_output": "<b64 32-byte>",
      "hpke_ciphertext": "<b64 48-byte>"
    },
    ...
  ],
  "recipient_directory_version": 42,
  "envelope_signature": "<b64 Ed25519>"
}
```

### Live-card upsert

Same as event plus `card_key`, `card_type`,
`sequence_number`. `envelope_kind: "live_card_upsert"`.

### Live-card delete

```json
{
  "protocol_version": 2,
  "envelope_kind": "live_card_delete",
  "sender_id": "...",
  "user_id": "...",
  "plugin_id": "...",       // NEW (Codex 125)
  "card_key": "...",
  "nonce": "<b64 12-byte>",
  "expires_at": "...",
  "envelope_signature": "<b64 Ed25519>"
}
```

No `recipient_envelopes` (delete is unencrypted; the lookup is
purely metadata).

### Sender directory response

```json
{
  "directory_version": 42,
  "user_id": "<uuid>",
  "devices": [
    {
      "device_id": "<uuid>",
      "encryption_public_key": "<b64 32-byte>",
      "updated_at": "<ISO8601>"
    },
    ...
  ]
}
```

### Device enrollment request additions

```json
POST /v1/auth/devices/enroll
{
  "public_key": "<b64 Ed25519>",
  "encryption_public_key": "<b64 X25519>",  // NEW
  "fcm_token": "..."
}
```

## Output

Per reviewer, terse:

1. Verdict on v5: GREEN / YELLOW / RED + items.
2. Anything still missing.

If dual-GREEN, Phase 9a closes here and Phase 9b implementation
begins.

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
