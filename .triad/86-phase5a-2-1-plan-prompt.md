# Consultation 86 — Phase 5a-2.1 plan (deferred follow-up from 5a-2)

**Protocol reminder — REVIEW ONLY.** Your job is to verdict the
plan + flag missing constraints. Don't write code, don't commit.
Consultation 83 had a self-commit incident; we're being explicit
again.

## Context

Phase 5a-2 (committed `a9fe84e`, dual-GREEN at consult 75) shipped
the **protocol foundation** of V1.5 automated pairing:

- Server: `Sender.bootstrap_key/_signature` columns, new
  `POST /v1/senders/me/bootstrap-key`, `PairingInitiateRequest`
  carries `sender_broker_url`, `PairingPreview` echoes the four
  bootstrap fields.
- Android: `BootstrapEnvelope.build(...)` in `core/crypto/`
  (X25519 + HKDF + AES-GCM, spec §9 byte-equivalent with vectors).
- SDK: `sdk-python/syncler/bootstrap.py` (decrypt + AAD
  reconstruction + replay guard), `broker_storage.py` (Protocol +
  `InMemoryBrokerStorage` with CAS), `Client.register_bootstrap_key`,
  `Client.create_pairing_qr(sender_broker_url=...)`,
  `Client.wait_for_pairing(timeout, poll_interval)` with ±20% jitter.

Three pieces were **explicitly deferred** to 5a-2.1 — the
automated pairing protocol is dead weight until they land. V1.5 DX
shipping without these is a lie.

## The three deferred items

### Item 1 — Android pairing UX (`PairingRepository` + `PairingScreen`)

Current state (post-5a-2): `PairingRepository.kt` (`feature/pairing/`)
implements only the V1 manual flow — preview → user confirms
fingerprint → `confirm(...)` calls `/complete` → server returns
`(user_id, encryptedInitialState)` → app shows them for manual
paste into the sender. No bootstrap envelope is built; the
Phase 5a-2 `BootstrapEnvelope.build(...)` is unused.

### Item 2 — FastAPI broker app

Current state (post-5a-2): no `[broker]` extra in
`sdk-python/pyproject.toml`. Decrypt logic exists in
`bootstrap.py`. Storage Protocol + in-memory impl exist. **No
runnable broker.** Each sender currently has to write their own
HTTP layer to call `decrypt_bootstrap_envelope(...)` — exactly
what the agreement said the SDK would provide.

### Item 3 — `docs/integration-guide.md` automated-pairing walkthrough

Current state: `docs/integration-guide.md` (596 lines) has §1-§11
covering V1 manual pairing only. Zero mentions of "bootstrap",
"broker_url", "wait_for_pairing", or "automated pairing".

## Proposed plan

### Item 1 — Android UX

**Detection:** `PairingRepository.preview(...)` already returns
`PairingPreviewResponseDto`. Extend the DTO to surface the four
new bootstrap fields (`sender_broker_url`, `bootstrap_key`
base64, `bootstrap_key_signature` base64, `bootstrap_protocol_version`).
When all four are non-null AND `bootstrap_protocol_version == 1`,
the screen takes the automated path. Otherwise V1 manual.

**Bootstrap-key signature verification:** After user confirms
fingerprint, verify `bootstrap_key_signature` against the
sender's Ed25519 public key (which the preview already returns,
and which the user just confirmed). The signed input is the
literal bytes `"syncler-v1-bootstrap-key:" || raw_x25519_pub` —
already specced and test-vector'd by Phase 5a-1. If verify fails:
**refuse to proceed, do NOT fall back to manual silently** —
this is a substitution-attack indicator. Show a hard error
banner.

**Flow after signature OK:** continue with the existing
`confirm(...)` path to get `(user_id, pairing_key)` from
`/complete`. THEN build the bootstrap envelope via
`BootstrapEnvelope.build(...)` with the trusted fields from the
preview, and POST it to `sender_broker_url`.

- HTTP 2xx → success. Same local persistence as V1 (write
  `PairedSender` row). Show "Paired automatically" toast.
- HTTP 4xx/5xx/timeout/DNS → fall back: show banner "automatic
  pairing failed; copy these values into your sender manually"
  with `user_id` + `pairing_key_hex` displayed (mirrors V1
  screen). Do NOT delete the local pairing record — automated
  + manual produce the same local state.

**Order rationale:** the existing `/complete` call must happen
before the broker POST so that (a) syncler-side pairing is
finalized atomically per `.triad/70-phase5-agreement.md`
"strict order" rule, and (b) if the broker POST fails, the user
can still complete pairing manually.

**Surface changes:**
- `core/network/PairingPreviewResponseDto.kt`: add 4 nullable
  fields.
- `feature/pairing/PairingRepository.kt`: new
  `attemptBootstrapPost(candidate, preview, pairingKey, userId)`
  method calling `BootstrapEnvelope.build(...)` and a new
  `SynclerApi.postBootstrapEnvelope(brokerUrl, dto)` Retrofit
  call (`@Url` annotation since the URL is sender-supplied).
- `feature/pairing/PairingScreen.kt` + `PairingViewModel.kt`:
  branch UI state on whether bootstrap fields are present;
  drive the success/failure banners.
- Tests: new `PairingRepositoryAutomatedTest.kt` covering
  signature-verify success, signature-verify failure, broker
  POST 2xx, broker POST 5xx, broker timeout.

### Item 2 — FastAPI broker

**New module:** `sdk-python/syncler/broker/__init__.py` +
`sdk-python/syncler/broker/app.py`.

**Optional extra:** `pyproject.toml` adds
```
[project.optional-dependencies]
broker = ["fastapi>=0.110", "uvicorn[standard]>=0.27"]
```
so a vanilla `pip install syncler` doesn't pull FastAPI in.

**App factory:**
```python
def make_broker_app(
    *,
    bootstrap_private_key: X25519PrivateKey,
    bootstrap_public_key_raw: bytes,
    sender_broker_url: str,
    storage: BrokerStorage,
) -> FastAPI:
    ...
```

Single POST endpoint mounted at `/` (sender chooses the public
path via `sender_broker_url`). Body shape mirrors the wire
envelope in spec §9 step 7.

**Handler steps** (mirror spec §9 sender-broker-handler):
1. Parse envelope JSON; validate shape (all 7 fields, base64
   decode succeeds, lengths correct).
2. Look up `pairing_id` in storage. For V1.5 we use the
   fixed-config `sender_broker_url` from app config as the
   trusted state — the agreement permits this for senders
   who don't rotate broker URLs.
3. Call `decrypt_bootstrap_envelope(...)` with the trusted
   `sender_broker_url`.
4. `storage.complete(pairing_id, BrokerEntry(user_id, pairing_key))`.
   - `BrokerStorageConflictError` → return 409.
   - Other `BootstrapDecryptError` → return 401 with no detail
     (avoid leaking which field mismatched — same as message
     decrypt).
5. Return 201 on success.

**No auth on the endpoint.** The agreement explicitly says the
broker authenticates by being able to decrypt — anyone can
POST, only the legit envelope decrypts to anything meaningful.

**Tests:** `sdk-python/tests/test_broker_app.py` using
`TestClient`: success path, expired envelope (401), substituted
broker URL (401), AAD bit-flip (401), replay with different
values (409), idempotent replay with same values (201).

### Item 3 — Integration guide docs

**Placement:** new §8.5 "Automated pairing (V1.5)" after the
current §8 (testing) and before §9 (errors). Rationale: §8
covers the end-to-end manual flow, §8.5 is the V1.5 upgrade on
top of it, both stay close together for the reader.

**Subsections:**
1. **Why automated** — one paragraph, the manual UX problem.
2. **Sender setup** — `register_bootstrap_key()` once,
   `create_pairing_qr(sender_broker_url=...)`,
   `wait_for_pairing()` block in the send loop.
3. **Broker setup** — the `syncler[broker]` extra,
   `make_broker_app(...)` factory, uvicorn invocation,
   production-storage note (Redis/Postgres replacing
   `InMemoryBrokerStorage`).
4. **Android user flow** — what the user sees, when the manual
   fallback banner appears.
5. **Failure modes** — signature mismatch (hard error),
   broker-unreachable (manual fallback), replay (409 in broker
   logs), bootstrap-key rotation guard.
6. **Security boundaries** — the substitution-attack defense,
   why `sender_broker_url` is sourced from trusted state, why
   we use a separate X25519 key.

Also update §1 overview's two-paragraph pairing summary to
mention automated as the V1.5 default path with manual as
fallback. §8 end-to-end testing gets a pointer to §8.5 once the
sender has set up a broker.

## Open questions for reviewers

1. **Item 1 — manual fallback after `/complete` succeeded.** If
   `/complete` returned `(user_id, pairing_key)` but the broker
   POST fails, the sender doesn't know the pairing happened
   until the user types those values in manually. Is this
   acceptable, or should we have the device retry the broker
   POST silently a few times first (e.g. 3 attempts with
   exponential backoff)?

2. **Item 2 — broker URL strategy.** For V1.5 I propose
   per-app-config (single fixed `sender_broker_url`). The agreement
   allows per-pairing URLs but requires extra storage (a map
   `pairing_id → broker_url`). Is the fixed-config approach
   acceptable for V1.5 with per-pairing URLs deferred to V2,
   or should we land both now?

3. **Item 2 — auth.** Truly no auth on the POST endpoint? The
   agreement is explicit (decrypt is the auth), but a 1-line
   rate-limit by IP / pairing_id would defend against a
   DOS-via-failed-decrypts. Should we ship a rate limiter
   even if optional?

4. **Item 3 — section number.** §8.5 vs new §9 (push current
   §9-§11 down by one)? §8.5 is a "third decimal" but keeps the
   manual section number stable. Preference?

## Output

Per reviewer:
1. Per-item: GREEN/YELLOW/RED on the plan.
2. Answers to the four open questions.
3. Anything missing from the plan that the agreement called
   for.
4. Anything new (security, footgun, doc accuracy).

If both GREEN, I implement and fire a code review consultation.
