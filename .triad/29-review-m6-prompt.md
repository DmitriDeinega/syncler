# Review of M6 (Pairing + revocation)

Build still in "Claude builds, others review" protocol. M6 just landed across two commits:

- `919c8f0` M6 (server): pairing + revocation (broker model)
- `d4fd98c` M6 (android): pairing UI + EncryptedPairedSenderStore (I4)

## What landed

### Server
- `services/pairing.py` — token generation, fingerprint (base32 of SHA-256 prefix), initiate/complete/revoke flow
- `routers/pairing.py` — POST /initiate (sender Ed25519-signed), POST /complete (JWT auth), POST /{id}/revoke, GET / (list)
- New table `pending_pairings` + migration 0002
- Schemas + tests covering happy path, bad signature 401, expired token 410, list+revoke round-trip

### Android
- `:core:storage/PairedSenderStore.kt` — EncryptedSharedPreferences-backed PairedSender records (locks identity tuple)
- `:core:network/SynclerApi.kt` — pairing endpoints added
- `:feature:pairing` (new module) — Repository + ViewModel + PairingScreen with broker-URL paste + fingerprint confirmation dialog
- QR camera scanning deferred to M11 (URL paste exercises same protocol)

## Specifically pressure-test

1. **Token uniqueness** — `pending_pairings.pairing_token` has UNIQUE constraint, but generate_pairing_token uses 32 random bytes via secrets.token_bytes — collision risk effectively zero, but is there any race in initiate where two concurrent requests might produce the same token? (None expected since INSERT will fail on UNIQUE conflict.)

2. **TTL handling** — `complete_pairing` checks `pending.expires_at <= now`. Timezone awareness same as M5.2 — is `expires_at` always stored timezone-aware? (Migration uses `TIMESTAMPTZ`, model uses `DateTime(timezone=True)` — should be fine.)

3. **Anti-spoofing layers** — fingerprint format is base32(SHA-256(pk)[0:8]) in 4-char hyphen groups. Is 64 bits of identity material enough? (Birthday bound ~2^32, fine for "this is the same sender I paired with" not "global hash collision resistance".)

4. **Initiate envelope** — sender signs `{sender_id, ttl_seconds, metadata}`. Could a different sender's signature be replayed against the same envelope? `get_active_sender(payload.sender_id)` returns the named sender's public key for verification, so signature must match that sender's private key. ✓

5. **Encrypted initial state** — Android sends a placeholder bytes "syncler-pairing-bootstrap-v1". Acceptable for V1 (M7 wires the real pairing-key-derivation handshake). Confirm this isn't a real security issue today.

6. **Race: pairing already exists** — `complete_pairing` raises `PairingAlreadyExistsError` if a non-revoked pairing exists for (user, sender). Re-pairing requires user to revoke first. UX-wise reasonable.

7. **PairedSenderStore.encodeRecord uses `|` as separator** — same pattern as messages.encrypted_body_pointer. Base64 alphabet doesn't include `|`. ✓

8. **/initiate broker_url** — built from `request.base_url`. Could a malicious proxy forge the base URL via X-Forwarded-Host? Yes if proxy isn't trusted; same caveat as the client-IP detection in rate_limit.py. Document but not a V1 fix.

9. **/pairing/complete is JWT-authed but accepts any pairing_token** — meaning any logged-in user could try every token and complete pairings they shouldn't have access to. Mitigation: token is 32 random bytes; brute-force infeasible in TTL window (5 min default, 15 min max).

10. **Android URL parsing** — `parseBrokerUrl` accepts only https://. Good. Token is extracted from `?token=` query param. Verified no path traversal or non-URL injection.

## Output

Same shape as the M5.1/M5.2 reviews. Be thorough — pairing is critical for I4 anti-spoof which the user explicitly flagged as a must-fix.

```
=== AGREEMENTS WITH CONCERNS ===
<per-item 1-10>

=== ADDITIONAL FINDINGS ===
- <severity> <title>
  evidence: <file:line>
  proposal: <fix>

=== SHOW-STOPPERS FOR M7 ===

=== READY FOR M7? ===
```
