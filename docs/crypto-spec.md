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

The V1 server performs a per-sender in-memory LRU replay check for the last 100,000 nonces. This protects the current server process only. A Redis or database-backed replay check is planned for V1.5.

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
  "sender_id": "...",
  "user_id": "..."
}
```

**`user_id` is REQUIRED in the envelope.** Without it, a delete signature valid for one user's card could be replayed against another user's card with a coincidentally matching `(sender_id, card_key)` — the table is uniquely keyed on `(sender_id, user_id, card_key)`, so the lookup would otherwise be ambiguous. (Codex consultation 62 security finding.) The server matches the exact triple before deleting; mismatched triples no-op silently and still publish a `card.delete` SSE event so all of the user's devices clear any stale local copy.

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
) -> bytes:
    return json.dumps(
        {
            "card_key": card_key,
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
