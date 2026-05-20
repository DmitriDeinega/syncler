# M2 — Crypto layer (server-side reference + shared protocol spec)

You completed M1. M2 is the cryptographic foundation that the server, the Android app, and both SDKs must agree on. Server-side this milestone is mostly:
- Implementing the **verification** side of all crypto operations (signature verification, AAD validation, nonce uniqueness checks)
- Producing a **canonical reference document** in `docs/crypto-spec.md` that the Android app and SDKs will follow
- Reference test vectors so all three implementations can validate against the same expected outputs

The actual client-side derivations (Argon2id, master key generation, message encryption) happen in the Android app + SDKs in later milestones. The server only verifies things it received.

## Files

### `server/app/crypto/__init__.py` — NEW

### `server/app/crypto/argon2.py` — NEW
- `ARGON2_PARAMS_V1`: dict with `m_cost=19_456` (= 19 MiB), `time_cost=2`, `parallelism=1`, `hash_len=64` (split into 32-byte auth_key + 32-byte wrap_key)
- Function `verify_auth_key_hash(stored_hash: bytes, submitted_hash: bytes) -> bool` using `secrets.compare_digest`
- Function `params_for_version(version: int) -> dict` so V2 params can be added later without changing call sites

### `server/app/crypto/hkdf.py` — NEW
- Constants for HKDF labels:
  - `HKDF_LABEL_PAIRING_KEY = "syncler-v1-pairing-key"`
  - `HKDF_LABEL_NOTIFICATION_KEY = "syncler-v1-notification-key"` (for future use)
- Function `derive_pairing_key(master_key: bytes, sender_id: bytes) -> bytes` returning 32 bytes via HKDF-SHA256 with explicit info string per the contract: `info = HKDF_LABEL_PAIRING_KEY + ":" + sender_id`
- This is for tests / reference only; real derivation happens client-side. The server has no master_key.

### `server/app/crypto/signatures.py` — NEW
- Ed25519 signature verification using `cryptography.hazmat.primitives.asymmetric.ed25519`:
  - `verify_message_envelope(public_key: bytes, envelope: bytes, signature: bytes) -> bool`
  - `verify_plugin_bundle(sender_public_key: bytes, canonical_manifest: bytes, signature: bytes) -> bool`
- Helper `canonical_manifest_for_signing(manifest: dict) -> bytes` that:
  - Removes the `signature` field
  - Sorts keys lexicographically
  - Encodes to JSON with `separators=(",", ":")` and `sort_keys=True` and `ensure_ascii=True`
  - Returns UTF-8 bytes
  - Append `bundleHash` bytes (hex-decoded) per the V1 contract: `canonical_manifest_without_signature || bundleHash`

### `server/app/crypto/aead.py` — NEW
- AES-256-GCM helpers using `cryptography.hazmat.primitives.ciphers.aead.AESGCM`:
  - `decrypt_message_body(pairing_key: bytes, wire: bytes, aad: bytes) -> bytes` — splits `wire = nonce[:12] || ciphertext_with_tag`, calls AESGCM(pairing_key).decrypt(nonce, ct_with_tag, aad)
- For reference only on the server side (server never has pairing_key in production; the Android app + plugin SDK use this same logic)
- `assemble_aad(fields: dict) -> bytes` — canonical AAD assembly: sorted keys, JSON-canonical, UTF-8. AAD always contains: `message_id, sender_id, user_id, plugin_id, min_plugin_version, created_at, schema_version`. Missing fields error out (don't substitute defaults — let the caller be explicit).

### `server/app/crypto/nonce.py` — NEW
- `generate_nonce() -> bytes` — 12 cryptographically random bytes via `secrets.token_bytes(12)`
- Server-side **nonce uniqueness check** for incoming messages: `class NonceRegistry` (in-memory LRU for V1; backed by Redis or DB column in V1.5) with `seen(sender_id, nonce) -> bool` returning True if the nonce was already seen in the last window
- For V1: per-sender LRU of last 100,000 nonces

### `server/app/crypto/wire.py` — NEW
- Wire format encoder/decoder helpers:
  - `pack_message(nonce: bytes, ciphertext_with_tag: bytes) -> bytes` returning `nonce || ciphertext`
  - `unpack_message(wire: bytes) -> tuple[bytes, bytes]` returning `(nonce, ciphertext_with_tag)`
- Length validation, error on malformed input

### `server/tests/test_crypto.py` — NEW
- Test `argon2`: derives a 64-byte hash, splits into auth + wrap, params version round-trips, params_for_version raises on unknown versions
- Test `hkdf`: derive_pairing_key produces 32 bytes; same inputs → same output; different sender_id → different output; matches a known test vector hardcoded in the test (compute once, paste, comment with command used)
- Test `signatures`: generate Ed25519 keypair via `cryptography`, sign a manifest, verify_plugin_bundle returns True; tampered signature returns False; tampered manifest returns False
- Test `aead`: encrypt-then-decrypt round-trip; AAD mismatch raises `InvalidTag`; nonce reuse with same key+plaintext+aad produces same ciphertext (sanity); short nonce → ValueError
- Test `nonce`: registry detects replay within window; per-sender isolation
- Test `wire`: pack/unpack round-trip; truncated wire → error

### `docs/crypto-spec.md` — NEW
A standalone document the Android team + SDK teams use. Sections:
1. **Key hierarchy**: password → Argon2id → (auth_key 32B + master_key_wrap_key 32B). Argon2 params version 1 = m_cost=19_456 KiB, time_cost=2, parallelism=1.
2. **Master key**: client-generates 32 random bytes at signup. Encrypted with master_key_wrap_key via AES-256-GCM. Stored on server as opaque blob.
3. **Pairing key derivation**: HKDF-SHA256(ikm=master_key, salt=sender_id, info="syncler-v1-pairing-key:" + sender_id, length=32)
4. **Message encryption**: AES-256-GCM with 12-byte random nonce per message. Wire format: nonce ‖ ciphertext_with_tag. AAD canonical JSON of `{message_id, sender_id, user_id, plugin_id, min_plugin_version, created_at, schema_version}`.
5. **Plugin bundle signing**: Ed25519. Sign `canonical_manifest_without_signature || bundleHash`. Canonical manifest = JSON with sorted keys, `separators=(",",":")`, ASCII only.
6. **Test vectors** (Python REPL output for reference — paste the actual outputs your tests assert against)
7. **Nonce reuse policy**: never reuse a nonce with the same key. Server-side LRU detects per-sender replay. Clients must use a CSPRNG.

Include code snippets in Python AND a section "Equivalents for Android/Kotlin" pointing at `androidx.security.crypto` + `BouncyCastle` for Ed25519 + `cryptography` of choice — names only, no implementation in this doc.

### `server/pyproject.toml` — UPDATE: confirm `cryptography` and `argon2-cffi` are pinned (they should be from M1.1)

## Constraints
- All code is typed
- Tests use exact byte equality, not "looks fine"
- All constants live in module-level globals, no magic numbers
- The `docs/crypto-spec.md` is at repo root in `docs/`, not under `server/`

## Print summary
- Files created
- Test vectors values you generated (so they're greppable later)
- Any deviation from the V1 contract (with reason)
