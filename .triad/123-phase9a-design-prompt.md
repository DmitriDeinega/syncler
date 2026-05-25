=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 123 — Phase 9a: per-device envelope encryption (DESIGN v1)

V1.5 item #4 (roadmap line 22-23). Phase 10 just closed (multi-
process plugin host). Phase 9 is the largest unshipped V1.5
item. This consult drafts the protocol design; implementation
is a downstream Phase 9b track.

Stage: design only. Not asking for a build. Asking for: spec
review, alternative-architecture flags, missed threat-model
items, migration objections.

## Background — current V1 model

`docs/crypto-spec.md §3-4`:

- User has a 32-byte `master_key`.
- Each (sender, user) pair derives one shared
  `pairing_key = HKDF(master_key, salt=sender_id, info="syncler-v1-pairing-key:" + sender_id)`.
- Sender encrypts payload with AES-256-GCM under `pairing_key`,
  random 12-byte nonce, AAD = JSON({expires_at, min_plugin_version,
  plugin_id, sender_id, user_id}).
- Server cannot decrypt (no `master_key`).
- ALL devices on the user account share the same `pairing_key`
  via the shared `master_key` (delivered via `encrypted_user_state`
  blob unlocked with the user's `master_key_wrap_key`).
- Revoking ONE device requires rotating the user-wide
  `master_key` (V1.5 §10 just shipped that flow).

## Goal — Phase 9

> "Senders encrypt the payload separately for each of the
> user's enrolled devices, keyed by per-device public keys
> instead of the shared user master key. Enables forward
> secrecy and immediate per-device revocation without
> rotating user-wide keys." — `docs/ROADMAP.md` line 23

Concretely:

1. **Per-device public encryption keys.** Each enrolled device
   registers a long-term X25519 public key alongside its
   existing Ed25519 device-bound JWT key.
2. **HPKE-sealed per-recipient envelopes.** Sender generates a
   per-message Content Encryption Key (CEK, AES-256), encrypts
   the payload ONCE under the CEK, then wraps the CEK once per
   recipient device using HPKE (RFC 9180, Base mode, suite
   X25519-HKDF-SHA256 / AES-256-GCM).
3. **Per-device revocation.** Sender simply omits the revoked
   device's envelope on the next publish. No user-wide
   rotation needed.

## Proposed wire format

### Envelope (replaces today's wire = nonce || ciphertext_with_tag)

```json
{
  "protocol_version": 2,
  "payload_ciphertext": "<base64 AES-GCM(payload, CEK, nonce, aad)>",
  "payload_nonce": "<base64 12-byte random>",
  "recipient_envelopes": [
    {
      "device_id": "<uuid>",
      "hpke_kem_output": "<base64 X25519 ephemeral pubkey, 32 bytes>",
      "hpke_ciphertext": "<base64 HPKE(CEK)>"
    },
    ...
  ]
}
```

`hpke_ciphertext` carries the wrapped 32-byte CEK plus its
AES-GCM tag (HPKE auto-handles AEAD framing).

### AAD (unchanged structure, new field)

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

`protocol_version` joins the AAD so a downgrade attack from v2
back to v1 fails AEAD verification.

### Ed25519 envelope (signature input)

```json
{
  "expires_at": "...",
  "min_plugin_version": "...",
  "payload_ciphertext": "<base64>",
  "payload_nonce": "<base64>",
  "plugin_id": "...",
  "protocol_version": 2,
  "recipient_envelopes": [
    {"device_id": "...", "hpke_kem_output": "<b64>", "hpke_ciphertext": "<b64>"},
    ...
  ],
  "sender_id": "...",
  "user_id": "..."
}
```

Signature covers EVERY recipient envelope, so a network/server
adversary can't swap, drop, or add a recipient envelope without
invalidating the Ed25519 sig.

## Server changes

1. **Schema migration:** `devices.encryption_public_key BYTEA NOT NULL`
   (32 bytes for X25519). Existing rows get a synthesized zero
   key + a `requires_rekey=true` flag; clients re-enroll on
   first launch post-upgrade.
2. **New endpoint:** `GET /v1/users/{user_id}/devices/encryption_keys`
   — authenticated as a paired sender. Returns
   `[{device_id, encryption_public_key}]` for all non-revoked
   devices. Sender caches; SSE channel emits
   `devices.encryption_key.changed` for cache busts.
3. **`POST /v1/messages` validation:** verify
   `protocol_version=2` envelopes match the sender's known
   device set. Reject envelopes for revoked devices with a
   warning. Allow missing devices (sender may legitimately not
   know about new devices yet — they get the message via the
   sender's next refresh).
4. **`GET /v1/inbox` shape:** server filters to ONLY the
   requesting device's envelope (bandwidth + privacy).
   Server-side filter is a defense-in-depth nicety — the AEAD
   would already fail for other recipients' envelopes.
5. **Nonce-replay table (§7):** unchanged. Per-message
   `payload_nonce` is shared across recipients; one replay row
   per `(sender_id, nonce)` is correct.

## Android changes

1. **Device enrollment** generates an X25519 keypair stored in
   the existing `MasterKey`-wrapped EncryptedSharedPreferences.
   The Ed25519 device-bound JWT keypair stays separate (signing
   vs encryption purposes).
2. **Inbox decrypt path** picks the matching `recipient_envelope`
   by `device_id`, HPKE-opens to recover the CEK, then
   AES-GCM-decrypts the payload using `payload_nonce` and AAD.
3. **`master_key` no longer used for payload decryption.** It
   stays for the encrypted user-state blob + V1 messages during
   the migration window.

## SDK changes (Python + TypeScript)

1. New crypto utility: `seal_per_device(payload_bytes, aad_bytes, device_keys: dict[str, bytes]) -> RecipientEnvelopes`.
2. `Client.publish(...)` fetches device keys at publish time
   (one-shot per call, cached for 60s) and feeds them into the
   sealer.
3. Test vectors in `docs/crypto-spec.md §11` (new section).

## Migration plan

- **v0.1 + dev-mode:** because everything is v0.1 we can
  break wire compat. New code ships at `protocol_version=2`
  ONLY; the V1 path is deleted in the same release. User
  master_key continues to wrap the user-state blob, NOT
  payloads.
- **Per-device enrollment** runs on first launch of the new
  client. Device generates X25519, PUTs the pubkey to the
  server, gets an OK.
- **Pairing changes (downstream):** the existing
  `derive_pairing_key(master_key, sender_id)` is dead code
  in V2; pairing is now purely an authorization relationship,
  not a key-derivation point. V1.5 §9 automated pairing
  bootstrap envelope still works (it's a different layer).

## Forward-secrecy claim

The roadmap promises "forward secrecy". HPKE Base mode uses a
fresh ephemeral X25519 sender keypair per message AND a long-
term static recipient key. That gives:

- **Past secrecy on recipient compromise**: an attacker who
  steals a device's static X25519 private key today CAN
  decrypt past messages stored on the server. So this is NOT
  full forward secrecy in the message-by-message sense.
- **Cross-recipient secrecy**: device A's compromise doesn't
  reveal messages destined for device B (each has its own
  CEK wrap).
- **No-rotation revocation**: revoking a device doesn't
  require user-wide master_key rotation.

True FS would need ratcheting (Double Ratchet / Signal) which
is well out of V1.5 scope. Recommendation: drop "forward
secrecy" from the V1.5 #4 description; replace with
"per-device confidentiality" / "rotation-free revocation".

## Open questions for the triad

1. **HPKE-Base vs HPKE-Auth.** Auth mode binds the sender's
   long-term key into the KEM, providing recipient-side sender
   authentication WITHOUT a separate Ed25519 signature. We
   already have Ed25519 sigs (§4.1) for envelope integrity. Is
   the redundancy worth it, or should we drop Ed25519 and use
   HPKE-Auth?
2. **Per-recipient AAD binding.** The `payload_ciphertext` is
   AEAD-bound to AAD which currently does NOT include
   `device_id`. An attacker who somehow recovers a CEK could
   feed it to ANY recipient envelope (since they all wrap the
   same CEK). Acceptable — HPKE wrap is already integrity-
   protected — but is there a reason to add `device_id` into
   the AAD per recipient? Would force re-encryption per
   recipient → defeats the "encrypt once" optimization.
3. **Server-side per-device filtering vs unconditional fanout.**
   Filter saves bandwidth; fanout is simpler and lets a device
   verify the full envelope structure. Which?
4. **Backward-compat window length.** Roadmap doesn't specify.
   Recommend zero (dev mode, break wire). Confirm?
5. **Should `protocol_version` go in AAD or only in the Ed25519
   envelope?** Putting it in AAD makes downgrade
   verification automatic at AEAD level. Putting it only in
   the signed envelope makes V1 envelopes still decryptable
   if a v1 client receives one. Recommend AAD.
6. **Key registration trust.** When a device PUTs its
   encryption pubkey, the server must verify the device's
   Ed25519 device-bound JWT — does the existing JWT
   middleware cover this without changes?
7. **What's missing?** Anything I haven't surfaced.

## Files to read for context

- `docs/crypto-spec.md` (current §3-4 message encryption, §7
  nonce replay, §9 automated pairing)
- `docs/ROADMAP.md` V1.5 item 4
- `server/app/models.py` (`Device` schema, `Pairing` schema)
- `server/app/routers/messages.py` (publish path)
- `server/app/routers/inbox.py` (fetch path)
- `sdk-python/syncler/client.py` (`publish(...)` flow)
- `android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/InboxRepository.kt` (decrypt path)
- `android/core/crypto/` (current crypto primitives)

## Output

Per reviewer, terse:

1. Verdict on Phase 9a design v1: GREEN / YELLOW / RED + items.
2. Architectural alternatives I missed.
3. Threat-model gaps in the proposed wire format / AAD.
4. Migration objections (zero backward-compat window).
5. Answers to the seven open questions.

Multiple iterations expected — Phase 10a took 4 design rounds
to converge. Substance over speed.

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
