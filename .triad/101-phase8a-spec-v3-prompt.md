=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
=================================================================

# Consultation 101 — Phase 8a spec v3 (master-key rotation)

Consultation 100: **Gemini GREEN, Codex RED** on §10.7's proof-
binding overclaim + several YELLOWs (auth_key naming, schema
gap for challenge storage, pairing-set lock MUST, rate-limit
success-vs-fail separation, embedded Argon2id bytes).

This consultation reviews spec v3 which:
- Walks back the §10.7 replay-resistance claim and documents the
  actual layered defenses honestly (Codex 100 RED).
- Cleans up the `auth_key` / `auth_key_hash` naming.
- Schema'd `rotation_challenges` storage in §10.3.
- MUST clause on pairing-create/delete locking the user row.
- Separate rate-limit counters for successful rotations vs
  failed proof attempts.
- ACTUAL Argon2id + AES-GCM test-vector bytes embedded
  (computed against `cryptography==45.0.3` and locked).
- Gemini 100's wrap-MK downgrade-defense fix: client checks
  the server's reported `key_generation` against the high-water
  mark BEFORE unwrap.

## Diff from v2

```text
§10.3:
+ rotation_challenges table schema OR explicit Redis option
§10.7:
- "captured proof can't be replayed" — false claim, removed
+ honest security boundary: TLS bearer + single-use challenge +
  rate limits + session revocation on compromise
+ field naming locked: `current_password_proof` = 32-byte
  auth_key bytes; server compares SHA-256(proof) to
  users.auth_key_hash
+ `new_auth_key_proof` (new wire field) for the new auth_key
  during password change; server stores SHA-256(new_auth_key_proof)
  as the new auth_key_hash
§10.8:
+ MUST clause: pairing-create / pairing-delete / state-push
  routes lock the user row before mutating
+ rate-limit refinement: 3 successful rotations per 24h,
  10 failed proof attempts per hour
§10.10:
+ wrap-MK fetch response carries key_generation in the
  envelope; client compares vs high-water mark before unwrap
§10.13:
+ Argon2id outputs + AES-GCM ciphertexts computed and
  embedded
```

---

```markdown
## 10. Master-Key Rotation (V1.5)

The user's 32-byte master key (§2) is the root secret for all
per-user symmetric crypto: it AES-GCM-encrypts the synced user-
state blob and every per-pairing opaque blob. Rotation replaces
the master key while preserving access to all historical state.

### 10.1 Rotation modes

| `reason` | Master key | Wrap key | Auth salt | Data re-encrypted | `key_generation` bump | Sessions revoked |
|---|---|---|---|---|---|---|
| `password_rewrap` | unchanged | NEW (from new password) | NEW (16 random bytes) | NO | NO | NO |
| `root_hygiene_rotation` | NEW (32 random bytes) | unchanged | unchanged | YES | YES (+1) | NO |
| `root_compromise_rotation` | NEW | NEW (from new password) | NEW | YES | YES (+1) | YES — all sessions including the initiating one |

For `password_rewrap` the master key value stays byte-identical;
only its wrapping changes.

For `root_compromise_rotation` the initiating session is revoked
along with all others. The user must log in again from scratch
on every device.

### 10.2 What rotation does NOT protect against

Master-key rotation re-encrypts data stored under the old key.
It does NOT revoke:

- **Sender-held pairing keys.** Senders received stable pairing
  keys at bootstrap and continue to encrypt messages with them.
  A compromised pairing key needs a separate re-pair protocol
  (V2 follow-up).
- Messages already delivered to other devices.
- Data exfiltrated from an unlocked device.
- Cards encrypted under per-sender pairing keys (the per-pairing
  keys are unchanged through rotation; cards remain
  decryptable).

The Android UX MUST display a "backup-or-lose-access" warning
(spec MUST, not just product nicety) before submitting any
rotation that changes the password.

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

`rotation_challenges` is the durable store for the single-use
challenge nonces issued by `POST /v1/account/rotate-master-key/challenge`.
Production deployments MAY replace this table with Redis-with-TTL
provided the redis store supports atomic consume (e.g.
`GETDEL`) — Phase 8b implementation uses Postgres because the
rest of the durable state already lives there. A periodic
cleanup pass (in `app/jobs/retention.py`) deletes expired
challenges.

The audit row is INSERTed inside the same transaction as the
rotation (before COMMIT). If the rotation rolls back, the audit
row rolls back with it. No secret material in the log.

### 10.4 CAS counter semantics (LOCKSTEP CONTRACT)

This is the rule blobs depend on for AAD verification:

1. The client encrypts a blob using `state_version_observed + 1`
   (the version the row will have *after* the server's write)
   in the AAD.
2. The server, on a successful CAS write, increments
   `state_version` by EXACTLY ONE.
3. On decrypt the AAD `state_version` and the row's
   `state_version` match byte-for-byte.

If a server uses any other increment policy (timestamp, opaque
ID, "next available integer above N"), decryption will fail with
an AEAD tag error and the blob is permanently unreadable.
Implementations MUST integrity-test this lockstep with the
test vectors in §10.13.

The same rule applies to `key_generation`:

1. For `root_*` rotations, the client encrypts every blob it
   writes during rotation with `new_generation = old + 1` in
   the AAD.
2. The server sets `users.key_generation = new_generation` and
   every blob row's `key_generation` to the same value.
3. For `password_rewrap`, `key_generation` is unchanged and
   appears identically in every AAD.

### 10.5 `key_generation_observed` is required on EVERY state-mutating endpoint

Every endpoint that writes a `key_generation`-tagged blob MUST
require the client to pass `key_generation_observed` in the
request, and MUST 409 with
`current_key_generation` in the response if it doesn't match
`users.key_generation`. This prevents the post-rotation race
where a pre-rotation client still holds the old master key in
memory and tries to push state encrypted under it after the
rotation committed.

Endpoints in scope:
- `POST /v1/account/rotate-master-key`
- `PUT /v1/state` (encrypted_user_state CAS push)
- `PUT /v1/pairings/{pairing_id}/state` (per-pairing state CAS)
- Any future endpoint writing a `key_generation`-tagged blob.

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

The challenge is single-use and bound to `(user_id, session_id)`
in the `rotation_challenges` table. Failed proof attempts do
NOT consume the challenge — that would let a hijacker invalidate
a legitimate user's challenge by burning it with bad guesses.
The challenge is consumed (DELETEd) only when the rotation
transaction succeeds OR when it expires.

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

`pairings[]` MUST list EVERY pairing currently associated with
the user (for `root_*`). A `password_rewrap` MUST omit
`new_encrypted_user_state` and `pairings`.

### 10.7 Authentication proof

`current_password_proof` is the Argon2id-derived `auth_key`
(first 32 bytes of the 64-byte derivation) for the user's
CURRENT password. The server verifies it by computing
`SHA-256(current_password_proof)` and constant-time-comparing
the result against `users.auth_key_hash`.

`new_auth_key_proof` (only present for `password_rewrap` and
`root_compromise_rotation`) is the same construction for the
NEW password. On successful rotation, the server stores
`SHA-256(new_auth_key_proof)` as the new `users.auth_key_hash`.

**Security boundary.** `current_password_proof` is a
**bearer-credential under TLS** — anyone who captures these
32 bytes can authenticate as the user for any endpoint that
takes them. The protocol does NOT bind the proof bytes to the
challenge, so a captured `auth_key` can in principle be replayed
with a different challenge in a future rotation request. We
considered binding the proof via HMAC-SHA256(auth_key, challenge)
but rejected it for V1.5: the server only stores SHA-256(auth_key),
not the raw key, so it cannot verify an HMAC without weakening
the at-rest storage to a password-equivalent. (A proper
password-authenticated key exchange like OPAQUE is the V2+
solution.)

The actual defenses are layered:

1. **TLS transport.** The proof bytes never appear unencrypted
   on the wire. The server MUST NOT log them anywhere — not in
   request logs, not in audit rows, not in error responses.
2. **Single-use rotation challenge.** Each rotation request
   consumes a fresh server-issued challenge. A captured POST
   body can't be replayed verbatim — its challenge is already
   gone.
3. **Rate limits.** A captured proof can drive at most 3
   successful rotations per user per 24-hour window (§10.8 step
   4). Repeated FAILED proof attempts (wrong auth_key) are rate-
   limited separately at 10 per user per hour to prevent
   brute-force.
4. **Mode-specific session revocation.**
   `root_compromise_rotation` revokes ALL sessions, including
   the one that issued the rotation. A hijacker who rotated
   loses access at the same moment the legitimate user does.
5. **Re-type at rotation time.** The client UI MUST prompt for
   the current password fresh each rotation — no cached
   `wrap_key`. This narrows the attack window to the moment of
   active rotation.

### 10.8 Server-side processing

Inside ONE Postgres transaction:

1. Validate request shape (reject unknown fields, required
   combinations per reason).
2. `SELECT challenge FROM rotation_challenges WHERE challenge = :c
   AND user_id = :u AND session_id = :s AND expires_at > NOW()
   FOR UPDATE`. 401 if missing.
3. Verify `current_password_proof`: compute SHA-256, constant-
   time-compare against `users.auth_key_hash`. 401 if mismatch.
   On 401, increment a per-user failed-proof counter (10/hr
   limit); transaction rolls back so the challenge is NOT
   consumed.
4. Rate-limit successful rotations: 3 per user per 24h. Read
   `master_key_rotation_audit` count where `occurred_at > NOW() -
   24h AND user_id = :u`. 429 with `Retry-After` if at cap.
5. `SELECT users.* FROM users WHERE id = :user_id FOR UPDATE`.
6. Verify `users.key_generation == key_generation_observed`. 409
   if mismatch.
7. For `root_*`: count user's active pairings inside the lock.
   Verify the request lists exactly that count (no missing, no
   extra). Concurrent pairing create/delete is prevented by the
   MUST clause below; if the count still mismatches (e.g.
   pre-Phase-8 service didn't lock yet), respond 409
   `pairing_set_changed` (NOT 400).

   **MUST.** Every endpoint that creates or deletes a pairing
   for a user MUST `SELECT users.* FROM users WHERE id = :user_id FOR UPDATE`
   as its first DB operation. This serializes pairing-set
   mutation against rotation. Endpoints in scope:
   `POST /v1/pairing/complete`, `DELETE /v1/pairings/{id}`,
   any future pairing create/delete route.

8. CAS each pairing: `pairings.state_version == state_version_observed`.
   First mismatch → 409 `pairing_state_changed` with the list
   of mismatched `pairing_id` + their current `state_version`.
9. CAS `encrypted_user_state.state_version == state_version_observed`.
   409 `state_version_mismatch` with `current_state_version`.
10. Apply writes:
    - `users.encrypted_master_key = new_encrypted_master_key`.
    - For `password_rewrap` and `root_compromise_rotation`:
      `users.auth_key_hash = SHA-256(new_auth_key_proof)`,
      `users.auth_salt = new_auth_salt`.
    - For `root_*`: `users.key_generation = old + 1`.
    - For `root_*`: rewrite `encrypted_user_state` (bump
      `state_version` by EXACTLY 1, set `key_generation =
      new_generation`).
    - For `root_*`: rewrite EACH pairing's `encrypted_state`,
      `state_version` (+1), `key_generation = new_generation`.
11. INSERT one `master_key_rotation_audit` row.
12. `DELETE FROM rotation_challenges WHERE challenge = :c`.
13. For `root_compromise_rotation`: `DELETE FROM device_sessions
    WHERE user_id = :user_id` (ALL sessions including
    initiating).
14. COMMIT.

Response (200):

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

For `root_compromise_rotation` the response is the last
authenticated call this session can make; the next request
returns 401 and the client must log in again.

### 10.9 AAD shapes

All AAD bytes use canonical JSON: `sort_keys=True`,
`ensure_ascii=True`, `separators=(",", ":")`. UUIDs use
`str(uuid.UUID(v))` (lowercase no-brace).

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

NOT bound to `key_generation` because the wrapped MK is the
chicken-and-egg root — it has to decrypt BEFORE the client knows
the current `key_generation`. The downgrade defense for the
wrap-MK lives at the response layer (§10.10).

### 10.10 Downgrade defense

Every device persists `highest_key_generation_seen` in local
unencrypted storage. Every response carrying a
`key_generation`-tagged value is checked:

```text
if response.key_generation < highest_key_generation_seen:
    hard_fail("server returned downgraded key_generation; possible attack")
```

This includes the wrap-MK fetch (the `/login` and `/account`
endpoints MUST return `key_generation` alongside the wrapped
master-key blob; client checks BEFORE attempting to unwrap and
use the MK). Without this check, a server could silently serve
a stale wrapped MK + stale state, and the client's AEAD decrypts
would all succeed (everything matches under the old generation)
but the client would be operating on a frozen snapshot.

### 10.11 Mixed-client behavior

The server detects client capability via the
`X-Syncler-Client-Min-Phase` header, set to the integer `8` by
all Phase-8-aware apps.

- Pre-Phase-8 client (header missing or < 8) hits a
  `key_generation`-tagged endpoint while `users.key_generation > 1`:
  server responds **426 Upgrade Required**:
  ```json
  {"error": "account_upgraded_requires_newer_client",
   "minimum_supported_phase": 8}
  ```
- Pre-Phase-8 client + `users.key_generation == 1`: server
  serves normally. The user has not rotated yet so the legacy
  app can still decrypt the existing blobs.

### 10.12 Client-side flow

#### 10.12.1 Initiating device

1. User invokes "Rotate master key" (or "Change password")
   from Settings.
2. App displays the backup-or-lose-access warning (§10.2 MUST).
3. App prompts for current password. Derive Argon2id locally
   to recover `current_wrap_key` and compute
   `current_password_proof = current_auth_key`.
4. For password change: prompt for new password, derive new
   Argon2id outputs.
5. For `root_*`: generate new master key with CSPRNG (32 bytes).
6. POST `/rotate-master-key/challenge` → receive
   `rotation_challenge`.
7. Fetch latest server state (`GET /v1/state` and each pairing
   state). Record `state_version_observed` per row.
8. Decrypt each blob with the current master key.
9. Re-encrypt each blob with the NEW master key (or unchanged
   MK for `password_rewrap`), using
   `state_version_observed + 1` in AAD (§10.4 lockstep).
10. Re-wrap the new (or unchanged) master key with the new
    wrap key.
11. POST `/rotate-master-key` with the full payload.
12. On 200: update local state with new MK and
    `key_generation`. Persist new high-water mark in local
    unencrypted storage.
13. On 409: refetch affected blob(s), restart from step 7.
14. For `root_compromise_rotation`: app is signed out; show
    "logged out everywhere" UX, return to login screen.

#### 10.12.2 Other device coming online

1. Periodic sync polls `GET /v1/state`. Response carries
   `key_generation`.
2. If server's `key_generation > local high-water mark`: show
   "Account encryption key rotated — please re-enter your
   password" banner.
3. User enters password. Device fetches the new wrapped MK +
   `key_generation` from `/account` or `/login`.
4. Verify `response.key_generation >= local high-water mark`
   (downgrade defense).
5. Derive new wrap_key (Argon2id with whatever `auth_salt` the
   server returned — may have rotated), unwrap MK, persist
   `key_generation` as new high-water mark.
6. Refetch all state blobs (now encrypted under the new MK).

#### 10.12.3 Offline merge

If the device made local changes while offline that haven't been
pushed, the device's CAS push 409s with `key_generation_mismatch`
(§10.5). The device:

1. Re-runs the login flow to fetch the new wrapped MK.
2. Discards its old encrypted local blob.
3. Decrypts the new server blob with the new MK.
4. Replays the local pending changes on top of the new
   decrypted state.
5. Re-encrypts with the new MK + new lockstep counters, pushes
   via fresh CAS.

### 10.13 Test vectors

Argon2id parameters per §1 (`m_cost=19456 KiB`, `time_cost=2`,
`parallelism=1`, `hash_len=64`). All hex unless noted.
Computed against `cryptography==45.0.3`.

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

8b implementations MUST produce byte-identical
`old_wrapped_blob`, `new_wrapped_blob`,
`hygiene_wrapped_blob`, `old_encrypted_us`, and
`new_encrypted_us` given the fixture inputs. Argon2id
derivations are checked against the embedded bytes; AES-GCM
encryptions against the embedded ciphertexts.
```

---

## Codex 100 / Gemini 100 deltas

1. (Codex 100 RED §10.7) Replay claim removed. Section now
   honestly documents the bearer-credential nature of
   `current_password_proof` + the 5-layer defense stack.
2. (Codex 100 §10.7 naming) Wire fields renamed to
   `current_password_proof` and `new_auth_key_proof` (32-byte
   auth_key bytes); server stores `SHA-256(proof)` as
   `auth_key_hash`. No more ambiguity.
3. (Codex 100 §10.3 schema gap) `rotation_challenges` table
   declared in §10.3.
4. (Codex 100 §10.8 pairing concurrency) MUST clause: every
   pairing create/delete locks the user row. Listed endpoints in
   scope.
5. (Codex 100 §10.13) Argon2id + AES-GCM bytes computed against
   `cryptography==45.0.3` and embedded inline.
6. (Codex 100 rate limit) Separate counters: 3 successful
   rotations / 24h + 10 failed proof attempts / hour. Failed
   attempt does NOT consume the challenge (step 3 → rollback).
7. (Gemini 100 wrap-MK downgrade) §10.10 explicitly covers the
   wrap-MK fetch — `/login` and `/account` return
   `key_generation` alongside the wrapped blob; client checks
   against high-water mark BEFORE unwrapping.

## Output

Per reviewer:
1. Per-section: GREEN / YELLOW / RED.
2. Anything still missing or wrong.
3. Anything new.

If dual-GREEN, this becomes `docs/crypto-spec.md §10` verbatim.

Reply text only. Do NOT call any write/mutation tool.
