=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 125 — Phase 9a design v3 (per-device envelope encryption)

Triad 124: Gemini GREEN. Codex YELLOW with four blockers + nits.
v3 incorporates all Codex feedback.

## v3 deltas vs v2

### Block 1: sender device-directory API (Codex 124 #1)

Senders cannot use the user-facing `/v1/devices` route nor the
user SSE stream. New sender-auth endpoint:

```http
GET /v1/senders/me/devices?user_id={uuid}
Authorization: Bearer <sender_jwt>

200 OK
{
  "directory_version": 42,
  "user_id": "<uuid>",
  "devices": [
    {
      "device_id": "<uuid>",
      "encryption_public_key": "<b64 X25519, 32 bytes>",
      "updated_at": "<ISO8601>"
    },
    ...
  ]
}
```

- Gated by active pairing: 403 if no active `Pairing(sender_id,
  user_id)` exists.
- `directory_version` is a per-user monotonic counter bumped
  on any device enrollment, revocation, or
  `encryption_public_key` rotation. Computed server-side from
  `max(device.updated_at)` or a dedicated `users.device_directory_version`
  column.
- Returned in publish responses too (so the sender always
  knows the latest version it has seen).

### Block 2: complete-fanout enforcement (Codex 124 #2)

Server REJECTS publish if `recipient_envelopes` omits any
active device with a non-null `encryption_public_key`. Error:

```http
409 Conflict
X-Stale-Directory-Version: 42
{
  "error": "stale_recipient_set",
  "message": "active devices missing from recipient_envelopes",
  "current_directory_version": 42,
  "missing_device_ids": ["<uuid>", ...]
}
```

Sender retry pattern: catch `409 stale_recipient_set`, refetch
directory, re-encrypt + re-publish ONCE. Repeated 409 indicates
a race (new enrollment between retry attempts) — sender escalates
to caller.

Recently-revoked devices (within 5-minute grace window) MAY
appear as extras in `recipient_envelopes`; server tolerates
but doesn't require.

### Block 3: live-card metadata binding (Codex 124 #3)

The card-specific signature/AAD/HPKE info MUST include
`card_key`, `sequence_number`, and `card_type` (the
`PluginManifest.card_type` value). Without these, a server
could swap one live card's payload for another or rewind
sequence numbers without invalidating the signature.

**Card-specific HPKE info:**

```json
{
  "protocol_version": 2,
  "envelope_kind": "live_card_upsert",
  "sender_id": "...",
  "user_id": "...",
  "plugin_id": "...",
  "card_key": "...",
  "card_type": "live",
  "sequence_number": 17,
  "expires_at": "...",
  "min_plugin_version": "...",
  "payload_nonce": "<b64>",
  "payload_ciphertext_sha256": "<hex>"
}
```

**Card-specific payload AAD:**

```json
{
  "card_key": "...",
  "card_type": "live",
  "envelope_kind": "live_card_upsert",
  "expires_at": "...",
  "min_plugin_version": "...",
  "plugin_id": "...",
  "protocol_version": 2,
  "sender_id": "...",
  "sequence_number": 17,
  "user_id": "..."
}
```

**Card-specific signed envelope (Ed25519 input):**

Same as the payload AAD but PLUS `payload_ciphertext`,
`payload_nonce`, and `recipient_envelopes` (sorted by
`device_id`).

The `envelope_kind` field ("event" | "live_card_upsert" |
"live_card_delete") differentiates the three publish paths
in the canonical signing input. Without it, a captured
event-envelope and a captured card-upsert with the same
payload bytes would have identical signed forms.

### Block 4: signed delete envelope (Codex 124 #4)

V1's `LiveCardDeleteRequest` already carries `(sender_id,
user_id, card_key, nonce, expires_at, envelope_signature)` —
shipped in Phase 12 from Codex 95. V2 preserves this exactly,
plus adds `envelope_kind: "live_card_delete"` and
`protocol_version: 2` to the canonical signed envelope so a
delete's signature can't be cross-replayed as an upsert.

```json
{
  "card_key": "...",
  "envelope_kind": "live_card_delete",
  "expires_at": "...",
  "nonce": "<b64>",
  "protocol_version": 2,
  "sender_id": "...",
  "user_id": "..."
}
```

No HPKE recipient_envelopes needed (delete carries no
encrypted content). Existing nonce-replay table covers replay.

### Block 5: HPKE suite + canonical JSON profile (Codex 124 nit)

**HPKE suite:** RFC 9180 `DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM`:

- KEM id: `0x0020` (DHKEM(X25519, HKDF-SHA256))
- KDF id: `0x0001` (HKDF-SHA256)
- AEAD id: `0x0002` (AES-256-GCM)

Library targets:

- **Server (Python):** `cryptography>=41` ships HPKE. PyCA's
  `hazmat.primitives.kem.hpke` covers all three IDs.
- **SDK Python:** same.
- **Android (Kotlin):** Tink's `HpkeKemKeyFactory` and friends
  (already on classpath via androidx if we add Tink; otherwise
  BouncyCastle 1.80 has HPKE primitives we can call directly).

**Canonical JSON profile:**

- UTF-8 encoding.
- Sorted keys (lexicographic on Unicode code points).
- Separators: `(",", ":")` (no whitespace).
- `ensure_ascii=true` (compatible with the existing
  `assemble_envelope` helper in `server/app/crypto/aead.py`).
- Numbers: integers as integers, never as floats; reject NaN /
  Infinity / -0.0.
- UUIDs serialized as their RFC 4122 lowercase canonical form.

### Block 6: directory freshness in publish (Codex 124 nit)

Publish request body includes:

```json
{
  "protocol_version": 2,
  "recipient_directory_version": 42,
  ...
}
```

Server compares against current — if smaller, rejects with
`409 stale_recipient_set` + `current_directory_version`.
Avoids the publish-then-recheck race.

### Block 7: endpoint name fix (Codex 124 nit)

V2 prompt said `POST /v1/devices`; the actual existing route
is `POST /v1/devices/enroll` (`server/app/routers/devices.py`).
v3 uses the correct name. The X25519 encryption pubkey joins
the existing enroll body. The rotation endpoint is
`PUT /v1/devices/me/encryption_key` (new, current_auth_context).

### Block 8: SDK migration (Codex 124 nit)

Sender SDK changes:

- `Client.pair(...)` output now returns `(user_id,
  device_directory_path)` not `(user_id, pairing_key)`.
- `Client.publish(...)`:
  1. Cache check on directory (60s TTL).
  2. Build CEK + AES-GCM payload.
  3. Build HPKE wraps for each device in the directory.
  4. Build Ed25519 envelope including `recipient_directory_version`.
  5. POST. On `409`: refetch, rebuild, retry once.

- The legacy `pairing_key` field on `Client` is removed in V0.1
  (per zero-backward-compat decision).

### Block 9: required tests (Codex 124 nit)

For the implementation phase (Phase 9b), test vectors required:

- Round-trip single-recipient HPKE seal/open with the suite +
  bound info/aad.
- Multi-recipient publish (N=3) with sorted `recipient_envelopes`.
- Server rejects publish missing an active device → `409`.
- Server rejects publish with extra non-revoked device →
  `400` (unknown_recipient).
- Card upsert: `card_key` swap invalidates signature.
- Card upsert: `sequence_number` rewind invalidates signature.
- Delete: replay rejected by nonce table.
- Delete: cross-pluginid replay rejected by `plugin_id` in
  signed envelope (already present V1, doc to verify).
- Directory-version mismatch: `409 stale_recipient_set`.

## SSE event shape (Gemini 124 + Codex 124 D)

For users:
```json
{ "type": "devices.changed", "version": 42, "user_id": "<uuid>" }
```

For senders: no SSE channel. They refresh on directory cache
TTL expiry or on `409 stale_recipient_set` rejection.

(Adding a sender SSE channel is V2 work — out of scope for
Phase 9.)

## Carry-overs (unchanged from v2)

- HPKE Base mode (not Auth); Ed25519 envelope sig retained.
- Full fanout (no server filtering of recipient_envelopes).
- BYTEA NULL during migration; no zero sentinel.
- Re-enrolled devices can't decrypt pre-enrollment payloads;
  documented as expected.
- Recipient cap = 32 (named server constant).
- "Trusted device directory" trust model: server can
  substitute X25519 keys; deferred to a future attestation
  track.

## Updated wire format (full, both endpoints)

### Event publish (POST /v1/messages/send)

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

### Live-card upsert (POST /v1/cards/upsert)

Same as event plus `card_key`, `card_type`, `sequence_number`.
`envelope_kind` = `"live_card_upsert"`.

### Live-card delete (POST /v1/cards/delete)

Unchanged from V1 (Phase 12) plus `protocol_version: 2` and
`envelope_kind: "live_card_delete"` in the signed envelope.

## Open questions

A. **Directory version representation.** Int counter on the
   `users` table vs `max(devices.updated_at)` timestamp. Int is
   simpler + monotonic across server restarts; timestamp risks
   clock-skew. Recommend int.
B. **`encryption_public_key` rotation cadence.** No protocol
   enforcement of rotation interval. Sender re-fetches
   directory; server bumps version. Devices can rotate as often
   as the UX allows (probably never automatically; user-
   triggered on a "I've been compromised" flow).
C. **HPKE library choice on Android.** Tink (clean API,
   battle-tested) vs BouncyCastle (already on classpath, less
   ergonomic). Recommend adding Tink for HPKE specifically.
D. **Backfill of historical cards.** Existing live cards in
   the dev database are V1-encrypted. Should the migration
   delete them or leave them visible-but-undecryptable?
   Recommend delete in V0.1 since "everything is V0.1".

## Output

Per reviewer, terse:

1. Verdict on v3: GREEN / YELLOW / RED + items.
2. Anything still missing.
3. Answers to the 4 open questions.

If still YELLOW, v4 lands next. If GREEN, Phase 9b implementation
begins with: migration → server endpoints → SDK seal → Android
open.

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
