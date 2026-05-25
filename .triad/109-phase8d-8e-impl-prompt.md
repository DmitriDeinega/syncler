=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 109 — Phase 8d (AAD lockstep) + Phase 8e (root_* rotations)

Two phases ship together in this consultation. Phase 8a-c shipped
dual-GREEN across consults 99-108. This consult covers everything
needed to finish the §10 master-key rotation track end-to-end.

User is restarting between work sessions; both phases are committed
already (commits `fb9a536` for 8d, `eb8fb0c` for 8e) so reviewers
can read the diffs against `7d2c0a6`. Recommend fixes for me to
apply on the next session.

## Phase 8d — §10.9 AAD-binding (commit fb9a536)

Wires the three AAD shapes spec'd in §10.9 across signup, login,
rotation, user-state sync, and pairing complete. Cross-cuts client
+ server.

### AAD shapes (canonical JSON, sort_keys, separators=(",", ":"))

- `users.encrypted_master_key`:
  `{"auth_salt_b64": "<base64>", "user_id": "<uuid>"}`. NOT bound
  to `key_generation` (spec: the wrapped MK is the chicken-and-
  egg root; downgrade defense lives at §10.10).
- `encrypted_user_state.encrypted_blob`:
  `{"key_generation": <int>, "state_version": <int>,
   "user_id": "<uuid>"}`. `state_version` is the POST-write value
  (§10.4 lockstep).
- `pairings.encrypted_state`:
  `{"key_generation": <int>, "pairing_id": "<uuid>",
   "state_version": <int>, "user_id": "<uuid>"}`.

### Chicken-and-egg fix

The MK wrap AAD binds `user_id`, but pre-Phase-8d the server
generated user_id only at signup-response time — the client
couldn't know it before wrapping. Same for pairing_id at
pairing complete. Resolved by accepting client-generated UUIDs
in both request bodies:

- `SignupRequest.user_id: UUID | None` — server uses it when
  present, falls back to `uuid4()` for pre-Phase-8d clients.
- `PairingCompleteRequest.pairing_id: UUID | None` — same shape.

### Implementation surface

- `core/crypto/Aad.kt`: new `RotationAad` object with
  `masterKeyWrap`, `userState`, `pairingState` builders.
- `core/crypto/MasterKey.kt`: `wrap`/`unwrap` take optional AAD
  passed through to `Aead.encrypt/decrypt`.
- `core/auth/AuthRepository`:
  - `signup` generates UUID v4 locally, wraps MK with
    `masterKeyWrap(userId, authSaltB64)`, sends `user_id` in
    `SignupRequest`.
  - `login` unwraps MK with the same AAD (response carries
    userId + authSalt).
- `core/auth/RotationRepository.rewrapPassword`: unwraps with
  current AAD then wraps same MK under new AAD bound to new
  auth_salt + same user_id.
- `feature/inbox/UserStateRepository`: `encodeLocal` /
  `decodeRemote` take `(stateVersion, keyGeneration, userId)`
  and pass `RotationAad.userState(...)` into `Aead`. Initial-
  insert POST-write `state_version` = 1.
- `feature/pairing/PairingRepository.confirm`: generates
  pairing_id (UUID v4) client-side, encrypts placeholder
  initial state under master key with `RotationAad.pairingState
  (initial state_version=1)`, sends pairing_id in the request.
  Signature changed from `encryptedInitialState: ByteArray`
  to `initialStatePlaintext: ByteArray` — caller's "placeholder"
  was never actually encrypted; the encryption lifted into the
  repo so AAD can be computed where pairing_id is known.

### Tests

Updated fakes echo client-supplied user_id in SignupResponse +
LoginResponse + the enroll JWT's `sub` claim. Without that the
unwrap AAD wouldn't match the wrap AAD round-trip.
`UserStateRepositoryPushTest`'s session now ships a JWT-shaped
token so `doPut` can extract user_id.

**Compatibility note:** Phase-8d client REQUIRES Phase-8d server.
A Phase-8d client signing up against a pre-Phase-8d server would
have the server ignore the `user_id` field, generate its own UUID,
and the client's MK wrap AAD wouldn't match on next login's
unwrap. Acceptable for V0.1 — no production users.

## Phase 8e — root_hygiene_rotation + root_compromise_rotation (commit eb8fb0c)

Both root_* modes from §10.1 work end-to-end now.

### Server

- `GET /v1/pairing/{id}/state` — returns encrypted_state +
  state_version + key_generation for a pairing the user owns.
  404 for non-owners and revoked pairings.
- `PairingStateResponse` schema.
- 3 new tests (owner ok, non-owner 404, revoked 404).

### Client RotationRepository

- `rotateHygiene(currentPassword)`:
  1. Get challenge.
  2. Derive Argon2(currentPassword, currentSalt).
  3. Local unwrap test (skip server's failed-proof bucket on typos).
  4. GET /v1/state — decrypt under OLD MK with OLD AAD.
  5. listPairings (active) + getPairingState for each — decrypt
     under OLD MK with OLD pairing AAD.
  6. Generate fresh new MK.
  7. Re-encrypt user state under new MK with NEW AAD
     (key_generation+1, state_version+1).
  8. Re-encrypt each pairing under new MK with NEW pairing AAD.
  9. Wrap new MK under SAME wrap key + SAME MK wrap AAD
     (password unchanged).
  10. POST rotate-master-key with reason="root_hygiene_rotation".
  11. verifyAndBump key_generation + Session.update.
- `rotateCompromise(currentPassword, newPassword)`:
  Same flow PLUS new salt + new wrap key + new auth_key_proof;
  reason="root_compromise_rotation". `RootRotateResult.sessionsRevoked
  = true` so caller's UI MUST log out (server revoked all
  device sessions including ours).
- Shared `rotateRoot(currentPassword, newPassword, compromise:
  Boolean)` private helper. Plaintext scratch arrays (decrypted
  state, decrypted pairings, new MK) are zeroed in `finally`
  blocks throughout.

### UX (feature/settings)

- `RotateMasterKeyCard` Composable with two buttons
  ("Rotate master key (hygiene)" / "Rotate after compromise").
- Shared `RotateMasterKeyDialog` with mode-specific copy + the
  §10.2 backup-or-lose-access warning + mandatory checkbox.
  Compromise dialog asks for new + confirm passwords; hygiene
  only asks for current.
- `SettingsViewModel.rotateHygiene` / `rotateCompromise` +
  `RotateMkUiState` (Idle/InFlight/Success/Failure). `Success`
  carries `forceLogout = sessionsRevoked` so the dialog's
  acknowledge path triggers `onLogout()` for compromise.
- `app/ui/SettingsScreen` embeds the card next to the existing
  `ChangePasswordCard`.

### Tests

`RotationRepositoryTest` gains:
- `rotateHygieneRefreshesMasterKeyAndBumpsGeneration` — round-
  trip with an AAD-bound seeded user-state blob. Asserts the
  MK actually changed, key_generation bumped 1→2, body fields
  match the spec shape.
- `rotateCompromiseAlsoChangesAuthSaltAndProof` — asserts
  new_auth_salt + new_auth_key_proof non-null, salt rotated in
  Session, sessionsRevoked = true.
13/13 core/auth tests pass. 26 server tests pass on the
rotation surface. `:app:assembleDebug` green.

## Risks I want eyes on (both phases)

1. **MK wrap AAD's auth_salt_b64 field uses base64 encoding of
   the salt bytes.** Spec example shows `"AQIDBAUGBwgJCgsMDQ4PEA=="`
   for `0102030405060708090a0b0c0d0e0f10`. My client computes
   `salt.toBase64()` which uses `java.util.Base64.getEncoder()`
   (standard alphabet, with padding). Server does the same.
   Worth flagging if base64 alphabet/padding could drift.

2. **`Session.currentUserId()` parses JWT body via regex** (no
   `org.json` in tests). Same caveat as Gemini 107 nit — fine
   for current flat pyjwt output.

3. **rotateRoot zeroing.** Plaintext scratch (currentMasterKey
   is the session's; we leave it because session.updateAfterRotation
   handles zeroing). New MK is zeroed in `finally`. Pairing
   plaintexts + state plaintext are zeroed inline after re-
   encryption. Worth double-checking the failure paths leave
   nothing in memory.

4. **PairingRepository.confirm signature change** — from
   `encryptedInitialState: ByteArray` to `initialStatePlaintext:
   ByteArray`. The caller (PairingScreen) passed a placeholder
   that was never actually encrypted, so the rename + internal
   encrypt is the correct fix. Worth verifying no other caller
   ships a real ciphertext into this slot.

5. **Server GET /v1/pairing/{id}/state response** — exposes the
   encrypted_state blob server-side. The server is content-blind
   (can't decrypt) so this just hands back what it stored. The
   only authorization check is `user_id == ctx.user.id` +
   `revoked_at IS NULL`. Anyone able to authenticate as the user
   already had full access via /v1/state and listPairings, so
   this doesn't widen the attack surface — but worth confirming.

6. **Phase-8d client + pre-Phase-8d server** is broken (above).
   Pre-Phase-8d client + Phase-8d server still works (server
   generates the UUID, client uses server's reply). Asymmetric
   compat acceptable for V0.1.

7. **rotateCompromise should arguably wipe the SessionStore
   token IMMEDIATELY** rather than just setting forceLogout on
   the result. The current design leaves a window where the
   in-memory Session has the new MK but the SessionStore still
   holds the (now-revoked) old token. Next authenticated call
   would 401 and the AuthRepository.onAuthFailure handler would
   logout. Probably fine, but worth thinking about.

## Output

Per reviewer, terse:

1. Verdict on phase 8d: GREEN / YELLOW / RED + items.
2. Verdict on phase 8e: GREEN / YELLOW / RED + items.
3. Anything missing.
4. Anything new.

I'm paused for a restart after firing this consult. The user
will resume from your findings.

**Reply text only. Do NOT call any write/mutation tool. Do not
commit, edit, or stage anything.**
