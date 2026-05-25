=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 124 — Phase 9a design v2 (per-device envelope encryption)

Triad 123: Codex YELLOW with three blockers + threat-model gaps.
Gemini quota exhausted (re-consult once reset).

This is design-mode v2 incorporating Codex 123 feedback. Still
asking for review, not implementation.

## v2 deltas vs v1

### Block 1: filter/signature incompatibility → full fanout

V1 had server filtering inbox to one recipient envelope. That
broke Ed25519 signature reconstruction. **V2: full fanout.**

- `GET /v1/inbox/{message_id}` returns the entire
  `recipient_envelopes` array.
- Device verifies the Ed25519 signature against the full
  envelope, then picks its own entry by `device_id` and
  HPKE-opens.
- Bandwidth cost: ~80 bytes per envelope (32-byte KEM output
  + 48-byte HPKE wrap of a 32-byte CEK). With a recipient cap
  of 32 devices, that's ~2.5 KB extra per inbox row — trivial.
- Privacy cost: each device learns the IDs of sibling devices.
  Acceptable — they're all on the same user account.

If we ever need filtering, the Merkle-commitment approach
(sign the root over canonical recipient_envelopes; server
returns the leaf + path; device verifies path against signed
root) is the documented future path. Out of scope for V1.5 #4.

### Block 2: live cards → V2 envelope mirrors

`POST /v1/cards/upsert` and inbox live-card projection use the
same envelope shape. The change applies to both
`messages.encrypted_body` AND `live_cards.encrypted_body`. Spec
v2 covers both endpoints byte-for-byte.

Migration: cards stored under V1 pairing_key become
undecryptable on the new client. Zero backward-compat applies
to live cards too. (V0.1 dev-mode tolerates this.)

### Block 3: server key-substitution attack → explicit trust model

Codex flagged: a sender fetching device keys from the server
has no cryptographic proof they came from real enrolled
devices. A malicious server can substitute its own key and
decrypt.

**V2 trust model statement** (added to `docs/crypto-spec.md
§4.x intro`):

> The server is a **trusted device directory** for the
> per-device encryption key distribution path. This is the
> same trust level the V1 `encrypted_user_state` blob
> assumes — the server delivered the wrapped master_key to
> the client. The trust model for payload confidentiality
> against an active server adversary is NOT improved by
> Phase 9; it is preserved.
>
> Phase 9's confidentiality guarantee is against:
> - **Network observers** (TLS gives transport secrecy; HPKE
>   gives end-to-end secrecy against passive server reads)
> - **Server reads at rest** (no master_key on the server)
> - **Cross-device leakage** (a compromised device A's
>   private key cannot decrypt messages destined for B)
>
> Phase 9 does NOT defend against:
> - **Active server key substitution.** A malicious server
>   can issue its own X25519 key and gradually replace one
>   device's key in directory responses to the sender. Mitigation
>   is deferred to a future device-attestation track (existing
>   devices co-sign new devices' encryption keys, a la Signal's
>   safety numbers, OR transparency-log style key change
>   monitoring).

Documenting the limitation lets us ship V1.5 #4 with honest
threat-model claims rather than implicit promises we can't keep.

### Block 4: HPKE info / AEAD AAD binding

V1 had `hpke_ciphertext: "HPKE(CEK)"` which is loose. V2 binds
the HPKE encryption context tightly:

```text
HPKE encap call:
  enc, ctx = SealBase(
    pkR = device.encryption_public_key,
    info = info_bytes,
  )
  hpke_ciphertext = ctx.Seal(
    aad = aad_bytes,
    plaintext = CEK,
  )

info_bytes = canonical_json({
  "protocol_version": 2,
  "sender_id": "...",
  "user_id": "...",
  "plugin_id": "...",
  "expires_at": "...",
  "min_plugin_version": "...",
  "payload_nonce": "<b64>",
  "payload_ciphertext_sha256": "<hex>",
}).utf8_bytes

aad_bytes = canonical_json({
  "device_id": "<uuid>",
  "protocol_version": 2,
}).utf8_bytes
```

The `payload_ciphertext_sha256` in `info` binds the CEK wrap
to a specific payload — a server can't recombine one
recipient's wrap with a different payload. `device_id` in the
HPKE `aad` ensures one device's wrap cannot be replayed at a
different device even if the CEK is somehow recovered.

### Block 5: validation rules

- Reject duplicate `device_id` entries within
  `recipient_envelopes`.
- `hpke_kem_output` MUST be exactly 32 bytes (X25519 pubkey).
- `hpke_ciphertext` MUST be exactly 48 bytes (HPKE wrap of a
  32-byte CEK: 32 plaintext + 16-byte AEAD tag, no nonce
  because HPKE's secret-export key + sequence number derive
  it).
- `recipient_envelopes.length` MUST be ≥ 1 and ≤ 32.
- All `device_id`s in `recipient_envelopes` MUST be either
  active or in the "recently-revoked grace window" (5
  minutes) — covers in-flight publish races.
- The server-side publish handler rejects envelopes whose
  recipients include an unknown / long-revoked / duplicate
  device_id.

### Block 6: schema migration → no zero sentinel

V1 said `devices.encryption_public_key BYTEA NOT NULL` with a
synthesized zero default. Codex flagged the sentinel risk.

**V2:** column is `BYTEA NULL`. Server rejects
`POST /v1/messages` and `POST /v1/cards/upsert` if ANY device
in the user's active set has `encryption_public_key IS NULL`
(would create an undeliverable subset). New device enrollment
includes the X25519 key — column populated at creation.
Existing dev-mode devices re-enroll on first launch of the new
client (they can't decrypt anyway under the zero-compat plan).

After all active devices have registered: a follow-up
migration tightens to `NOT NULL`. Tracked as a Phase 9c chore.

### Block 7: lost-key semantics

If a device loses its X25519 private key (uninstall +
reinstall, factory reset, etc.) and re-enrolls with a fresh
key, all server-stored messages encrypted to the OLD key are
unrecoverable on this device. Acceptable behavior:

- The server is allowed to expire old messages on schedule
  (existing 30-day retention).
- The device sees the messages but cannot decrypt; UI surfaces
  "Could not decrypt — sent before this device was enrolled".
- Cross-device sync of historical messages is not in scope
  (a separate "history backfill" feature would need to
  re-encrypt at the sender, which isn't possible without the
  original payloads).

### Block 8: enrollment endpoint

V1 device enrollment uses `bootstrap_only_user` per
`server/app/routers/devices.py`. The X25519 encryption pubkey
joins the device-create request body:

```http
POST /v1/devices
{
  "public_key": "<b64 Ed25519 device-bound JWT key>",
  "encryption_public_key": "<b64 X25519>",
  "fcm_token": "..."
}
```

The existing `bootstrap_only_user` auth check stands. The
X25519 key is stored in the new column.

**Rotation:** a separate
`PUT /v1/devices/me/encryption_key` endpoint uses
`current_auth_context` (device-bound JWT), can ONLY mutate
`ctx.device.id` (not arbitrary IDs). For Phase 9 v1, rotation
is in-scope but lightly used.

## Updated wire format (full)

### Publish (POST /v1/messages and POST /v1/cards/upsert)

```json
{
  "protocol_version": 2,
  "sender_id": "...",
  "user_id": "...",
  "plugin_id": "...",
  "expires_at": "...",
  "min_plugin_version": "...",
  "payload_nonce": "<b64 12-byte>",
  "payload_ciphertext": "<b64 AES-GCM(payload, CEK, payload_nonce, payload_aad)>",
  "recipient_envelopes": [
    {
      "device_id": "<uuid>",
      "hpke_kem_output": "<b64 32-byte>",
      "hpke_ciphertext": "<b64 48-byte>"
    },
    ...
  ],
  "envelope_signature": "<b64 Ed25519>"
}
```

### Payload AAD (used inside AES-GCM)

```json
{
  "expires_at": "...",
  "min_plugin_version": "...",
  "plugin_id": "...",
  "protocol_version": 2,
  "sender_id": "...",
  "user_id": "..."
}
```

### Ed25519 envelope (signature input — canonical, sorted, UTF-8)

```json
{
  "expires_at": "...",
  "min_plugin_version": "...",
  "payload_ciphertext": "<b64>",
  "payload_nonce": "<b64>",
  "plugin_id": "...",
  "protocol_version": 2,
  "recipient_envelopes": [...],
  "sender_id": "...",
  "user_id": "..."
}
```

Envelope canonicalization: nested `recipient_envelopes` array
is sorted by `device_id` (lexicographic) before signing so
both sides agree on byte sequence.

## Test vectors (deferred to spec §11)

Phase 9b implementation lands one fixture vector per:

- HPKE seal/open for a single (device, CEK) round-trip
- Full envelope publish with N=1 and N=3 recipients
- Signature verification with sorted recipients

## Open questions (carry-overs + new)

A. **Sender's recipient-set knowledge.** The sender's local
   `Client` instance needs the user's current device set.
   Today senders cache `pairing_key`. V2: they cache device
   list + per-device encryption pubkeys, refreshed via SSE
   `devices.changed` or polling. Confirm refresh cadence
   (every publish? every 60s? on SSE event only?).
B. **Recipient cap = 32.** Arbitrary. Same as V1's typical max
   devices/user? Or should it be higher (mobile + tablet +
   laptop = 3 devices/user typical, but power users have more)?
C. **Live-card delete propagation.** Cards have a `delete`
   path. Does the delete need an envelope, or is the
   server-side soft-delete sufficient given the card was
   previously authenticated? (Likely sufficient — delete is
   just a tombstone — but worth a sentence in the spec.)
D. **SSE event shape.** When a new device enrolls, existing
   senders need to learn. SSE already carries `inbox.changed`
   etc. — propose `devices.changed` event with no payload
   (forces senders to re-fetch the device list). Confirm
   shape.

## Output

Per reviewer, terse:

1. Verdict on v2: GREEN / YELLOW / RED + items.
2. Anything still missing.
3. Answers to the 4 open questions.

If still YELLOW, v3 lands next. If GREEN, implementation begins.

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
