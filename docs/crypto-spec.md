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

AAD is canonical JSON encoded as UTF-8 with sorted keys and compact separators. It contains exactly the following protocol fields:

```json
{
  "message_id": "...",
  "sender_id": "...",
  "user_id": "...",
  "plugin_id": "...",
  "min_plugin_version": 1,
  "created_at": "...",
  "schema_version": 1
}
```

Missing AAD fields are errors. Callers must provide explicit values.

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

### Message AEAD

```text
aad_json = {"created_at":"2026-05-20T00:00:00Z","message_id":"msg-001","min_plugin_version":1,"plugin_id":"plugin.weather","schema_version":1,"sender_id":"sender-alpha","user_id":"user-123"}
aad_hex = 7b22637265617465645f6174223a22323032362d30352d32305430303a30303a30305a222c226d6573736167655f6964223a226d73672d303031222c226d696e5f706c7567696e5f76657273696f6e223a312c22706c7567696e5f6964223a22706c7567696e2e77656174686572222c22736368656d615f76657273696f6e223a312c2273656e6465725f6964223a2273656e6465722d616c706861222c22757365725f6964223a22757365722d313233227d
key = f6ed649481dd8a5ffc57401b816803fba79556731c5c9ff53be49f7862f8cb8e
nonce = 101112131415161718191a1b
plaintext = 7b2274656d70657261747572655f63223a32317d

ciphertext_with_tag = 3fefbb1a0238b24f6860563d1e3194c9bf47d1dfdb255e7b87ad60d5ab9b07573bbf55bc
wire = 101112131415161718191a1b3fefbb1a0238b24f6860563d1e3194c9bf47d1dfdb255e7b87ad60d5ab9b07573bbf55bc
```

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

## Python Reference Snippets

```python
import json
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
