# Consultation 74 — Phase 5a-2 plan revised, post-Codex pushback

Consultation 73 returned:
- **Gemini**: all-GREEN.
- **Codex**: HOLD/YELLOW with 4 concrete blockers + 2 useful
  Gemini additions.

Codex's 4 are all real — I verified each against the actual code.
Plan revisions below; this consultation is a tight confirmation
before any code lands.

## Plan deltas applied

### Delta 1 — Naming collision: `sender_broker_url` (not `broker_url`)

`PairingInitiateResponse.broker_url` ALREADY EXISTS at
[schemas.py:313](D:/Projects/syncler/server/app/schemas.py:313)
and means "the Syncler-side broker URL that the QR encodes for
the device to scan." Reusing the name for the sender's
bootstrap-POST URL would silently overload existing wire
contract.

**New field name throughout 5a-2: `sender_broker_url`.**
- `PairingInitiateRequest.sender_broker_url: str | None = None`
  (signed in the canonical envelope when set)
- `PairingInitiateResponse.sender_broker_url: str | None = None`
  (echoed)
- `PairingPreviewResponse.sender_broker_url: str | None = None`
- `PendingPairing.sender_broker_url: Mapped[str | None]` (new
  nullable column)
- AAD field `sender_broker_url` (not `broker_url`) — **note: this
  is a spec change against §9 of `docs/crypto-spec.md`. Spec
  edit required before code.**
- Python SDK: `client.create_pairing_qr(..., sender_broker_url=...)`

Same rename in the Android wire DTO, the SDK
`assemble_bootstrap_aad(...)` helper, the §9 test vector
(AAD JSON byte string changes because the key name changed,
which means the AEAD ciphertext vector and the canonical AAD
vector will need regeneration before commit).

### Delta 2 — `/v1/pairing/complete` preserved and ordered correctly

The broker POST delivers `(user_id, pairing_key)` to the sender,
but `/v1/pairing/complete` is still what creates the Syncler-side
`Pairing` row and returns the `PairingCompleteResponse` fields
the device needs to populate its local `PairedSender`.

**Android post-confirm flow (revised order)**:

1. Generate local `pairingKey` (32 bytes via SecureRandom).
2. POST `/v1/pairing/complete` (existing path); receive
   `PairingCompleteResponse` with the canonical sender identity.
3. Verify the complete response sender identity matches the
   preview the user just confirmed (sanity check).
4. If preview carried `sender_broker_url` + `bootstrap_key` +
   `bootstrap_key_signature`:
   - Verify the bootstrap key signature against the sender's
     Ed25519 public key (fail loudly on mismatch — treat as
     syncler-side substitution attack).
   - Build the bootstrap envelope.
   - POST to `sender_broker_url`.
5. Persist `PairedSender` locally (via existing
   `pairedSenderStore.add`).
6. If step 4's POST fails: keep the local pairing (it's
   already valid — the user is paired) but surface the manual
   fallback banner so the sender's backend can be unblocked
   with a copy-paste.

**Key invariant**: Syncler `/v1/pairing/complete` MUST succeed
before the sender broker POST happens. Otherwise the sender
thinks pairing exists while Syncler rejected it.

### Delta 3 — `PendingPairing` is the storage row

Plan originally said "pairings row" — code has
`PendingPairing` at
[models.py:239](D:/Projects/syncler/server/app/models.py:239)
for the initiate/preview state. Final `Pairing` row at
[models.py:60](D:/Projects/syncler/server/app/models.py:60) is
created at `/complete` time.

**`sender_broker_url` lives on `PendingPairing` only.** Not
copied to the final `Pairing` row. Audit log retains the
PendingPairing trail; the permanent Pairing row doesn't need
it (the bootstrap POST has already happened by then).

Migration `0008_pending_pairing_sender_broker.py`: add nullable
`sender_broker_url: Text` to `pending_pairings`.

Sender bootstrap-key columns stay on `senders` as planned
(nullable until V1.5 sender adopts).

### Delta 4 — FastAPI broker app is an optional SDK extra

Base `syncler` SDK stays FastAPI-free. The `make_broker_app(...)`
helper ships behind:

```toml
# sdk-python/pyproject.toml
[project.optional-dependencies]
broker = ["fastapi>=0.115", "uvicorn>=0.30"]
```

Install: `pip install syncler[broker]`.

`syncler/bootstrap.py` (decrypt helper) + `syncler/broker_storage.py`
(`BrokerStorage` protocol + `InMemoryBrokerStorage`) stay in the
base package — they're pure logic. Only the FastAPI mounting
helper goes behind the extra.

If broker extras isn't installed, `from syncler.broker import make_broker_app`
raises `ImportError` with a pointer to the extras install line.

### Delta 5 — Rate-limit `/v1/senders/me/bootstrap-key` (Gemini)

Add to `server/app/middleware/rate_limit_config.py`:
```python
"bootstrap_key_register": RateLimitConfig(
    name="bootstrap_key_register",
    max_count=5,
    window_seconds=300,  # 5/5min per sender — rotation isn't hot
),
```

Apply via `Depends(rate_limit("bootstrap_key_register"))` on the
new route. Use per-sender bucket (the route is sender-signed so
the `sender_id` is verified before the rate limit applies).

### Delta 6 — Android broker POST timeout (Gemini)

`OkHttpClient` used for the bootstrap POST gets explicit:
- `connectTimeout(10, SECONDS)`
- `readTimeout(10, SECONDS)`
- `callTimeout(15, SECONDS)` — total hard cap so the manual
  fallback surfaces within ~15s on broker unresponsiveness.

Mirrors the `TemplateActionRunner` pattern (its own unauthenticated
client built locally, not the singleton with the auth interceptor).

### Other small things

- Crypto spec §9 needs ONE field-name change: `broker_url` →
  `sender_broker_url` in §9.3 AAD JSON example and the §9.4
  AAD-byte vector. The test-vector ciphertext changes
  consequently. New vector values will be regenerated before
  any code lands.
- §9.1 Ed25519 signature vector is unaffected (signs over the
  X25519 raw key, not any URL).
- §9.2 HPKE derivation vector is unaffected.

## What I need from each reviewer

1. **Delta 1 (rename)**: agree `sender_broker_url` is the right
   name? Push back if you prefer something tighter (e.g.
   `bootstrap_post_url`, `sender_bootstrap_url`).
2. **Delta 2 (operation order)**: pairing/complete-before-broker-
   POST is correct? Any other ordering invariants I missed?
3. **Delta 3 (PendingPairing storage)**: confirmed correct
   location?
4. **Delta 4 (optional FastAPI extra)**: extras-pattern OK, or
   should the broker app be in a separate `syncler-broker`
   package entirely?
5. **Delta 5/6**: rate limit threshold (5/5min) + Android
   timeouts (10s/10s/15s) reasonable?
6. **Vector regeneration**: §9 AAD vector changes because of the
   rename. Plan: regenerate locally with the canonical Python
   reference + assert in `test_crypto.py` + paste into §9 + sync
   the Android `SpecVectorsTest.kt` constants. No protocol logic
   change. OK?

## Output

Per reviewer:
1. Per-delta verdict: GREEN / YELLOW / RED with reasoning if
   non-GREEN.
2. Anything I missed.
3. Overall: ready to start coding / specific blockers / hold.

If both reviewers GREEN, I update §9 with the rename + regen
vectors as the first step, then proceed through Server →
Android → SDK in order. Post-impl review fires as consultation
75.
