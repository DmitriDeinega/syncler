# Syncler Crypto Protocol V1

This document is the canonical V1 crypto contract for the server, Android app, and SDKs.

## 1. Key Hierarchy

The user password is processed client-side with Argon2id.

Argon2 params version 1:

```text
m_cost = 19_456 KiB
time_cost = 2
parallelism = 1
hash_len = 64 bytes
```

The 64-byte Argon2id output is split without further transformation:

```text
auth_key = output[0:32]
master_key_wrap_key = output[32:64]
```

The server stores and compares an auth-key hash using constant-time byte equality. The server does not derive the password output.

## 2. Master Key

At signup, the client generates a 32-byte master key with a CSPRNG.

The client encrypts the master key with `master_key_wrap_key` using AES-256-GCM. The encrypted master key is stored on the server as an opaque blob. The server does not decrypt it and does not have `master_key_wrap_key`.

## 3. Pairing Key Derivation

Pairing keys are derived client-side with HKDF-SHA256:

```text
ikm = master_key
salt = sender_id
info = "syncler-v1-pairing-key:" + sender_id
length = 32
```

`sender_id` is the exact byte string used by the protocol identity layer. Implementations must not normalize or reinterpret it before HKDF.

## 4. Message Encryption

Messages use AES-256-GCM with a 12-byte random nonce per message.

Wire format:

```text
wire = nonce || ciphertext_with_tag
nonce = wire[0:12]
ciphertext_with_tag = wire[12:]
```

AAD (additional authenticated data passed to AES-GCM) contains the **protocol context** that binds the ciphertext but does NOT include the ciphertext itself. It is JSON-encoded UTF-8 with sorted keys and compact separators (no whitespace):

```json
{
  "expires_at": "<ISO8601 UTC>",
  "min_plugin_version": "...",
  "plugin_id": "...",
  "sender_id": "...",
  "user_id": "..."
}
```

Missing AAD fields are errors. `min_plugin_version` is an empty string when unset (never null). `expires_at` is required and must be a future timestamp ≤ 30 days from the signing instant.

### 4.1 Envelope (Ed25519 signing input)

The sender ALSO signs an envelope with Ed25519. The envelope is AAD plus the ciphertext and nonce so a server / network adversary cannot swap one ciphertext for another while leaving the AAD intact. Canonical envelope JSON (UTF-8, sorted keys, compact separators):

```json
{
  "sender_id": "...",
  "user_id": "...",
  "plugin_id": "...",
  "encrypted_body": "<base64 ciphertext_with_tag>",
  "nonce": "<base64 12-byte nonce>",
  "min_plugin_version": "...",
  "expires_at": "<ISO8601 UTC>"
}
```

> **V1 contract change (M5.1):** previous draft of this spec required `message_id`, `created_at`, and `schema_version` in AAD — those fields are server-generated and unknowable to the sender at signing time, so they have been removed from both AAD and the envelope. They remain server metadata stored next to the message but are not signed by the sender. AAD and the envelope are intentionally distinct: AAD authenticates context to AES-GCM (no ciphertext); the envelope is what Ed25519 signs (AAD + ciphertext + nonce). Replay protection is enforced at the server via the per-sender nonce registry (see §7).

## 5. Plugin Bundle Signing

Plugin bundles use Ed25519 signatures.

Signing input:

```text
canonical_manifest_without_signature || bundleHash
```

Canonical manifest rules:

```text
Remove the "signature" field.
Encode JSON with sorted keys.
Use separators=(",", ":").
Use ensure_ascii=True.
Encode the JSON as UTF-8 bytes.
Append bundleHash decoded from hex to raw bytes.
```

The `bundleHash` field remains present in the canonical JSON, then its raw bytes are appended.

## 6. Test Vectors

These vectors are asserted by `server/tests/test_crypto.py`.

### Argon2id

```text
password = 73796e636c65722d746573742d70617373776f7264
salt = 00112233445566778899aabbccddeeff
params = m_cost=19456,time_cost=2,parallelism=1,hash_len=64,type=Argon2id

argon2_hash = e23ed7b136661e69f2424d8440777943827d9981e2fb409d69e48bce72dd7f82c8327524d69330d1993ba67e26a3576718d29f0602e44a881d924ca36836699c
auth_key = e23ed7b136661e69f2424d8440777943827d9981e2fb409d69e48bce72dd7f82
master_key_wrap_key = c8327524d69330d1993ba67e26a3576718d29f0602e44a881d924ca36836699c
```

### HKDF Pairing Key

```text
master_key = 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
sender_id = 73656e6465722d616c706861
info = 73796e636c65722d76312d70616972696e672d6b65793a73656e6465722d616c706861

pairing_key = f6ed649481dd8a5ffc57401b816803fba79556731c5c9ff53be49f7862f8cb8e
```

### Message AEAD (V1.1 — 5-field AAD)

```text
aad_json = {"expires_at":"2026-05-20T00:00:00Z","min_plugin_version":"1.0.0","plugin_id":"plugin.weather","sender_id":"sender-alpha","user_id":"user-123"}
key = f6ed649481dd8a5ffc57401b816803fba79556731c5c9ff53be49f7862f8cb8e
nonce = 101112131415161718191a1b
plaintext = 7b2274656d70657261747572655f63223a32317d
```

Ciphertext is verified via round-trip in both `server/tests/test_crypto.py` and `android/core/crypto/.../SpecVectorsTest.kt` rather than via a fixed hex assertion — the ciphertext depends on AAD bytes, and the V1.1 AAD shape is the contract going forward. Old AAD bytes (with `message_id`/`created_at`/`schema_version`) are obsolete.

### Message Envelope (Ed25519 sender signature input)

```text
envelope_json = {"encrypted_body":"<base64 ciphertext_with_tag>","expires_at":"2026-05-20T00:00:00Z","min_plugin_version":"1.0.0","nonce":"<base64 nonce>","plugin_id":"plugin.weather","sender_id":"sender-alpha","user_id":"user-123"}
```

The sender signs the canonical envelope JSON with Ed25519. The server reconstructs the envelope from the request fields and verifies against the registered sender's public key. Envelope is also what the recipient device uses to verify the signature on inbox fetch — therefore inbox/detail responses include `expires_at` (in addition to the body, nonce, signature).

### Plugin Bundle Signature

```text
manifest_json = {"bundleHash":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08","name":"Weather Plugin","pluginId":"plugin.weather","version":"1.0.0"}
bundleHash = 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
canonical_manifest_for_signing = 7b2262756e646c6548617368223a2239663836643038313838346337643635396132666561613063353561643031356133626634663162326230623832326364313564366331356230663030613038222c226e616d65223a225765617468657220506c7567696e222c22706c7567696e4964223a22706c7567696e2e77656174686572222c2276657273696f6e223a22312e302e30227d9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08

ed25519_private_seed = 1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100
ed25519_public_key = 712651f450ba05b63898b99ef5f7ba45632e8e2527f7f715cd671ec4024cc51e
ed25519_signature = 3d3a4963d6390f4392b36dac13938cadf015da019c6d0b2004e701656f544f6b336bb9da81ef4fde0b392f3ac33884c7dbb40dcd6f0ac30f1bbc06a464e68a06
```

### Python Vector Script

The vectors above were generated with this Python shape:

```python
from argon2.low_level import Type, hash_secret_raw
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

argon2_hash = hash_secret_raw(
    secret=b"syncler-test-password",
    salt=bytes.fromhex("00112233445566778899aabbccddeeff"),
    time_cost=2,
    memory_cost=19456,
    parallelism=1,
    hash_len=64,
    type=Type.ID,
)

master_key = bytes.fromhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
sender_id = b"sender-alpha"
pairing_key = HKDF(
    algorithm=hashes.SHA256(),
    length=32,
    salt=sender_id,
    info=b"syncler-v1-pairing-key:" + sender_id,
).derive(master_key)

nonce = bytes.fromhex("101112131415161718191a1b")
aad = b'{"created_at":"2026-05-20T00:00:00Z","message_id":"msg-001","min_plugin_version":1,"plugin_id":"plugin.weather","schema_version":1,"sender_id":"sender-alpha","user_id":"user-123"}'
plaintext = b'{"temperature_c":21}'
ciphertext_with_tag = AESGCM(pairing_key).encrypt(nonce, plaintext, aad)

private_key = Ed25519PrivateKey.from_private_bytes(
    bytes.fromhex("1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100")
)
public_key = private_key.public_key().public_bytes(
    encoding=serialization.Encoding.Raw,
    format=serialization.PublicFormat.Raw,
)
```

## 7. Nonce Reuse Policy

Never reuse an AES-GCM nonce with the same key. Clients must generate 12-byte nonces with a CSPRNG.

The V1.5 server enforces replay protection via a durable per-sender registry backed by the `nonce_replay` Postgres table (composite PK on `(sender_id, nonce)`, atomic INSERT ON CONFLICT DO NOTHING). The previous V1 in-memory LRU registry was process-local and lost state on worker restart; the durable registry survives restarts and synchronizes across multiple uvicorn workers. Rows are pruned by `app/jobs/retention.py` after 30 days (matching the upper bound on accepted envelope lifetime — envelopes older than that already fail the `expires_at` check at the service layer, so the registry can safely forget them).

The same registry is shared by `POST /v1/messages/send`, `POST /v1/cards/upsert`, and `POST /v1/cards/delete`. Cards-upsert was scoped in even though sequence-number CAS already mitigates most replay scenarios — defense-in-depth against resurrecting deleted cards with an old sequence. **Phase 12 added freshness + replay protection to `POST /v1/cards/delete`** (closing Codex consultation 95): the delete envelope now binds `nonce` (12 random bytes) and `expires_at` (≤ 48 h in the future, same cap as upsert). The server checks the envelope is unexpired, then records the nonce in the shared registry (409 on replay). The route explicitly commits even when the underlying card was already gone — without that commit the nonce row would roll back on session-close and a replay could land against a future card with the same `(sender_id, user_id, card_key)`.

## 8. Live Cards (Phase 3b)

Live cards are persistent, upsertable units identified by `(sender_id, user_id, card_key)`. The encrypted payload uses the same AES-256-GCM primitive as messages, but with a richer AAD that binds the card-specific metadata. Upsert and delete each have their own Ed25519 envelope distinct from the message envelope.

### 8.1 Live Card AAD

Canonical JSON, UTF-8, sorted keys, compact separators (same encoder as §4):

```json
{
  "card_key": "...",
  "card_type": "live",
  "expires_at": "<ISO8601 UTC with Z suffix>",
  "plugin_id": "...",
  "sender_id": "...",
  "sequence_number": <integer JSON literal>,
  "user_id": "..."
}
```

`card_type` is always the string `"live"` (the field exists so the AAD shape can extend cleanly when future card types land — e.g. ephemeral or scheduled cards). `sequence_number` is emitted as a JSON integer literal (no quotes); senders MUST NOT stringify it. `expires_at` MUST use the `Z` UTC suffix (not `+00:00`) so SDK and server agree on the canonical bytes.

The AAD binds the ciphertext to a specific (sender, user, plugin, card, sequence, expiry) tuple. A replay of an older sequence or a substitution of one card's ciphertext under another's metadata will fail AES-GCM tag verification on decrypt.

### 8.2 Live Card Upsert Envelope (Ed25519 signing input)

Canonical JSON for the sender's `envelope_signature` on `POST /v1/cards/upsert`:

```json
{
  "card_key": "...",
  "card_type": "live",
  "encrypted_payload": "<base64 ciphertext_with_tag, no nonce prefix>",
  "expires_at": "<ISO8601 UTC with Z suffix>",
  "nonce": "<base64 12-byte nonce>",
  "plugin_id": "...",
  "sender_id": "...",
  "sequence_number": <integer JSON literal>,
  "user_id": "..."
}
```

The server constructs the same canonical bytes from the request body (via `assemble_envelope` in `server/app/routers/cards.py`) and verifies against the registered sender's public key. UUIDs MUST be canonicalized to lowercase no-brace form before signing (`str(uuid.UUID(value))`) — the SDK normalizes via `_canon_uuid` to prevent sender-side self-401s on uppercase or braced input.

### 8.3 Live Card Delete Envelope (Ed25519 signing input)

Canonical JSON for `POST /v1/cards/delete`:

```json
{
  "card_key": "...",
  "expires_at": "<ISO 8601 UTC ≤ 48 h ahead>",
  "nonce": "<base64 12 random bytes>",
  "sender_id": "...",
  "user_id": "..."
}
```

**`user_id` is REQUIRED in the envelope.** Without it, a delete signature valid for one user's card could be replayed against another user's card with a coincidentally matching `(sender_id, card_key)` — the table is uniquely keyed on `(sender_id, user_id, card_key)`, so the lookup would otherwise be ambiguous. (Codex consultation 62 security finding.) The server matches the exact triple before deleting; mismatched triples no-op silently and still publish a `card.delete` SSE event so all of the user's devices clear any stale local copy.

**Phase 12 — `nonce` + `expires_at` are REQUIRED.** Without them a captured delete envelope replays indefinitely against any future card under the same `(sender_id, user_id, card_key)`. The server (a) rejects envelopes whose `expires_at` is in the past or exceeds the 48 h live-card TTL cap, and (b) records `nonce` in the shared `nonce_replay` registry — same registry used by `messages/send` and `cards/upsert`. The delete route explicitly commits even when the card was already absent so the nonce row persists across a replay attempt. (Codex consultation 95 finding.)

### 8.4 Test Vectors

Live-card vectors live alongside the rest in `server/tests/test_crypto.py` (round-trip) and `android/core/crypto/.../SpecVectorsTest.kt` (Android cross-check). The canonical envelope bytes for upsert/delete are asserted in `server/tests/test_phase3.py` via signature-verification round trips against `app/routers/cards.py:_build_upsert_envelope_bytes` and `_build_delete_envelope_bytes`.

## Python Reference Snippets

```python
import json
import uuid
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

def derive_pairing_key(master_key: bytes, sender_id: bytes) -> bytes:
    return HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=sender_id,
        info=b"syncler-v1-pairing-key:" + sender_id,
    ).derive(master_key)

def assemble_aad(fields: dict[str, object]) -> bytes:
    return json.dumps(fields, ensure_ascii=True, separators=(",", ":"), sort_keys=True).encode("utf-8")

def decrypt(pairing_key: bytes, wire: bytes, aad: bytes) -> bytes:
    nonce = wire[:12]
    ciphertext_with_tag = wire[12:]
    return AESGCM(pairing_key).decrypt(nonce, ciphertext_with_tag, aad)

# --- Live cards (§8) -----------------------------------------------------

def _canon_uuid(value) -> str:
    """Normalize a UUID to the lowercase no-brace form the server stores
    via str(uuid.UUID(payload.*)). Required before signing: uppercase or
    braced input from a caller would otherwise produce a canonical-bytes
    mismatch and a self-401 from the server."""
    return str(uuid.UUID(str(value)))

def assemble_live_card_aad(
    *,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    card_key: str,
    sequence_number: int,
    expires_at,  # datetime; coerced to ISO8601 with Z suffix
) -> bytes:
    return json.dumps(
        {
            "card_key": card_key,
            "card_type": "live",
            "expires_at": expires_at.isoformat().replace("+00:00", "Z"),
            "plugin_id": _canon_uuid(plugin_id),
            "sender_id": _canon_uuid(sender_id),
            "user_id": _canon_uuid(user_id),
            "sequence_number": sequence_number,
        },
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")

def assemble_live_card_upsert_envelope(
    *,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    card_key: str,
    encrypted_payload_b64: str,
    nonce_b64: str,
    sequence_number: int,
    expires_at,
) -> bytes:
    return json.dumps(
        {
            "card_key": card_key,
            "card_type": "live",
            "encrypted_payload": encrypted_payload_b64,
            "expires_at": expires_at.isoformat().replace("+00:00", "Z"),
            "nonce": nonce_b64,
            "plugin_id": _canon_uuid(plugin_id),
            "sender_id": _canon_uuid(sender_id),
            "sequence_number": sequence_number,
            "user_id": _canon_uuid(user_id),
        },
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")

def assemble_live_card_delete_envelope(
    *,
    sender_id: str,
    user_id: str,
    card_key: str,
    nonce: str,           # base64 of 12 random bytes
    expires_at: str,      # ISO 8601, e.g. "2026-05-25T12:00:00Z"
) -> bytes:
    return json.dumps(
        {
            "card_key": card_key,
            "expires_at": expires_at,
            "nonce": nonce,
            "sender_id": _canon_uuid(sender_id),
            "user_id": _canon_uuid(user_id),
        },
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
```

## 9. Bootstrap Protocol (V1.5)

Automated pairing (V1.5 DX) replaces manual `user_id`/`pairing_key` entry with an encrypted POST from the device to a sender-operated broker. Trust is established by the sender signing its X25519 bootstrap key with its long-term Ed25519 signing key.

### 9.1 Sender Bootstrap Key Registration

The sender generates a static X25519 keypair. It signs the raw 32-byte public key with its Ed25519 signing key to bind the bootstrap identity to the sender identity.

Signing input:
```text
"syncler-v1-bootstrap-key:" (24 bytes ASCII) || bootstrap_pub_x25519 (32 bytes raw)
```

The signature and X25519 public key are registered with the Syncler server. The server stores a hash `bootstrap_key_id = SHA-256(bootstrap_key)[:16]` for stable identification.

### 9.2 Bootstrap Encryption (HPKE-style)

The device fetches the sender's bootstrap key and signature from the server and verifies the signature BEFORE proceeding.

1. Device generates an ephemeral X25519 keypair `(eph_priv, eph_pub)`.
2. `shared_secret = X25519(eph_priv, sender.bootstrap_key)`.
3. `aead_key = HKDF-SHA256(salt=eph_pub || sender.bootstrap_key, ikm=shared_secret, info="syncler-v1-bootstrap-aead", length=32)`.
4. `nonce = 12 random bytes`.
5. `aad = JSON canonical bytes (sorted keys, compact separators, UTF-8)`.
6. `ciphertext_with_tag = AES-256-GCM(aead_key, nonce, plaintext, aad)`.

### 9.3 Bootstrap AAD

Canonical JSON binds the envelope to the specific pairing and broker.

```json
{
  "bootstrap_key_id": "<base64 16 bytes>",
  "exp": "<ISO8601 UTC with Z suffix>",
  "pairing_id": "<uuid>",
  "protocol_version": 1,
  "sender_broker_url": "<string>",
  "sender_id": "<uuid>"
}
```

`sender_broker_url` is named that way (not just `broker_url`) because `PairingInitiateResponse.broker_url` already exists in the V1 wire contract and means "the Syncler-side broker URL the QR encodes" — a different concept. `sender_broker_url` is the URL the sender's backend operates for the encrypted bootstrap POST. Renaming avoids silent semantic overload.

**Security Rule:** The broker MUST NOT reconstruct `sender_broker_url` from the envelope. It MUST use the `sender_broker_url` stored in its own pairing state (indexed by `pairing_id`) created when the sender called `pairing/initiate`.

#### Single fixed `sender_broker_url` and the pending-pairing registry

The reference broker implementation shipped under the optional `syncler[broker]` extra (`sdk-python/syncler/broker/app.py`) uses a **single fixed `sender_broker_url`** configured at app startup, not a per-`pairing_id` map. This satisfies the "AAD-binding" half of the security rule above — the trusted state IS the configured URL, byte-equal to what the sender signed at `pairing/initiate`. Per-`pairing_id` URLs are deferred to V2 (rotating broker URLs are not currently a use case).

The "reject unknown `pairing_id`" half of the rule is enforced via the **pending-pairing registry** built into [BrokerStorage] as of Phase 6. `Client.create_pairing_qr(sender_broker_url=...)` calls `storage.reserve(pairing_id)` when the Syncler server hands back the freshly-issued ID; the broker handler calls `storage.is_reserved(pairing_id)` BEFORE attempting decrypt and returns HTTP 404 (opaque) for unknown IDs. `complete()` ALSO enforces the same invariant as defense-in-depth (raises `UnknownPairingIdError`, which the handler maps to the same 404).

The mandatory `rate_limiter` hook on `make_app(...)` is still strongly recommended in production to defend against decrypt-spam DOS targeting a known-reserved `pairing_id`. The pending-pairing registry guards the entry point; the rate limiter guards CPU once an attacker knows a real ID.

For multi-process production deployments, `InMemoryBrokerStorage` is insufficient because `reserve()` from the Client process won't be visible to `is_reserved()` in a separate broker process. Use a shared backing store (Redis, Postgres) that implements the [BrokerStorage] Protocol atomically.

### 9.4 Test Vectors

These vectors are asserted by `server/tests/test_crypto.py`.

#### Ed25519 Bootstrap Key Signature

```text
ed25519_seed: 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
ed25519_pub: 03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8
bootstrap_pub_raw: 358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254
sig_input_hex: 73796e636c65722d76312d626f6f7473747261702d6b65793a358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254
signature: 714def847ce5343f9b06f9263a57e192975709a73a92ae290b8b0eee47770c184eb3c5492d5a8adaed3b459c5614294ea9ddcd64e7b697af2e7b61142f3ac608
```

#### HPKE Key Derivation

```text
eph_seed: 404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f
eph_pub: 79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a
bootstrap_pub: 358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254
shared_secret: 04c304fb1ca83cee75e206344231f33797e07d9929db670994b7c6fbeb1dc255
salt: 79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254
aead_key: 09817b8833c85ff7c9b16b4c867e5dc801c3b57a4f56ee453265a9160f4d9b31
```

#### Bootstrap AAD and AEAD Round-trip

```text
aad_json: {"bootstrap_key_id":"oCiYEAMutBcnTuvEo45omQ==","exp":"2026-05-24T12:00:00Z","pairing_id":"00000000-1111-2222-3333-444444444444","protocol_version":1,"sender_broker_url":"https://broker.example.com/api/v1","sender_id":"55555555-6666-7777-8888-999999999999"}
nonce: a0a1a2a3a4a5a6a7a8a9aaab
plaintext: {"pairing_key":"8PHy8/T19vf4+fr7/P3+/wARIjNEVWZ3iJmqu8zd7v8=","user_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}
ciphertext_with_tag: e4a7378b1739a2c6bf053a09689bf54c97c44f268455ac7ec413844fcfe313757d2c9ebdbc1ba979998aa3880d68db65bd4263de3bf65f9f541a1009b6fcd5ee327979e0431eee1be93ecf2c12442946514cf4e5e351ef9ee996ed721367bcc1cff20fb71dd2701ee8daad6a9e7276f381ecd54c2bd928e836c28fe6e6dd68
```

## 10. Master-Key Rotation (V1.5)

The user's 32-byte master key (§2) is the root secret for all per-user symmetric crypto: it AES-GCM-encrypts the synced user-state blob and every per-pairing opaque blob. Rotation replaces the master key while preserving access to all historical state.

### 10.1 Rotation modes

| `reason` | Master key | Wrap key | Auth salt | Data re-encrypted | `key_generation` bump | Sessions revoked | Pairings revoked |
|---|---|---|---|---|---|---|---|
| `password_rewrap` | unchanged | NEW (from new password) | NEW (16 random bytes) | NO | NO | NO | NO |
| `root_hygiene_rotation` | NEW (32 random bytes) | unchanged | unchanged | YES | YES (+1) | NO | NO |
| `root_compromise_rotation` | NEW | NEW (from new password) | NEW | YES | YES (+1) | YES — all sessions including the initiating one | YES — every active pairing (§10.8 step 12) |

For `password_rewrap` the master key value stays byte-identical; only its wrapping changes. For `root_compromise_rotation` the initiating session is revoked along with all others — the user must log in again from scratch on every device — AND every active pairing is server-side revoked. The pairing keys themselves remain in the (now revoked) encrypted_user_state blob and an attacker holding the old master key still has plaintext copies, but the next message they sign with any of those keys gets a 410 from the server, and the legitimate user must re-pair every sender from scratch with fresh key material. Phase 13 (Codex consultation 98 follow-up) added the auto-revoke.

### 10.2 What rotation does NOT protect against

Master-key rotation re-encrypts data stored under the old key. It does NOT revoke:

- **Sender-held pairing key BYTES.** Senders received stable pairing keys at bootstrap and they remain in the encrypted_user_state blob; rotation does not regenerate those byte values. For `root_compromise_rotation` the server auto-revokes every active pairing row (§10.8 step 12) so a sender using a compromised pairing key now gets 410 on the next send and must re-pair from scratch with fresh material. For `root_hygiene_rotation` and `password_rewrap` pairings remain active (the threat model didn't include sender-channel compromise).
- Messages already delivered to other devices.
- Data exfiltrated from an unlocked device.
- Cards encrypted under per-sender pairing keys (the per-pairing keys are unchanged through rotation; cards remain decryptable).

The Android UX MUST display a "backup-or-lose-access" warning (spec MUST, not just product nicety) before submitting any rotation that changes the password — a forgotten new password makes encrypted account data unrecoverable.

### 10.3 Schema

```sql
ALTER TABLE users
    ADD COLUMN key_generation INTEGER NOT NULL DEFAULT 1;

ALTER TABLE encrypted_user_state
    ADD COLUMN key_generation INTEGER NOT NULL DEFAULT 1;
    -- state_version pre-existing from M7 CAS state.

ALTER TABLE pairings
    ADD COLUMN state_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE pairings
    ADD COLUMN key_generation INTEGER NOT NULL DEFAULT 1;

CREATE TABLE rotation_challenges (
    challenge BYTEA PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_rotation_challenges_expiry ON rotation_challenges (expires_at);

CREATE TABLE master_key_rotation_audit (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason TEXT NOT NULL CHECK (reason IN
        ('password_rewrap','root_hygiene_rotation','root_compromise_rotation')),
    old_generation INTEGER NOT NULL,
    new_generation INTEGER NOT NULL,
    initiating_session_id UUID,
    initiating_device_id UUID,
    ip TEXT,
    user_agent TEXT,
    paired_count INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_mkr_audit_user_time ON master_key_rotation_audit (user_id, occurred_at DESC);
```

`rotation_challenges` is the durable store for single-use challenge nonces issued by `POST /v1/account/rotate-master-key/challenge`. Production deployments MAY replace this table with Redis-with-TTL provided the store supports atomic consume (e.g. `GETDEL`) — the V1.5 implementation uses Postgres because the rest of the durable state already lives there. A periodic cleanup pass (`app/jobs/retention.py`) deletes expired challenges.

The audit row is INSERTed inside the same transaction as the rotation (before COMMIT). If the rotation rolls back, the audit row rolls back with it. No secret material in the log.

### 10.4 CAS counter semantics (LOCKSTEP CONTRACT)

This is the rule blobs depend on for AAD verification:

1. The client encrypts a blob using `state_version_observed + 1` (the version the row will have *after* the server's write) in the AAD.
2. The server, on a successful CAS write, increments `state_version` by EXACTLY ONE.
3. On decrypt the AAD `state_version` and the row's `state_version` match byte-for-byte.

If a server uses any other increment policy (timestamp, opaque ID, "next available integer above N"), decryption will fail with an AEAD tag error and the blob is permanently unreadable. Implementations MUST integrity-test this lockstep with the test vectors in §10.13.

The same rule applies to `key_generation`:

1. For `root_*` rotations, the client encrypts every blob it writes during rotation with `new_generation = old + 1` in the AAD.
2. The server sets `users.key_generation = new_generation` and every blob row's `key_generation` to the same value.
3. For `password_rewrap`, `key_generation` is unchanged and appears identically in every AAD.

### 10.5 `key_generation_observed` is required on EVERY state-mutating endpoint

Every endpoint that writes a `key_generation`-tagged blob MUST require the client to pass `key_generation_observed` in the request, and MUST 409 with `current_key_generation` in the response if it doesn't match `users.key_generation`. This prevents the post-rotation race where a pre-rotation client still holds the old master key in memory and tries to push state encrypted under it after the rotation committed.

**MUST.** Every endpoint that mutates a `key_generation`-tagged blob serializes against rotation by taking `SELECT users.* FROM users WHERE id = :user_id FOR UPDATE` as its first DB operation, before checking `key_generation_observed` or applying any CAS write. Endpoints in scope:

- `POST /v1/account/rotate-master-key`
- `PUT /v1/state` (encrypted_user_state CAS push)
- `PUT /v1/pairings/{pairing_id}/state` (per-pairing state CAS)
- `POST /v1/pairing/complete` (creates a pairing row)
- `POST /v1/pairing/{pairing_id}/revoke` (revokes a pairing)
- Any future endpoint writing or removing a `key_generation`-tagged row.

The user-row lock + per-blob CAS together prevent both lost-update races (concurrent pushes to the same blob) AND generation-mixing races (a pre-rotation client's push landing after the rotation commit).

The 409 response shape is:

```json
{
  "error": "key_generation_mismatch",
  "current_key_generation": <int>,
  "client_action": "refetch_master_key_and_state"
}
```

### 10.6 Wire format

#### `POST /v1/account/rotate-master-key/challenge`

```http
POST /v1/account/rotate-master-key/challenge
Authorization: Bearer <session_token>
```

Response (200):

```json
{
  "rotation_challenge": "<base64 32 random bytes>",
  "expires_at": "<ISO 8601 UTC, ~5min from now>"
}
```

The challenge is single-use and bound to `(user_id, session_id)` in the `rotation_challenges` table. Failed proof attempts do NOT consume the challenge — that would let a hijacker invalidate a legitimate user's challenge by burning it with bad guesses. The challenge is consumed (DELETEd) only when the rotation transaction succeeds OR when it expires.

#### `POST /v1/account/rotate-master-key`

```http
POST /v1/account/rotate-master-key
Content-Type: application/json
Authorization: Bearer <session_token>

{
  "reason": "root_hygiene_rotation" | "root_compromise_rotation" | "password_rewrap",
  "key_generation_observed": <int>,
  "rotation_challenge": "<base64 32 bytes — from /challenge>",
  "current_password_proof": "<base64 32 bytes — see §10.7>",

  // Required for `password_rewrap` and `root_compromise_rotation`,
  // forbidden for `root_hygiene_rotation`:
  "new_auth_salt": "<base64 16 bytes>",
  "new_auth_key_proof": "<base64 32 bytes>",

  // Always required:
  "new_encrypted_master_key": "<base64 AES-GCM blob>",

  // Forbidden for `password_rewrap`; required for `root_*`:
  "new_encrypted_user_state": {
    "encrypted_blob": "<base64>",
    "state_version_observed": <int>
  },
  "pairings": [
    {
      "pairing_id": "<uuid>",
      "state_version_observed": <int>,
      "new_encrypted_state": "<base64>"
    }
  ]
}
```

`pairings[]` MUST list EVERY pairing currently associated with the user (for `root_*`). A `password_rewrap` MUST omit `new_encrypted_user_state` and `pairings`.

### 10.7 Authentication proof

`current_password_proof` is the Argon2id-derived `auth_key` (first 32 bytes of the 64-byte derivation) for the user's CURRENT password. The server verifies it by computing `SHA-256(current_password_proof)` and constant-time-comparing the result against `users.auth_key_hash`.

`new_auth_key_proof` (only present for `password_rewrap` and `root_compromise_rotation`) is the same construction for the NEW password. On successful rotation, the server stores `SHA-256(new_auth_key_proof)` as the new `users.auth_key_hash`.

**Security boundary.** `current_password_proof` is a **bearer-credential under TLS** — anyone who captures these 32 bytes can authenticate as the user for any endpoint that takes them. The protocol does NOT bind the proof bytes to the challenge, so a captured `auth_key` can in principle be replayed with a different challenge in a future rotation request. We considered binding the proof via HMAC-SHA256(auth_key, challenge) but rejected it for V1.5: the server only stores SHA-256(auth_key), not the raw key, so it cannot verify an HMAC without weakening the at-rest storage to a password-equivalent. (A proper password-authenticated key exchange like OPAQUE is the V2+ solution.)

The actual defenses are layered:

1. **TLS transport.** The proof bytes never appear unencrypted on the wire. The server MUST NOT log them anywhere — not in request logs, not in audit rows, not in error responses.
2. **Single-use rotation challenge.** Each rotation request consumes a fresh server-issued challenge. A captured POST body can't be replayed verbatim — its challenge is already gone.
3. **Rate limits.** A captured proof can drive at most 3 successful rotations per user per 24-hour window (§10.8 step 5). Repeated FAILED proof attempts (wrong auth_key) are rate-limited separately at 10 per user per hour to prevent brute-force.
4. **Mode-specific session revocation.** `root_compromise_rotation` revokes ALL sessions, including the one that issued the rotation. A hijacker who rotated loses access at the same moment the legitimate user does.
5. **Re-type at rotation time.** The client UI MUST prompt for the current password fresh each rotation — no cached `wrap_key`. This narrows the attack window to the moment of active rotation.

### 10.8 Server-side processing

Inside ONE Postgres transaction:

1. **Validate request shape** (reject unknown fields, verify required combinations per `reason`).
2. **Lock the user row** — `SELECT users.* FROM users WHERE id = :user_id FOR UPDATE`. Per the §10.5 MUST clause, every state-mutating endpoint takes this lock first; rotation is the same. Read `users.auth_key_hash`, `users.key_generation`, `users.auth_salt` from the locked row.
3. **Consume the rotation challenge** — `SELECT challenge FROM rotation_challenges WHERE challenge = :c AND user_id = :u AND session_id = :s AND expires_at > NOW() FOR UPDATE`. 401 if missing.
4. **Verify `current_password_proof`** — compute SHA-256, constant-time-compare against the locked `users.auth_key_hash`.
   - On mismatch: increment a per-user failed-proof counter in a SEPARATE database transaction (separate connection / `BEGIN`-`COMMIT` block) so the increment persists even after this transaction rolls back. The counter is keyed on `user_id` with a 1-hour window (10 attempts → 429 `Retry-After`).
   - Then raise 401. The main transaction rolls back — the challenge is NOT consumed (consumption is only on successful rotation; step 13).
5. **Rate-limit successful rotations** — `SELECT COUNT(*) FROM master_key_rotation_audit WHERE user_id = :u AND occurred_at > NOW() - INTERVAL '24 hours'`. 429 `Retry-After` if at 3.
6. **Verify `key_generation_observed`** — `key_generation_observed == users.key_generation` (already loaded from the locked row in step 2). 409 if mismatch.
7. **For `root_*`: verify the exact active pairing ID set inside the user-row lock.** No concurrent pairing-create can happen per §10.5 MUST. Read the canonical active set:

    ```sql
    SELECT pairing_id FROM pairings
    WHERE user_id = :u AND revoked_at IS NULL
    ```

    First verify `len(raw_request_pairing_ids) == len(deduped_request_pairing_ids)`; otherwise respond `409 pairing_set_changed` (duplicate request ID). Then compare the deduped request set to the canonical active set; if the two sets differ (extra ID, missing ID), respond `409 pairing_set_changed`. UUID secrecy is not the authorization boundary; explicit set equality is.

8. **CAS each pairing in request** — for each:

    ```sql
    UPDATE pairings
    SET encrypted_state = :new,
        state_version = :observed + 1,
        key_generation = :new_generation
    WHERE pairing_id = :pid
      AND user_id = :u
      AND revoked_at IS NULL
      AND state_version = :observed
    RETURNING state_version
    ```

    Affected-rows = 0 → 409 `pairing_state_changed` (include the list of mismatched pairing_ids in the response with their current `state_version`). The `user_id` and `revoked_at` predicates are defense-in-depth — step 7 already verified the set, but the predicates prevent rotating a stale or wrong-owner pairing if the implementation diverges.

    The `SELECT ... FOR UPDATE` then update-in-code form is also acceptable — but the SELECT must carry the same `user_id` + `revoked_at` filters.

9. **CAS `encrypted_user_state`** — for `root_*`:

    ```sql
    UPDATE encrypted_user_state
    SET encrypted_blob = :new,
        state_version = :observed + 1,
        key_generation = :new_generation
    WHERE user_id = :u AND state_version = :observed
    ```

    Affected-rows = 0 → 409 `state_version_mismatch` (response body carries `current_state_version`). `password_rewrap` skips this step entirely.

10. **Apply user-row writes** — `users.encrypted_master_key = :new_blob`. For `password_rewrap` + `root_compromise_rotation`: `users.auth_key_hash = SHA-256(new_auth_key_proof)` and `users.auth_salt = :new_salt`. For `root_*`: `users.key_generation = old + 1`.
11. **INSERT audit row** — `master_key_rotation_audit` capturing `(user_id, reason, old_generation, new_generation, initiating_session_id, initiating_device_id, ip, user_agent, paired_count)`. NO secret material.
12. **For `root_compromise_rotation`: revoke sessions AND pairings** —

    ```sql
    UPDATE devices  SET revoked_at = NOW()
      WHERE user_id = :u AND revoked_at IS NULL;
    UPDATE pairings SET revoked_at = NOW()
      WHERE user_id = :u AND revoked_at IS NULL;
    ```

    Devices: every authenticated call from any of them now 401s.

    Pairings: the attacker who stole the old master key also has every sender's pairing key bytes (they live in `encrypted_user_state`). Re-encrypting the blob under the new MK does NOT change those bytes; the attacker can keep sending messages until each pairing is server-side revoked. After revoke the existing `PairingMissingError` path in the send routes returns 410 and the legitimate sender must re-pair with fresh material. Phase 13 closure of the Codex 98 follow-up.
13. **Consume the challenge** — `DELETE FROM rotation_challenges WHERE challenge = :c`.
14. **COMMIT.**

If any of steps 1-12 raises after some DB writes have happened, the surrounding `async with db.begin()` / `get_db()` lifecycle rolls everything back. The challenge is never consumed unless the rotation succeeds (intentional: a hijacker who triggers a failure shouldn't burn the legitimate user's challenge).

#### Failed-proof counter implementation note

The failed-proof counter is a row in `rate_limit_events` keyed on `(actor_type='user', actor_id=user_id, route='rotate_proof_fail')` with the existing 1-hour window logic. The increment uses a SEPARATE `async with session_factory() as fail_db:` (not the request's `db`) and commits independently. This is the only operation in the entire rotation flow that escapes the main transaction.

#### Response

```json
{
  "key_generation": <new>,
  "encrypted_user_state": {
    "state_version": <new>,
    "key_generation": <new>
  },
  "pairings": [
    {"pairing_id": "<uuid>", "state_version": <new>, "key_generation": <new>}
  ]
}
```

For `root_compromise_rotation` the response is the last authenticated call this session can make; the next request returns 401 and the client must log in again.

### 10.9 AAD shapes

All AAD bytes use canonical JSON: `sort_keys=True`, `ensure_ascii=True`, `separators=(",", ":")`. UUIDs use `str(uuid.UUID(v))` (lowercase no-brace).

#### `encrypted_user_state.encrypted_blob` AAD:

```json
{"key_generation": <int>, "state_version": <int>, "user_id": "<uuid>"}
```

`state_version` is the POST-write value (§10.4 lockstep).

#### `pairings.encrypted_state` AAD:

```json
{"key_generation": <int>, "pairing_id": "<uuid>", "state_version": <int>, "user_id": "<uuid>"}
```

#### `users.encrypted_master_key` AAD:

```json
{"auth_salt_b64": "<base64 of current auth_salt>", "user_id": "<uuid>"}
```

NOT bound to `key_generation` because the wrapped MK is the chicken-and-egg root — it has to decrypt BEFORE the client knows the current `key_generation`. The downgrade defense for the wrap-MK lives at the response layer (§10.10).

### 10.10 Downgrade defense

Every device persists `highest_key_generation_seen` in local unencrypted storage. Every response carrying a `key_generation`-tagged value is checked:

```text
if response.key_generation < highest_key_generation_seen:
    hard_fail("server returned downgraded key_generation; possible attack")
```

This includes the wrap-MK fetch (the `/login` and `/account` endpoints MUST return `key_generation` alongside the wrapped master-key blob; client checks BEFORE attempting to unwrap and use the MK). Without this check, a server could silently serve a stale wrapped MK + stale state, and the client's AEAD decrypts would all succeed (everything matches under the old generation) but the client would be operating on a frozen snapshot.

### 10.11 Mixed-client behavior

The server detects client capability via the `X-Syncler-Client-Min-Phase` header, set to the integer `8` by all Phase-8-aware apps.

- Pre-Phase-8 client (header missing or < 8) hits a `key_generation`-tagged endpoint while `users.key_generation > 1`: server responds **426 Upgrade Required**:

    ```json
    {"error": "account_upgraded_requires_newer_client",
     "minimum_supported_phase": 8}
    ```

- Pre-Phase-8 client + `users.key_generation == 1`: server serves normally. The user has not rotated yet so the legacy app can still decrypt the existing blobs.

### 10.12 Client-side flow

#### 10.12.1 Initiating device

1. User invokes "Rotate master key" (or "Change password") from Settings.
2. App displays the backup-or-lose-access warning (§10.2 MUST).
3. App prompts for current password. Derive Argon2id locally to recover `current_wrap_key` and compute `current_password_proof = current_auth_key`.
4. For password change: prompt for new password, derive new Argon2id outputs.
5. For `root_*`: generate new master key with CSPRNG (32 bytes).
6. POST `/rotate-master-key/challenge` → receive `rotation_challenge`.
7. Fetch latest server state (`GET /v1/state` and each pairing state). Record `state_version_observed` per row.
8. Decrypt each blob with the current master key.
9. Re-encrypt each blob with the NEW master key (or unchanged MK for `password_rewrap`), using `state_version_observed + 1` in AAD (§10.4 lockstep).
10. Re-wrap the new (or unchanged) master key with the new wrap key.
11. POST `/rotate-master-key` with the full payload.
12. On 200: update local state with new MK and `key_generation`. Persist new high-water mark in local unencrypted storage.
13. On 409: refetch affected blob(s), restart from step 7.
14. For `root_compromise_rotation`: app is signed out; show "logged out everywhere" UX, return to login screen.

#### 10.12.2 Other device coming online

1. Periodic sync polls `GET /v1/state`. Response carries `key_generation`.
2. If server's `key_generation > local high-water mark`: show "Account encryption key rotated — please re-enter your password" banner.
3. User enters password. Device fetches the new wrapped MK + `key_generation` from `/account` or `/login`.
4. Verify `response.key_generation >= local high-water mark` (downgrade defense).
5. Derive new wrap_key (Argon2id with whatever `auth_salt` the server returned — may have rotated), unwrap MK, persist `key_generation` as new high-water mark.
6. Refetch all state blobs (now encrypted under the new MK).

#### 10.12.3 Offline merge

If the device made local changes while offline that haven't been pushed, the device's CAS push 409s with `key_generation_mismatch` (§10.5). The device:

1. Re-runs the login flow to fetch the new wrapped MK.
2. Discards its old encrypted local blob.
3. Decrypts the new server blob with the new MK.
4. Replays the local pending changes on top of the new decrypted state.
5. Re-encrypts with the new MK + new lockstep counters, pushes via fresh CAS.

### 10.13 Test vectors

Argon2id parameters per §1 (`m_cost=19456 KiB`, `time_cost=2`, `parallelism=1`, `hash_len=64`). All hex unless noted. Computed against `cryptography==45.0.3`.

```text
# Common
user_id = "11111111-1111-1111-1111-111111111111"

# === FIXTURE A: password_rewrap (master key UNCHANGED) ===
old_password_utf8       = "correct horse battery staple"
old_auth_salt           = 0102030405060708090a0b0c0d0e0f10
old_argon2id_out        = 15f27b3c958c09691754a9aed801aedb15cd20fb6361905638bc9801af42f44d
                          0b5e6f49ca002a0eaced34380987398c594436db90784532f5ca1cb64802556a
old_auth_key            = 15f27b3c958c09691754a9aed801aedb15cd20fb6361905638bc9801af42f44d
old_wrap_key            = 0b5e6f49ca002a0eaced34380987398c594436db90784532f5ca1cb64802556a
old_master_key          = 1111111111111111111111111111111111111111111111111111111111111111
old_wrap_nonce          = 000000000000000000000001
old_wrap_aad_json       = {"auth_salt_b64":"AQIDBAUGBwgJCgsMDQ4PEA==","user_id":"11111111-1111-1111-1111-111111111111"}
old_wrapped_blob        = b3f1778aa90dd4786013a858335f50101b050e08e01c3cf22306fb1912d18b89
                          6baa0c890e08ffdf913b8c697a692e8a

new_password_utf8       = "Tr0ub4dor & 3"
new_auth_salt           = 202122232425262728292a2b2c2d2e2f
new_argon2id_out        = f5f96c7e046f94b91eb6f96ed2c03dcaa6825564005f5cacf553b907a0dd4020
                          a2d01046547d4ba279bc730cf69efd8136a40737b8a4722b4d9e72a649d18cdc
new_auth_key            = f5f96c7e046f94b91eb6f96ed2c03dcaa6825564005f5cacf553b907a0dd4020
new_wrap_key            = a2d01046547d4ba279bc730cf69efd8136a40737b8a4722b4d9e72a649d18cdc
new_wrap_nonce          = 000000000000000000000002
new_wrap_aad_json       = {"auth_salt_b64":"ICEiIyQlJicoKSorLC0uLw==","user_id":"11111111-1111-1111-1111-111111111111"}
new_wrapped_blob        = bc1a4f30af480c2a5b473e0b4528525526ce371345b793f9469b323325677c6c
                          4ebaa404cdb0d082cda4b02db392035a
                          # NB: master key bytes IDENTICAL to old; only wrap-key + nonce + AAD change

# === FIXTURE B: root_hygiene_rotation (new MK, same password) ===
new_master_key          = 2222222222222222222222222222222222222222222222222222222222222222
hygiene_wrap_nonce      = 000000000000000000000003
hygiene_wrap_aad_json   = {"auth_salt_b64":"AQIDBAUGBwgJCgsMDQ4PEA==","user_id":"11111111-1111-1111-1111-111111111111"}
hygiene_wrapped_blob    = a3bf182652acb78d43d4a02557b11e7a5991bcc541f0f8d12864f1e6fb530100
                          fd7ef39e83e56037ef66f2690e1e3304

# user-state lockstep (root_hygiene_rotation from gen=1 → gen=2)
state_json              = {"installed_plugins":[],"muted_senders":[]}
old_us_aad_json         = {"key_generation":1,"state_version":5,"user_id":"11111111-1111-1111-1111-111111111111"}
old_us_nonce            = 000000000000000000000010
old_encrypted_us        = 4a6ddc3b7e7279d706334c8f6b889c5aecd45073d58df75fcc639d88377bf48c
                          92eb68b54949ac21ee9721436ae2904e82423410389b4319a91da1

new_us_aad_json         = {"key_generation":2,"state_version":6,"user_id":"11111111-1111-1111-1111-111111111111"}
new_us_nonce            = 000000000000000000000011
new_encrypted_us        = 74f1dcf51df2d37634383f79a5f987256a13a5eafa6e01470b3350c0c1fa3abd
                          cc5140f2f3161239bc048481e487a5a43997d07d7a7a34cf98ba68
```

Phase 8b implementations MUST produce byte-identical `old_wrapped_blob`, `new_wrapped_blob`, `hygiene_wrapped_blob`, `old_encrypted_us`, and `new_encrypted_us` given the fixture inputs. Argon2id derivations are checked against the embedded bytes; AES-GCM encryptions against the embedded ciphertexts.

## Equivalents for Android/Kotlin

Use platform or vetted library implementations only:

```text
AES-GCM: javax.crypto.Cipher or androidx.security.crypto
Argon2id: BouncyCastle or another maintained Argon2id implementation
Ed25519: BouncyCastle
HKDF-SHA256: BouncyCastle or cryptography library of choice
CSPRNG: java.security.SecureRandom
Canonical JSON: deterministic JSON encoder configured for sorted keys and compact separators
```

## 11. Per-Device Envelope Encryption (V2, Phase 9)

V2 protocol replaces the §3 per-(sender, user) `pairing_key` symmetric scheme with per-device HPKE-sealed envelopes. Every device on a user account holds its own X25519 keypair; senders fetch the recipient directory and seal a per-message Content Encryption Key (CEK) to each enrolled device.

V2 is wire-incompatible with V1. Clients that speak V2 reject messages without `protocol_version == 2`. The `master_key` continues to wrap the user-state blob (§10) but no longer derives any payload encryption material.

### 11.1 Key Hierarchy

Per device:

```text
device_signing_keypair    = existing Ed25519 device-bound JWT key (§ device auth)
device_encryption_keypair = NEW X25519 long-term keypair
```

The X25519 private key is stored in the device's existing `MasterKey`-wrapped EncryptedSharedPreferences (Android) alongside the Ed25519 signing key. The public key is registered with the server at enrollment time.

### 11.2 HPKE Suite

```text
suite = (KEM=DHKEM(X25519, HKDF-SHA256),
         KDF=HKDF-SHA256,
         AEAD=AES-256-GCM)
KEM id  = 0x0020
KDF id  = 0x0001
AEAD id = 0x0002
```

**Library targets:**

- **Server + SDK Python:** `cryptography>=47.0.0` — `from cryptography.hazmat.primitives.hpke import Suite, KEM, KDF, AEAD`.
- **Android (Kotlin):** Google Tink — `com.google.crypto.tink:tink-android`.

PyCA `Suite.encrypt(plaintext, public_key, info=...)` returns the concatenation `enc || ciphertext`. For X25519 the KEM output `enc` is 32 bytes; the ciphertext is `len(plaintext) + 16` (AEAD tag). For a 32-byte CEK that is 48 bytes ciphertext, 80 bytes total.

The wire format splits `enc` and `ciphertext` into separate base64 fields (`hpke_kem_output` + `hpke_ciphertext`) for schema-level validation cleanliness. Implementations re-concatenate them before calling `Suite.decrypt`. The 32/48/80 byte split is derived from the suite constants; implementations MUST NOT hardcode the numbers outside KEM-specific tests — a future suite swap fails closed.

PyCA's single-shot HPKE only accepts `info`; it does not expose RFC 9180's separate `aad`. To match across platforms, Phase 9 uses `aad = empty` and folds ALL per-recipient authenticated context into `info`. Tink callers pass `aad = byte[0]` and the same canonical `info` bytes.

### 11.3 Per-Recipient HPKE Info (canonical JSON)

For each `recipient_envelope[i]` the sender builds a per-recipient `info` blob, sorted-keys canonical JSON, UTF-8, compact separators `(",", ":")`:

```json
{
  "card_key": "...",            (live_card_upsert only)
  "card_type": "...",           (live_card_upsert only)
  "device_id": "<uuid>",
  "envelope_kind": "event" | "live_card_upsert",
  "expires_at": "<ISO8601 UTC>",
  "min_plugin_version": "...",
  "payload_ciphertext_sha256": "<lowercase hex>",
  "payload_nonce": "<base64 12-byte>",
  "plugin_id": "<uuid>",
  "protocol_version": 2,
  "sender_id": "<uuid>",
  "sequence_number": <int>,     (live_card_upsert only)
  "user_id": "<uuid>"
}
```

Live-card upsert info adds `card_key`, `card_type`, `sequence_number`. The presence/absence of these keys is the canonical signal of the envelope kind — implementations MUST omit keys (not emit `null`) so kind A's info cannot collide with kind B's at the byte level.

`payload_ciphertext_sha256` binds the CEK wrap to one payload: an attacker can't take recipient D's HPKE wrap and combine it with a different `payload_ciphertext` because HPKE seal failure-closed verifies info.

`device_id` makes each recipient's info unique; one device's wrap cannot be replayed at another device even if the CEK is somehow recovered.

### 11.4 Wire Format — Event Publish

`POST /v1/messages/send` body:

```json
{
  "protocol_version": 2,
  "envelope_kind": "event",
  "sender_id": "<uuid>",
  "user_id": "<uuid>",
  "plugin_id": "<uuid>",
  "expires_at": "<ISO8601 UTC>",
  "min_plugin_version": "...",
  "payload_nonce": "<base64 12-byte>",
  "payload_ciphertext": "<base64 AES-256-GCM(payload, CEK, payload_nonce, payload_aad)>",
  "recipient_envelopes": [
    {
      "device_id": "<uuid>",
      "hpke_kem_output": "<base64 32-byte>",
      "hpke_ciphertext": "<base64 48-byte>"
    },
    ...
  ],
  "recipient_directory_version": <int>,
  "envelope_signature": "<base64 Ed25519 sig>"
}
```

### 11.5 Wire Format — Live-Card Upsert

`POST /v1/cards/upsert` body: same as event publish PLUS `card_key`, `card_type`, `sequence_number`. `envelope_kind = "live_card_upsert"`. `card_type` is the manifest's declared type ("standard_card", etc.).

### 11.6 Wire Format — Live-Card Delete

`POST /v1/cards/delete` body:

```json
{
  "protocol_version": 2,
  "envelope_kind": "live_card_delete",
  "sender_id": "<uuid>",
  "user_id": "<uuid>",
  "plugin_id": "<uuid>",
  "card_key": "...",
  "nonce": "<base64 12-byte>",
  "expires_at": "<ISO8601 UTC>",
  "envelope_signature": "<base64 Ed25519 sig>"
}
```

Delete has no recipient_envelopes (no encrypted content). `plugin_id` is new in V2 — it prevents a captured delete envelope from replaying against a different plugin's card with the same `card_key`.

### 11.7 Payload AAD (AES-GCM)

```json
{
  "card_key": "...",            (live_card_upsert only)
  "card_type": "...",           (live_card_upsert only)
  "envelope_kind": "event" | "live_card_upsert",
  "expires_at": "<ISO8601 UTC>",
  "min_plugin_version": "...",
  "plugin_id": "<uuid>",
  "protocol_version": 2,
  "sender_id": "<uuid>",
  "sequence_number": <int>,     (live_card_upsert only)
  "user_id": "<uuid>"
}
```

Used as `aad` in `AES-256-GCM.encrypt(CEK, payload_nonce, payload, aad)`. The payload AAD is shared across all recipients (every device decrypts the same `payload_ciphertext`); per-recipient binding lives in the HPKE `info`.

### 11.8 Ed25519 Signed Envelope

Sorted-keys canonical JSON, UTF-8, compact separators. Includes EVERY recipient envelope (sorted by `device_id` lowercase UUID lexicographic):

```json
{
  "card_key": "...",                          (live_card_upsert / delete)
  "card_type": "...",                         (live_card_upsert)
  "envelope_kind": "...",
  "expires_at": "<ISO8601>",
  "min_plugin_version": "...",                (event / live_card_upsert)
  "nonce": "<base64>",                        (live_card_delete; named differently from payload_nonce)
  "payload_ciphertext": "<base64>",           (event / live_card_upsert)
  "payload_nonce": "<base64>",                (event / live_card_upsert)
  "plugin_id": "<uuid>",
  "protocol_version": 2,
  "recipient_directory_version": <int>,       (event / live_card_upsert)
  "recipient_envelopes": [                    (event / live_card_upsert)
    {
      "device_id": "<uuid>",
      "hpke_ciphertext": "<base64>",
      "hpke_kem_output": "<base64>"
    },
    ...
  ],
  "sender_id": "<uuid>",
  "sequence_number": <int>,                   (live_card_upsert)
  "user_id": "<uuid>"
}
```

**Verify order (Codex 127 implementation guardrail):** server / device MUST validate the Ed25519 signature BEFORE consulting any envelope field for routing, storage, or HPKE info reconstruction. The unsigned wire bytes are untrusted until the signature passes. Implementations that read `sender_id` from the envelope to look up the verification key MUST re-verify the signature with the *resolved* sender's known public key, never trust an inline pubkey.

### 11.9 Sender Device Directory

`POST /v1/senders/me/devices` (signed request body, gated by active `Pairing(sender_id, user_id)`):

```json
{
  "sender_id": "<uuid>",
  "user_id": "<uuid>",
  "request_signature": "<base64 Ed25519 sig>"
}
```

Signature is over the canonical JSON of `{"endpoint_kind": "directory_fetch", "sender_id": "<uuid>", "user_id": "<uuid>"}` (sorted keys, UTF-8, compact separators).

Response 200 OK:

```json
{
  "directory_version": <int>,
  "user_id": "<uuid>",
  "devices": [
    {
      "device_id": "<uuid>",
      "encryption_public_key": "<base64 32-byte X25519>",
      "updated_at": "<ISO8601 UTC>"
    },
    ...
  ]
}
```

`directory_version` is a per-user monotonic integer (`users.device_directory_version`), bumped transactionally on any device enrollment, revocation, or `encryption_public_key` rotation. Senders include the last-seen version in `recipient_directory_version` on every publish.

**Auth model rationale.** Earlier drafts of this section called for sender JWT auth. This codebase doesn't currently have sender JWTs — senders sign each request body with Ed25519 (see §4.1, /v1/pairing/initiate). The directory endpoint reuses that pattern for consistency. Sender JWTs are a V2 optimization; until then a fresh signed POST per directory refresh is the cost. Replay protection (a nonce) is omitted: the directory is identical to what an attacker would see by replaying, so there's no security benefit.

**Consistency assumption:** the directory_version read and the recipient set check on `POST /v1/messages/send` MUST happen in the same DB transaction (strongly consistent). Phase 9 ships single-Postgres only; deferred to V2 / future replication track.

### 11.10 Recipient Set Validation

Server applies these checks in order, returning the first matching error:

| Check | Status | Code |
|---|---|---|
| Duplicate `device_id` within `recipient_envelopes` | 400 | `duplicate_device_id` |
| `device_id` not in user's `active_devices ∪ recently_revoked` | 400 | `unknown_recipient` |
| `recipient_directory_version > server.users.device_directory_version` | 400 | `invalid_directory_version` |
| `recipient_directory_version < server_version` AND active device missing | 409 | `stale_recipient_set` |
| `active_devices - recipient_envelopes` non-empty | 409 | `stale_recipient_set` |
| recently-revoked device extras (`revoked_at > now() - 5m`) | 200 | (logged at INFO) |
| All active devices covered exactly | 200 | (success) |

Sets are computed as:

```sql
active_devices = devices WHERE user_id = $1
                          AND revoked_at IS NULL
                          AND encryption_public_key IS NOT NULL
recently_revoked = devices WHERE user_id = $1
                            AND revoked_at > NOW() - INTERVAL '5 minutes'
```

`409 stale_recipient_set` responses include `X-Stale-Directory-Version: <int>` header and JSON body:

```json
{
  "error": "stale_recipient_set",
  "message": "...",
  "current_directory_version": <int>,
  "missing_device_ids": ["<uuid>", ...]
}
```

Sender retry pattern: catch `409`, refetch directory, re-encrypt + re-publish ONCE. Repeated `409` indicates a live race (new enrollment between attempts) — sender escalates to caller.

Recipient cap: `recipient_envelopes.length <= 32`. Server constant `RECIPIENT_CAP_PER_MESSAGE`.

### 11.11 Trust Model

The server is a **trusted device directory** for Phase 9. This is the same trust level the V1 `encrypted_user_state` blob assumed — the server delivers the wrapped master_key to the client. Phase 9 PRESERVES the V1 trust boundary; it does not improve it against an active server adversary.

**Confidentiality guarantees Phase 9 DOES provide:**

- Network observers can't read payloads (transport TLS + end-to-end HPKE).
- Server reads at rest can't decrypt payloads (no master_key, no per-device private keys on the server).
- Compromise of device A's X25519 private key reveals only device A's wraps, not device B's.
- Per-device revocation: removing a device from `recipient_envelopes` is sufficient; no user-wide master_key rotation needed.

**Confidentiality guarantees Phase 9 does NOT provide:**

- **Active server key substitution.** A malicious server can serve sender-fetched directory responses with its OWN X25519 keys and gradually replace one device's key, then decrypt that device's future inbox. Mitigation requires a separate device-attestation track (existing devices co-sign new devices' encryption keys, transparency-log key change monitoring, or similar Signal-style safety numbers). Out of scope for V1.5; tracked under V2 capability surface.
- **Forward secrecy in the message-by-message sense.** HPKE Base mode uses a fresh ephemeral sender keypair per message AND a long-term static recipient key. A device-key compromise today CAN decrypt past messages stored on the server. True forward secrecy requires ratcheting (Double Ratchet) which is out of V1.5 scope.

The V1.5 roadmap claim of "forward secrecy" is replaced in Phase 9 documentation by **per-device confidentiality + rotation-free revocation** — what HPKE Base actually delivers.

### 11.12 Device Enrollment

`POST /v1/auth/devices/enroll` body adds `encryption_public_key`:

```json
{
  "public_key": "<base64 Ed25519 device-bound JWT key>",
  "encryption_public_key": "<base64 X25519 32-byte>",
  "fcm_token": "..."
}
```

Server stores both in the `devices` row. `device_directory_version` for the user bumps on this insert.

`PUT /v1/auth/devices/me/encryption_key` (auth: `current_auth_context`, mutates only `ctx.device.id`):

```json
{
  "encryption_public_key": "<base64 X25519 32-byte>"
}
```

Rotates the device's encryption pubkey. Bumps `device_directory_version`. Old key material is irrecoverable after rotation — messages encrypted to the old key become undecryptable on this device.

### 11.13 SSE Event

User-facing SSE channel adds:

```json
{
  "type": "devices.changed",
  "version": <int>,
  "user_id": "<uuid>"
}
```

Clients refetch the user's own device list on receipt. Senders have no SSE channel; they refresh on directory cache TTL (60s) or on `409 stale_recipient_set` rejection.

### 11.14 Migration Notes

Phase 9 is wire-incompatible with V1. Migration in V0.1 dev-mode:

- `devices.encryption_public_key` column is `BYTEA NULL` during migration; transition to `NOT NULL` after a clean-up migration once all active dev devices have re-enrolled.
- Existing V1 live cards in the `live_cards` table are deleted by the Phase 9b migration — they're encrypted under `pairing_key` which the new client can't derive.
- Existing V1 messages aren't migrated; users see an empty inbox on first launch of the V2 client. (Acceptable for dev-mode.)
- The Phase 8 `master_key_rotation` flow (§10) is unchanged. `master_key` still wraps the user-state blob.

### 11.15 Test Vectors

Phase 9b implementation MUST produce byte-identical outputs for these fixtures:

- HPKE round-trip: fixed `(skR, pkR, info, plaintext)` → expected `enc || ciphertext`.
- Multi-recipient publish with sorted envelopes: N=3 fixed devices, fixed CEK, fixed payload → expected `recipient_envelopes` array byte-equal across Python/Kotlin.
- Canonical info JSON: every combination of `envelope_kind` and present/absent card fields → expected canonical byte sequence.
- Ed25519 envelope signature: fixed sender key + fixed envelope → expected signature.
- Recipient classification: 8-row matrix from §11.10.

Vectors live in `server/tests/fixtures/phase9_vectors.json` and are asserted by both `server/tests/test_phase9.py` and `android/core/crypto/src/test/.../HpkeTest.kt`.

