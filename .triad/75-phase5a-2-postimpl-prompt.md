# Consultation 75 — Phase 5a-2 post-impl review

Phase 5a-2 implementation is partially landed. This consultation
asks the triad to:
1. Validate what shipped against the agreement
   (`.triad/70-phase5-agreement.md`) and the impl plan
   (`.triad/74-phase5a-2-impl-plan-revised-prompt.md`).
2. Decide whether the deferred items can land as a follow-up
   commit (5a-2.1) or block the 5a-2 commit.

## What landed

### Spec rename + vector regeneration
- `docs/crypto-spec.md §9.3`: `broker_url` → `sender_broker_url`,
  alphabetical key order updated, security-rule paragraph
  rewritten to reflect the new field name. Added a paragraph
  explaining why the rename was necessary (collision with the
  existing `PairingInitiateResponse.broker_url`).
- `docs/crypto-spec.md §9.4`: regenerated AAD JSON byte string +
  ciphertext for the new key set. New ciphertext hex computed by
  running `AESGCM.encrypt` against the renamed AAD. Verified the
  resulting bytes against an independent Python encode.
- `server/tests/test_crypto.py`: `BOOTSTRAP_AAD_JSON` +
  `BOOTSTRAP_CIPHERTEXT_HEX` updated; `test_bootstrap_v1_5_vectors`
  asserts the round-trip locally (needs Postgres for the rest of
  the test_crypto module to run — Postgres-less invocation: each
  vector function is pure).
- `android/core/crypto/.../SpecVectorsTest.kt`: same constants
  updated; new assertion that
  `BootstrapEnvelope.buildBootstrapAadJson(...)` produces the
  byte-identical AAD. Test passes locally
  (`./gradlew :core:crypto:testDebugUnitTest --tests "*Spec*"`).

### Server
- `server/app/models.py`:
  - `Sender.bootstrap_key: LargeBinary | None`
  - `Sender.bootstrap_key_signature: LargeBinary | None`
  - `PendingPairing.sender_broker_url: Text | None`
- `server/alembic/versions/0008_sender_bootstrap_key_and_pending_pairing_broker.py`:
  3 ADD COLUMN, all nullable, down_revision = `0007_live_cards`.
- `server/app/schemas.py`:
  - `PairingInitiateRequest.sender_broker_url` (optional)
  - `PairingInitiateResponse.sender_broker_url` +
    `bootstrap_protocol_version` (both optional)
  - `PairingPreviewResponse.sender_broker_url` + `bootstrap_key` +
    `bootstrap_key_signature` + `bootstrap_protocol_version` (all
    optional)
  - New `BootstrapKeyRegisterRequest` / `BootstrapKeyRegisterResponse`
- `server/app/routers/senders.py`: new endpoint
  `POST /v1/senders/me/bootstrap-key` implementing per-Codex-74
  pattern (pre-auth IP bucket → signature verify → post-auth
  per-sender rate limit). Returns `bootstrap_key_id` (base64
  SHA-256(pub)[:16]).
- `server/app/routers/pairing.py`:
  - `_initiate_envelope_bytes` now conditionally includes
    `sender_broker_url` so V1 senders' canonical envelope shape
    stays unchanged.
  - `_validate_sender_broker_url` helper: HTTPS in release,
    private-LAN http in debug, no credentials, no fragment, ≤2048
    chars.
  - `initiate` handler: validates broker URL shape AND rejects
    with 400 if the sender doesn't have a registered bootstrap
    key (Codex-74 requirement).
  - `preview` handler: surfaces broker fields when present on the
    PendingPairing AND the sender has a registered bootstrap key.
- `server/app/services/pairing.py`: `initiate_pairing` accepts
  `sender_broker_url` and persists on `PendingPairing`.
- `server/app/middleware/rate_limit_config.py`: new
  `bootstrap_key_register` bucket (5/5min per sender).
- `server/app/middleware/rate_limit.py`:
  `bootstrap_key_register` added to the sender-bucket set in
  `_identify_actor(...)`.
- Server imports clean
  (`from app.routers import pairing, senders; from app.services
  import pairing as sp; print('ok')`).

### Android
- `android/core/crypto/.../BootstrapEnvelope.kt` (NEW):
  HPKE-style envelope builder. Generates ephemeral X25519,
  ECDHs against sender bootstrap pub, HKDF-derives AES key,
  encrypts with AES-256-GCM, returns the wire fields. Uses
  BouncyCastle X25519 + the existing `Hkdf.deriveSha256` and
  `canonicalJsonBytes` helpers.
- `android/core/crypto/.../SpecVectorsTest.kt` extended:
  asserts `BootstrapEnvelope.buildBootstrapAadJson` produces the
  byte-identical canonical AAD. Test green.
- Android build green
  (`./gradlew :core:crypto:testDebugUnitTest`).

### SDK
- `sdk-python/syncler/bootstrap.py` (NEW): canonical AAD builder,
  `bootstrap_key_id`, `decrypt_bootstrap_envelope` (with
  ±5min `exp` window check and the substitution-attack guard via
  trusted-state `sender_broker_url`), `x25519_keypair_pem`,
  `load_x25519_private_key_from_raw`.
- `sdk-python/syncler/broker_storage.py` (NEW): `BrokerStorage`
  protocol, `BrokerEntry`, `BrokerStorageConflictError`,
  `InMemoryBrokerStorage` with thread-safe CAS semantics.
- `sdk-python/syncler/client.py`:
  - `Client.__init__(broker_storage=None)`
  - `Client.create_pairing_qr(sender_broker_url=None)` —
    forwards the URL to `/v1/pairing/initiate`, reserves the
    broker storage slot
  - `Client.register_bootstrap_key(bootstrap_public_key_raw)` —
    signs the prefix+raw-pub string per spec §9.1, POSTs to
    `/v1/senders/me/bootstrap-key`, returns `bootstrap_key_id`
  - `Client.wait_for_pairing(timeout_seconds=120,
    poll_interval_seconds=1.0)` — polls broker storage with
    ±20% jitter, auto-calls `set_pairing` on success
- `sdk-python/tests/test_bootstrap.py` (NEW): 5 tests, all
  passing:
  - `test_bootstrap_aad_canonical_bytes_match_spec_vector`
  - `test_bootstrap_round_trip`
  - `test_bootstrap_decrypt_rejects_substituted_broker_url`
    (substitution-attack regression guard)
  - `test_bootstrap_decrypt_rejects_stale_exp`
  - `test_in_memory_broker_storage_cas`

## What's DEFERRED (would block 5a-2 commit by strict reading)

1. **Android `PairingRepository.complete(...)` UX flow.** The
   crypto module (`BootstrapEnvelope`) is in. The repository
   change to:
   - read `broker_url` + `bootstrap_key` + `bootstrap_key_signature`
     from the preview response,
   - verify the signature,
   - build the envelope,
   - POST to `sender_broker_url`,
   - show the manual-fallback banner on POST failure,
   is NOT in. The screen flow stays at the V1 manual code today.

2. **Server tests** for the new endpoint and the modified
   pairing routes (the 4 cases plan lists: bootstrap-key
   register success, bad signature, revoked sender, rotate).

3. **Optional FastAPI broker app** under `syncler[broker]` extra.
   `BrokerStorage` + `decrypt_bootstrap_envelope` ship in the
   base package; an `examples`-grade FastAPI mounting helper is
   not in. Production users can wire their own broker handler in
   ~30 lines using the helpers that are shipped.

4. **Integration-guide update** describing the automated flow.
   Spec is in `crypto-spec.md` §9 already; the user-facing
   walkthrough in `integration-guide.md` isn't updated yet.

## Question for the triad

Two options to consider:

**Option A**: Land Phase 5a-2 as a partial commit now, with
- the spec + vector rename,
- the server endpoint + schema/migration,
- the Android crypto module,
- the SDK crypto + broker storage + Client additions,
- the SDK tests.

Then track the 4 deferred items as Phase 5a-2.1 (small follow-up
commit). The full automated flow won't be END-TO-END exercisable
on a real Android device until 5a-2.1 lands, but the protocol
contract is locked and unit-tested.

**Option B**: Block the 5a-2 commit until the Android UX
landing lands. Means the next commit ships a complete end-to-end
flow.

I lean Option A — the crypto-critical parts are tested at the
bottom and the wire contract is locked. The UX work is plumbing
that won't move byte semantics. But the strict reading of the
protocol favors Option B.

## What I need from each reviewer

1. **Validate what landed**. Per-area: GREEN / YELLOW / RED
   with file:line cites where appropriate.
2. **Option A vs Option B vote.** With reasoning.
3. **If you vote Option A**, what's the right commit message
   framing (the 4 deferreds explicitly called out as 5a-2.1
   backlog)?
4. **If you vote Option B**, what's the minimum-viable Android
   UX shape that has to land before commit?
5. **Anything else** that should be added/changed before commit.

## Output

Per reviewer:
1. Per-area verdict + Option vote.
2. Specific blockers if any.
3. Overall: commit now (Option A) / hold for UX (Option B) /
   commit after small fix list.
