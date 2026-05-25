=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 126 — Phase 9a design v4 (convergence)

Triad 125: Gemini GREEN. Codex YELLOW with three fixable items
+ nits. v4 incorporates everything.

## v4 deltas vs v3

### Block 1: delete envelope gains plugin_id (Codex 125 #1)

Current `LiveCardDeleteRequest` schema (`server/app/schemas.py:255`)
has `(sender_id, user_id, card_key, nonce, expires_at,
envelope_signature)`. No `plugin_id`. That means a captured
delete envelope for plugin A's card_key K could be replayed
against plugin B's card_key K if any sender happened to use
the same card_key across plugins. Today's `live_cards`
unique key is `(sender_id, user_id, card_key)` — no plugin
constraint.

**v4 spec change:** `plugin_id` joins the request, canonical
signature input, service lookup, and SSE payload.

```python
class LiveCardDeleteRequest(BaseModel):
    sender_id: UUID
    user_id: UUID
    plugin_id: UUID  # NEW
    card_key: str
    nonce: str
    expires_at: datetime
    envelope_signature: str  # over (sender_id, user_id, plugin_id, card_key, nonce, expires_at, envelope_kind="live_card_delete", protocol_version=2)
```

Signed delete canonical envelope:

```json
{
  "card_key": "...",
  "envelope_kind": "live_card_delete",
  "expires_at": "...",
  "nonce": "<b64>",
  "plugin_id": "...",
  "protocol_version": 2,
  "sender_id": "...",
  "user_id": "..."
}
```

`live_cards` table unchanged structurally; the service lookup
just adds `plugin_id` to the filter. Phase 12 migration didn't
add plugin scoping; Phase 9b will.

### Block 2: endpoint prefix correction (Codex 125 #2)

Actual app routing in `server/app/main.py`:

- `POST /v1/auth/devices/enroll` (existing; X25519 key joins request body)
- `PUT /v1/auth/devices/me/encryption_key` (new; current_auth_context-bound, only mutates ctx.device.id)
- `GET /v1/senders/me/devices?user_id={uuid}` (new sender-auth; returns directory + version)

### Block 3: PyCA HPKE target correction (Codex 125 #3)

Bump `cryptography>=47` in `server/pyproject.toml` and
`sdk-python/pyproject.toml`. PyCA 47 ships HPKE at
`cryptography.hazmat.primitives.hpke` with `Suite/KEM/KDF/AEAD`
classes matching RFC 9180.

Suite construction:

```python
from cryptography.hazmat.primitives.hpke import Suite, KEM, KDF, AEAD

SUITE = Suite(
    kem=KEM.DHKEM_X25519_HKDF_SHA256,
    kdf=KDF.HKDF_SHA256,
    aead=AEAD.AES_256_GCM,
)
```

Server-side seal (one call per recipient):

```python
enc_and_ct = SUITE.seal_one_shot(
    pkR=recipient_x25519_pub_bytes,  # 32 bytes
    info=hpke_info_canonical_bytes,
    aad=hpke_aad_canonical_bytes,
    plaintext=cek_bytes,              # 32 bytes
)
# PyCA returns enc || ciphertext. X25519 enc = 32 bytes.
hpke_kem_output = enc_and_ct[:32]
hpke_ciphertext = enc_and_ct[32:]    # 48 bytes (32 plaintext + 16 tag)
```

Server-side open (single recipient):

```python
cek = SUITE.open_one_shot(
    skR=device_x25519_private_key,
    enc=hpke_kem_output,             # 32 bytes
    info=hpke_info_canonical_bytes,
    aad=hpke_aad_canonical_bytes,
    ciphertext=hpke_ciphertext,      # 48 bytes
)
```

Wire stays split (`hpke_kem_output` separate from
`hpke_ciphertext`) for clearer parsing/validation; the
implementation just slices/concatenates around the PyCA API.

### Block 4: Ed25519 canonical envelope includes directory version (Codex 125 nit)

`recipient_directory_version` joins the Ed25519 signed
envelope for both event publish AND live-card upsert. A
malicious server can't strip it from the wire to mask a
stale-directory rejection.

Event publish signed envelope (v4 final):

```json
{
  "envelope_kind": "event",
  "expires_at": "...",
  "min_plugin_version": "...",
  "payload_ciphertext": "<b64>",
  "payload_nonce": "<b64>",
  "plugin_id": "...",
  "protocol_version": 2,
  "recipient_directory_version": 42,
  "recipient_envelopes": [
    {"device_id": "...", "hpke_kem_output": "<b64>", "hpke_ciphertext": "<b64>"},
    ...
  ],
  "sender_id": "...",
  "user_id": "..."
}
```

Live-card upsert adds `card_key`, `card_type`,
`sequence_number`. `recipient_envelopes` sorted by `device_id`
(lowercase UUID lexicographic).

### Block 5: explicit duplicate-device rejection (Codex 125 nit)

Server-side publish validator rejects with `400 duplicate_device_id`
if two entries in `recipient_envelopes` share the same
`device_id`. The schema validator (Pydantic) gates this before
the route handler runs. Test required.

### Block 6: HPKE output framing clarified (Codex 125 nit)

Per Block 3 above — wire split is `enc[:32] || ciphertext[32:]`
exactly. Implementation slice is deterministic per X25519's
fixed KEM output length. Spec section 11 lands a test vector
confirming the byte boundaries.

## Carry-overs from v3 (unchanged)

- Sender directory: `GET /v1/senders/me/devices?user_id={uuid}`
  with `directory_version: int` monotonic counter on `users`
  table (per Gemini 124 A + Codex 125 A: integer, not timestamp).
- Complete-fanout enforcement; `409 stale_recipient_set` with
  `current_directory_version` + `missing_device_ids`.
- HPKE Base mode (not Auth); Ed25519 envelope sig retained.
- Card-specific HPKE info adds `envelope_kind`, `card_key`,
  `card_type`, `sequence_number`.
- Full fanout (no server filtering of recipient_envelopes).
- BYTEA NULL during migration; tighten to NOT NULL later.
- Recipient cap = 32 (server constant).
- "Trusted device directory" trust model documented.
- Historical V1 live cards deleted in migration (Gemini 125 D +
  Codex 125 D).
- Re-enrolled devices can't decrypt pre-enrollment payloads
  (documented).
- Android HPKE via Tink (Gemini 125 C + Codex 125 C).
- SSE for user devices: `{type, version, user_id}`.
- No sender SSE channel; senders refresh on TTL/409.
- SDK migration: `Client.pair(...)` returns `(user_id,
  device_directory_path)` not pairing_key.

## Required tests (Phase 9b implementation)

- HPKE round-trip single-recipient with full info/aad.
- Multi-recipient publish (N=3) with sorted envelopes.
- Server rejects publish missing an active device → `409`.
- Server rejects publish with unknown/long-revoked device →
  `400 unknown_recipient`.
- Server rejects duplicate `device_id` → `400 duplicate_device_id`.
- Card upsert: `card_key` swap invalidates signature.
- Card upsert: `sequence_number` rewind invalidates signature.
- Card upsert: `card_type` mutation invalidates signature.
- Delete: replay rejected by nonce table.
- Delete: cross-plugin replay rejected by `plugin_id` in signed
  envelope.
- Directory-version mismatch: `409 stale_recipient_set`.
- HPKE wire-byte split: `enc[:32] || ciphertext[32:]` round-trips.

## Files I plan to touch in Phase 9b

### Server

- `server/alembic/versions/0011_per_device_encryption_keys.py` (new migration)
- `server/app/models.py` — `Device.encryption_public_key`, `users.device_directory_version`, `LiveCardDelete` plugin_id
- `server/app/crypto/hpke.py` (new — Suite + seal/open wrappers + canonical info/aad builders)
- `server/app/schemas.py` — new `MessageSendRequestV2`, `LiveCardUpsertRequestV2`, `LiveCardDeleteRequestV2`, `DeviceDirectoryResponse`
- `server/app/routers/messages.py` — replace publish/inbox to v2
- `server/app/routers/cards.py` — replace upsert/delete to v2
- `server/app/routers/devices.py` — enroll body adds `encryption_public_key`; new `PUT /me/encryption_key`
- `server/app/routers/senders.py` — new `GET /me/devices`
- `server/app/services/messages.py` — recipient-set validation
- `server/app/services/cards.py` — plugin-scoped lookup + recipient-set validation
- `server/app/services/devices.py` — directory_version bumps
- `server/pyproject.toml` — `cryptography>=47`
- `server/tests/test_phase9_*` (new test modules)

### SDK Python

- `sdk-python/syncler/crypto.py` — HPKE seal helper, directory client, canonical info/aad
- `sdk-python/syncler/client.py` — `publish/upsert_live_card/delete_live_card` rewrites
- `sdk-python/pyproject.toml` — `cryptography>=47`
- `sdk-python/tests/test_publish_v2.py` (new)

### SDK TypeScript

- `sdk-plugin/src/...` — plugin SDK doesn't need HPKE (plugins receive decrypted payloads from the host); spec docs updated.

### Android

- `android/core/crypto/build.gradle.kts` — add Tink dependency
- `android/core/crypto/src/main/kotlin/.../Hpke.kt` (new — seal/open wrappers, canonical info/aad)
- `android/feature/inbox/.../InboxRepository.kt` — open path: HPKE decap → AES-GCM open
- `android/feature/plugin-host/.../PluginPermissionStore.kt` or similar — X25519 keypair storage in EncryptedSharedPreferences
- `android/core/network/.../SynclerApi.kt` — enroll body adds `encryption_public_key`; new endpoint stubs

### Spec

- `docs/crypto-spec.md` — new `§11 Per-device envelope encryption (V2)` superseding §3-4. Sections include: protocol v2 wire, HPKE info/aad canonical JSON, sender directory shape, complete-fanout rule, test vectors.
- `docs/ROADMAP.md` — drop "forward secrecy" claim from V1.5 #4; replace with "per-device confidentiality + rotation-free revocation".

## Output

Per reviewer, terse:

1. Verdict on v4: GREEN / YELLOW / RED + items.
2. Anything still missing.

If dual-GREEN, this Phase 9a closes and 9b begins.

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
