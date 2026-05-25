=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
=================================================================

# Consultation 98 — Phase 8 plan (V1.5 runtime #3: master-key rotation)

V1.5 ROADMAP item #3: "Master-key rotation. UX + protocol for
rotating the user's 32-byte master key without losing access to
historical state. Required before per-device encryption (#4)
lands."

## Current state (audit)

### What the master key encrypts

The user's 32-byte master key is the root secret for all per-user
symmetric crypto:

1. **Encrypted user state blob** (`encrypted_user_state.encrypted_blob`)
   — synced via CAS on `state_version`. Stores installed plugins,
   dismissed/archived/deleted-message bitmaps, settings,
   muted-senders set, paired-senders list (with per-pairing keys
   inline).
2. **Per-pairing AES keys** — derived per-sender via
   `HKDF(ikm=master_key, salt=sender_id, info="syncler-v1-pairing-key:"+sender_id, length=32)`.
   Used to AES-GCM-encrypt the message body and per-card payloads.
3. **Per-pairing encrypted_state** (`pairings.encrypted_state`)
   — server-stored opaque blob per pairing row. No CAS counter.

### Wrapping + delivery

Master key is wrapped client-side under `master_key_wrap_key`
(Argon2id-derived from password, bytes 32-64 of the 64-byte
derivation). Wrapped blob sits in `users.encrypted_master_key`.
On login the device gets the wrapped blob, unwraps with the
locally-derived wrap key, never sends the master key to the
server.

### Existing rotation hooks

**None.** No route, no schema column, no UX. This is a complete
greenfield protocol design.

### Why this gates #4 (per-device encryption)

Item #4 replaces the shared per-user master key with per-device
keypairs. To migrate from one to the other safely we need a
generic "re-encrypt all dependent state under a new root key"
primitive. Phase 8 builds that primitive.

## Threat model + use cases

Three reasons a user rotates:
- **Periodic hygiene.** No specific compromise; opt-in rotation
  cycle.
- **Password change.** Re-deriving `master_key_wrap_key` from a
  new password means re-wrapping the master key. The master key
  *itself* doesn't have to change for this. Simpler operation.
- **Compromise.** Master key exposed (e.g. device-loss with no
  PIN, or shoulder-surf of password). New master key + new wrap
  key + re-encrypt everything.

Phase 8 should support all three. Password-change is a strict
subset of compromise rotation (skip the master-key regenerate
step, skip the data re-encryption).

## Design alternatives

### Option A — Hot rotation via key-generation counter

Schema:
- `users.key_generation: int default 1`. Bumps to 2 on rotation.
- `encrypted_user_state` gains `key_generation int`. Old blobs at
  `key_generation=1` co-exist with new blobs at `key_generation=2`
  during the rotation window.
- `pairings` gains `key_generation int`. Each row carries the
  generation it was last encrypted with.

Flow:
1. Initiating device generates new master key MK', new wrap key
   WK' (from new or unchanged password).
2. Device decrypts all data under MK, re-encrypts under MK',
   posts atomic rotation request:
   - new `encrypted_master_key` (wrapped under WK')
   - new `encrypted_user_state.encrypted_blob` (encrypted under
     MK'), `state_version` bumped, `key_generation` bumped.
   - Each `pairings.encrypted_state` re-encrypted under MK'
     (well, under the new per-pairing key derived from MK').
3. Server commits atomically in one transaction (rotation row
   stamp + new blobs).
4. Other devices: poll detects `key_generation` change → forced
   re-login (or auto-pull new `encrypted_master_key` if session
   still valid) → unwrap with WK' → decrypt new blobs.

Pros: clean break, no version juggling at read time.
Cons: rotation window where device B has the old key fails CAS
hard; needs UX banner "your master key was rotated, re-login".
Pros2: simple to reason about; no multi-key-decrypt fallback
needed.

### Option B — Dual-key transition window

Server stores TWO encrypted_master_keys (current + previous)
during a rotation grace period. Blobs are *signed* with
key_generation. Devices try newest first, fall back to older.
Old generation removed after all devices acknowledge.

Pros: offline devices don't break — they decrypt with old key
once, then upgrade on next online sync.
Cons: meaningful storage doubling during the window; "all
devices acknowledged" is fuzzy (devices may be permanently
offline). More moving parts.

### Option C — Versioned blobs, forward-only

Each blob carries `key_generation`. Devices keep ALL master keys
they've ever held (encrypted under current wrap key). Decrypt
tries newest first, falls back as needed. Old data stays
encrypted under old keys forever — never re-encrypts.

Pros: no re-encryption cost (rotation just generates new key for
new writes).
Cons: defeats the purpose if the OLD key was the one compromised
— historical data stays vulnerable.

**My recommendation: Option A.** Compromise scenario forces full
re-encryption anyway. The "offline device breaks" cost is a
re-login prompt, which is the right UX for compromise rotation
(you WANT to invalidate all sessions). For periodic hygiene the
same re-login is fine.

## Phase scoping

I lean **one Phase 8 commit** covering:
- Server schema migration (10) — new columns + atomic rotation
  endpoint.
- Server rotation route + service layer.
- Android UX — Settings → "Rotate master key" + a separate
  "Change password" entry that takes the password-only fast
  path.
- SDK (Python) — no changes; senders don't see this directly.
- Docs (crypto-spec §3 or new §10, integration-guide if
  user-facing).

Hard to triad-cycle a single huge commit. Alternative: split into
**8a (protocol + spec)** and **8b (server + Android + UX)**,
same shape as Phase 5a-1 vs 5a-2.

I lean **split into 8a / 8b** given the protocol gravity.

## Specific open questions

1. **Option A vs B vs C** — confirm A is right, or argue for one
   of the others.
2. **Phase scoping** — single commit vs 8a (spec) + 8b (impl)?
3. **Pairing.encrypted_state CAS** — should pairings gain a
   `state_version` column too (currently they don't)? Without
   one, concurrent rotation + active pairing update could race.
4. **What about per-sender HKDF-derived pairing keys?** They're
   computed on-device from `(master_key, sender_id)`. After
   rotation the SAME `sender_id` still derives a *different* key.
   Senders don't know — they re-encrypt against the unchanged
   pairing-key they were given at pairing time. So actually we
   need to bake the pairing key into the rotation: NEW pairing
   key = HKDF(NEW master_key, sender_id, ...). Device tells the
   sender about the new pairing key via... what mechanism?
   Re-pair from scratch? A new envelope on a "pairing rotation"
   endpoint? This is the hardest part of the design.

   Actually wait — looking at the audit again: per-pairing AES
   keys are stored in the synced user-state blob, derived BY THE
   DEVICE from master_key + sender_id at first pair. The SENDER
   already received the pairing key during the bootstrap
   envelope (`{user_id, pairing_key}` plaintext over X25519).
   So the sender's copy is independent — rotation of master key
   doesn't break the sender. The DEVICE just needs to keep
   decrypting messages with the OLD pairing keys (which were
   sent to the sender pre-rotation and won't change).

   **So: master-key rotation should NOT regenerate pairing
   keys.** It should only re-encrypt the user-state blob (which
   contains the pairing keys themselves as data, but the keys
   themselves are unchanged values).

   Confirm this is right and I'm not missing a derivation path?

5. **Compromise rotation revokes sessions?** Should the rotation
   route automatically revoke all device sessions except the
   initiating one, so a stolen-session attacker is locked out?
   I lean yes for compromise; no for periodic hygiene + password
   change.

## Output

Per reviewer:
1. GREEN / YELLOW / RED on the overall plan direction.
2. Answers to the 5 open questions.
3. Anything missing.
4. Anything new (security, footgun, attacker model gap).

If dual-GREEN, I write a more detailed spec and fire 8a
(protocol) before implementation.

Reply text only. Do NOT call any write/mutation tool.
