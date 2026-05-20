# M6 — Pairing + revocation (sender registration, QR broker, blocklist)

You completed M1–M5. M6 wires the sender ↔ user pairing flow with the platform acting as a broker (Round 6 decision: pairing broker = server, content stays E2E).

Workspace-write granted. Touch `server/` and `android/`.

## Server-side (M6.1)

### Sender registration
- `POST /v1/senders/register` (no auth — public endpoint, rate-limited per IP)
  Request:
  ```json
  {
    "public_key": "<base64 Ed25519 32 bytes>",
    "name": "Trading Bot",
    "contact": "ops@trading.com"
  }
  ```
  Response 201:
  ```json
  { "sender_id": "<UUID>", "created_at": "..." }
  ```
- Sender keeps the corresponding private key locally. Server has only the public key.

### Pairing initiation by sender
- `POST /v1/pairing/initiate` — sender authenticates with Ed25519 signature over the request body
  Request:
  ```json
  {
    "sender_id": "<UUID>",
    "pairing_token": "<32 random bytes base64>",   // sender-generated, single use
    "ttl_seconds": 300,
    "metadata": { "icon_url": "...", "name_override": "..." }  // optional, shown to user at pairing
  }
  ```
  Response 200:
  ```json
  {
    "pairing_id": "<UUID>",                       // server-issued, links this initiation
    "broker_url": "https://api.syncler.app/v1/pairing/complete?token=<pairing_token>",
    "expires_at": "..."
  }
  ```
- Stores a `pending_pairings` row keyed by `(sender_id, pairing_token)` with TTL

### Pairing completion by user
- `POST /v1/pairing/complete` — user authenticated (JWT)
  Request:
  ```json
  {
    "pairing_token": "...",
    "user_public_key": "<base64 user's public key for this sender>",   // per-sender ECDH-style
    "encrypted_initial_state": "<base64>"                             // bootstrap encrypted state
  }
  ```
  Response 201:
  ```json
  {
    "pairing_id": "<UUID>",
    "sender_id": "<UUID>",
    "sender_public_key_fingerprint": "abcd-efgh-ijkl",   // for user UI confirmation
    "sender_name_hash": "<base64 SHA-256 of name at pair time>",
    "sender_icon_hash": "<base64 or null>"
  }
  ```
- Looks up pending pairing by token; verifies not expired; not already used
- Creates `pairings` row with `encrypted_state` = user-supplied bootstrap
- Marks pending pairing as consumed
- Returns sender's pubkey fingerprint for I4 anti-spoofing UX (user confirms it matches what sender showed them)

### Revocation endpoints
- `POST /v1/pairing/{pairing_id}/revoke` — user-side (JWT auth)
  Sets `revoked_at = now()`. Subsequent sends from that sender to that user return `410 Gone`.
- `POST /v1/senders/revoke` — sender-side (signed by sender's private key)
  Self-revocation: sets `senders.revoked_at = now()`. All pairings effectively revoked. Sender can't send any more messages.

### QR endpoint
- `GET /v1/pairing/qr/{pairing_token}` — returns a PNG QR code encoding the JSON `{ broker_url, sender_public_key_fingerprint, sender_name, ttl_seconds }`. The Android app scans this and uses the contained URL + fingerprint to drive the I4 confirmation.

### Files
- `server/app/routers/pairing.py` — NEW: initiate, complete, qr, revoke (user side), revoke (sender side under `/v1/senders`)
- `server/app/routers/senders.py` — NEW: register, list (admin/self), revoke
- `server/app/services/pairing.py` — NEW
- `server/app/services/senders.py` — NEW
- `server/app/schemas.py` — UPDATE: add pairing + sender schemas
- `server/app/models.py` — UPDATE: add `PendingPairing` model (id, sender_id, pairing_token, ttl, consumed_at, metadata_json, created_at)
- `server/alembic/versions/0002_pending_pairings.py` — NEW migration
- `server/app/services/qr.py` — NEW: QR generation using `qrcode[pil]` (add to pyproject.toml)
- `server/tests/test_pairing.py` — NEW
- `server/tests/test_senders.py` — NEW

## Android-side (M6.2)

### Pairing UI
- `android/feature/pairing/...` — promote stub to real module
- `PairingScreen.kt` — Compose UI:
  1. "Scan QR" button (uses CameraX + MLKit Vision barcode scanner)
  2. Parses scanned URL + extracts fingerprint, name, etc.
  3. Shows confirmation dialog: *"Pair with 'Trading Bot' (fingerprint: ABCD-EFGH-IJKL)? Confirm this matches what the sender displayed."*
  4. On confirm: generates user keypair, computes initial encrypted state, calls `POST /v1/pairing/complete`
  5. On success: marks sender as paired locally (per-pairing record in local DB with `name_hash`, `icon_hash`, `first_paired_at`)

### Sender identity store (I4)
- `android/core/storage/.../PairedSenderStore.kt` — NEW: per-pairing record:
  ```kotlin
  data class PairedSender(
    val pairingId: UUID,
    val senderId: UUID,
    val publicKeyFingerprint: String,
    val nameHash: ByteArray,
    val iconHash: ByteArray?,
    val firstPairedAt: Instant,
  )
  ```
- On every incoming message: verify sender's signature against the stored pubkey + match name/icon hashes; mismatch → reject + show alert (I4 layer 2/4)

### Pairing list UI in settings
- `android/feature/settings/.../PairedSendersScreen.kt` — list all paired senders w/ "Paired ✓" badge (I4 layer 5), "Revoke" button, tap-to-see-fingerprint

### Files
- `android/feature/pairing/build.gradle.kts`
- `android/feature/pairing/.../PairingScreen.kt`
- `android/feature/pairing/.../PairingViewModel.kt`
- `android/feature/pairing/.../QrScanner.kt`
- `android/core/storage/.../PairedSenderStore.kt`
- `android/feature/settings/.../PairedSendersScreen.kt`
- Unit tests for the fingerprint formatter (groups of 4) + name/icon hash verifier

## Constraints
- Sender pubkey fingerprint = base32 of first 8 bytes of SHA-256(public_key), formatted as 4-char groups separated by `-`
- Name/icon hash = SHA-256 of UTF-8 bytes / image bytes
- Sender icon is fetched once at pair time, hashed, then locally cached; future sends include the hash in envelope metadata and devices verify
- Pending pairings expire after their TTL (1-15 min); pruning is part of M1.7's retention job
- Revoked pairing → server returns 410 immediately on any message attempt
- All E2E content keys flow client-to-client; broker only stores ciphertext

## Print summary
- Files created
- QR payload format example
- The exact format of `sender_public_key_fingerprint` (with an example)
