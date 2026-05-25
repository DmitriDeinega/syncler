# Consultation 88 — Phase 5a-2.1 plan, final pass (post-87)

**Protocol — REVIEW ONLY.**

Consultation 87 voted: **Gemini GREEN, Codex YELLOW** with
concrete tightening. This pass folds Codex 87 fully in.

## Item 1 — Android UX (final)

### Persistence model (Codex 87 RED)

The Syncler-side pairing is finalized at `/complete`. Local
`PairedSender` MUST be persisted at that point regardless of
broker POST outcome. The broker POST is a sender-catch-up signal
only — its failure does not unmake the device's pairing.

Concrete order:
1. Generate `pairing_key` locally (32 random bytes).
2. `/complete` → identity-match check → **persist `PairedSender`
   immediately**.
3. Verify `bootstrap_key_signature`. If verify fails → show
   hard-error banner (per consult 86); pairing record stays
   persisted because syncler-side is real.
4. Build envelope.
5. POST to broker.
6. UI state on POST:
   - `BootstrapSucceeded` → "Paired automatically; sender ready."
   - `BootstrapFailedFallback` → show fallback banner with
     `user_id` + `pairing_key_hex` for manual paste; persisted
     record unaffected.

### Retry strategy (Codex 87)
Retry only transient: network failures, DNS, timeout, HTTP 5xx,
HTTP 429 (with `Retry-After` if present, else default backoff).
Terminal-and-go-straight-to-fallback: 400, 401, 409, any other
4xx. Cap at 3 attempts; backoff 250ms / 750ms / 2s.

### Metadata pre-validation (Codex 87)
Before `/complete`, validate (hard-error if any fail):
- `bootstrap_key` base64 decodes to exactly 32 bytes.
- `bootstrap_key_signature` base64 decodes to exactly 64 bytes.
- `sender_broker_url` present, non-empty, parseable as URL,
  passes the existing scheme check (release HTTPS-only; debug
  allows HTTP-LAN).
- `bootstrap_protocol_version == 1`.

Test that partial automated metadata causes hard refusal BEFORE
`/complete` (so syncler-side is not consumed on bad metadata).

### Auth-header isolation + zero-logging (Codex 87 tightening)
`BrokerHttpClient`: plain `OkHttpClient.Builder()` with NO
interceptors, NOT cloning from the main client. Explicit test:
the broker request has no `Authorization` header AND no other
interceptor-injected headers (e.g. no logging interceptor).

### Wire DTO — snake_case JSON (Codex 87 RED)

Kotlin DTO uses Moshi `@Json(name = "...")` on every field so
the wire format matches the Python side exactly:

```kotlin
data class BootstrapEnvelopeDto(
    @Json(name = "protocol_version") val protocolVersion: Int,
    @Json(name = "pairing_id") val pairingId: String,
    @Json(name = "sender_id") val senderId: String,
    @Json(name = "bootstrap_key_id") val bootstrapKeyId: String,
    @Json(name = "exp") val exp: String,
    @Json(name = "ephemeral_pubkey") val ephemeralPubkey: String,
    @Json(name = "nonce") val nonce: String,
    @Json(name = "ciphertext") val ciphertext: String,
)
```

Cross-language byte-equivalence test: a fixed JSON sample
deserializes on both sides with identical field values.

### UI states
Same as consult 87: add `BootstrapPosting`, `BootstrapSucceeded`,
`BootstrapFailedFallback`. Hard-error state for signature failure
or malformed metadata: `BootstrapHardError`.

## Item 2 — FastAPI broker (final)

### Honest security framing (Codex 87 RED)

The V1.5 fixed-config broker **cannot reject unknown
pairing_ids**. An attacker with the public bootstrap key can
mint a cryptographically valid envelope for any pairing_id
(they pick the AAD, AEAD doesn't authenticate the pairing_id
came from a real `/initiate`). AEAD only prevents tampering of
existing envelopes.

V1.5 mitigations:
- UUID entropy (pairing_ids are uuid4, ~122 bits) — attacker
  can't enumerate.
- Mandatory rate limiting via the `rate_limiter` hook in
  production (we ship the hook + recommend an implementation).
- Per-pairing-id pending registry (Postgres / Redis) explicitly
  documented as V2.

This is precisely worded in:
- `sdk-python/syncler/broker/app.py` doc-string.
- `sdk-python/syncler/broker_storage.py` doc-string.
- `docs/crypto-spec.md §9.3` (Codex 87 RED — must update spec
  since current text says "MUST use pairing_id-indexed trusted
  state").
- `docs/integration-guide.md §8.5` security-callout block.

### App factory

```python
def make_app(
    *,
    bootstrap_private_key: X25519PrivateKey,
    bootstrap_public_key_raw: bytes,
    sender_broker_url: str,
    storage: BrokerStorage,
    rate_limiter: Callable[[Request], Awaitable[None]] | None = None,
) -> FastAPI: ...
```

### Rate limiter contract (Codex 87)

`rate_limiter` is awaited per request. If it raises:
- `HTTPException` → propagates as-is (so it can return 429 with
  Retry-After).
- Any other exception → FastAPI's default exception handler
  returns 500. NOT silently converted to 200/204.

Documented in the factory's doc-string.

### Storage Protocol change

`BrokerStorage.complete(pairing_id, entry) -> bool` (was None).
Returns `True` if this was the first completion; `False` on
idempotent same-values replay; raises `BrokerStorageConflictError`
on different-values replay (unchanged).

`InMemoryBrokerStorage.complete` updated to match. Existing
callers (none yet besides V1.5 tests) get the new return value.

### Handler — final shape

1. `await rate_limiter(request)` if configured.
2. Parse JSON body → reject 400 if invalid.
3. Validate shape: 8 fields present, types correct.
4. Validate base64 lengths BEFORE decrypt:
   - `ephemeral_pubkey` → 32 bytes
   - `nonce` → 12 bytes
   - `bootstrap_key_id` → 16 bytes
   - `ciphertext` → ≥ 16 bytes (AEAD tag is 16)
   Any mismatch → 400.
5. `decrypt_bootstrap_envelope(...)` with trusted
   `sender_broker_url` from app config.
6. `was_first = storage.complete(pairing_id, BrokerEntry(...))`
7. Status:
   - `was_first` → **201**
   - idempotent (was_first False) → **200**
   - `BrokerStorageConflictError` → **409**
   - `BootstrapDecryptError` → **401** (opaque, no detail)

### Tests

`sdk-python/tests/test_broker_app.py`:
- 201 on happy path; storage populated.
- 200 on replay with same values; storage unchanged.
- 409 on replay with different values.
- 401 on expired envelope (no detail).
- 401 on substituted sender_broker_url in AAD (no detail).
- 401 on AEAD tag flip (no detail).
- 400 on each of the 4 base64-length checks.
- 400 on missing field.
- Rate limiter raising `HTTPException(429)` → 429 with body.
- Rate limiter raising `RuntimeError` → 500.

## Item 3 — Docs (final)

### `docs/integration-guide.md` §8.5 — Automated pairing (V1.5)

Sections as per consult 87, plus:
- **Security callout** subsection: the fixed-config-broker
  limitation, mitigations (UUID entropy, rate limiter, V2
  pending registry), and a "Production hardening" checklist
  (rate limiter mandatory; storage backed by Redis/Postgres;
  TLS termination; multi-worker safety).

### `docs/crypto-spec.md §9.3` — fixed-config deviation note (Codex 87 RED)

New paragraph at the end of §9.3 explaining: the V1.5 broker
implementation in `syncler[broker]` ships with single-fixed
`sender_broker_url`. This satisfies the AAD-binding rule (the
trusted state IS the configured URL, byte-equal to what the
sender signed) but DOES NOT satisfy "reject unknown pairing_ids"
without an external pending-pairing registry. V2 will add that
registry as a first-class storage method.

### `docs/integration-guide.md §1` — one-paragraph overview update

Mention automated pairing as V1.5 default, manual as
fallback/older flow. Point to §8.5.

## Items accepted from consultation 87

| Source | Refinement | In final plan |
|---|---|---|
| Codex 87 | Persistence after `/complete` is unconditional | ✓ Item 1 |
| Codex 87 | Don't retry 4xx (terminal); retry network/5xx/429 | ✓ Item 1 |
| Codex 87 | Pre-`/complete` metadata validation + test | ✓ Item 1 |
| Codex 87 | No logging/interceptors on broker client | ✓ Item 1 |
| Codex 87 | Snake_case wire JSON; Moshi @Json annotations | ✓ Item 1 |
| Codex 87 | Honest framing of fixed-config security | ✓ Item 2 |
| Codex 87 | Rate limiter exception policy | ✓ Item 2 |
| Codex 87 | Update crypto-spec.md §9.3 | ✓ Item 3 |
| Gemini 87 | 401 opaque (no field detail) | ✓ Item 2 |
| Gemini 87 | Retry only transient (not 401/409) | ✓ Item 1 |

## Output

Per reviewer:
1. Per-item: GREEN / YELLOW / RED.
2. Anything still missing.
3. Anything new.

If dual-GREEN, I implement and fire a code-review consultation.
