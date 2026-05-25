# Consultation 87 — Phase 5a-2.1 revised plan (post-86)

**Protocol reminder — REVIEW ONLY.** Verdict + flag missing
constraints. No code, no commit.

Consultation 86 voted: **Gemini GREEN, Codex YELLOW** with
substantive refinements. This revision folds them in.

## Item 1 — Android UX (revised)

### Detection
- All four bootstrap fields present + `bootstrap_protocol_version == 1`
  → automated path.
- All four absent → V1 manual path (no behavior change).
- **Partial / malformed / unknown protocol_version → HARD error.**
  Show "automated pairing metadata incomplete — refusing to proceed"
  banner and refuse to write a paired record. No silent fallback —
  this is a substitution-attack indicator.

### Auth-header isolation (Codex 86 RED constraint)
The broker POST goes to a **sender-controlled URL**. The existing
`SynclerApi`/OkHttp client has an auth interceptor that attaches
the user's bearer token to every outbound request. If we reused
that client (even with Retrofit `@Url`), the bearer would leak.

Plan: introduce a SEPARATE unauthenticated `BrokerHttpClient` —
plain OkHttp instance with no interceptors, used only for the
broker POST. Add a Robolectric test that asserts the request has
no `Authorization` header.

### `/complete` field correction (Codex 86 RED constraint)
The Phase 5a-2 plan and consultation 86 incorrectly described
`/complete` as returning `(user_id, pairing_key)`. It does not.
The actual flow is:
- Android generates `pairing_key` locally (32 random bytes)
  before `/complete`.
- `user_id` comes from `Session.currentUserId()` — already known
  to the device since the user is signed in.
- `/complete` returns server identity metadata only.

So the bootstrap envelope's plaintext `{user_id, pairing_key}` is
built from these LOCAL sources, not from anything `/complete`
returns.

### Bootstrap-key signature verification
After user confirms fingerprint and BEFORE building the envelope,
verify `bootstrap_key_signature` against the preview-returned
sender Ed25519 pub key, over input
`b"syncler-v1-bootstrap-key:" || raw_x25519_pub`. Fail = hard
error (no fallback). The Phase 5a-2 Ed25519 verifier already
exists.

### Build + POST order
1. Generate `pairing_key = 32 random bytes` locally (same as V1).
2. Call `/complete` to finalize syncler-side pairing.
3. Verify identity-match assertion (already in repo).
4. Verify `bootstrap_key_signature`.
5. Build envelope: `BootstrapEnvelope.build(senderBootstrapPub,
   pairingId, senderId, senderBrokerUrl, expIso=now+60s,
   plaintext=canonical {user_id, pairing_key b64})`.
6. POST envelope to `sender_broker_url` via the unauthenticated
   `BrokerHttpClient`.
7. **On HTTP 2xx → success path.** Persist `PairedSender`
   locally, show "Paired automatically" success card.
8. **On HTTP 4xx/5xx/timeout/DNS:**
   - Retry up to 3 times with exponential backoff (250ms, 750ms,
     2s) per Codex/Gemini 86.
   - After all retries fail → fallback banner with `user_id` +
     `pairing_key_hex` displayed for manual paste. Local
     `PairedSender` record is NOT deleted (the syncler side is
     already paired; user just needs the sender side to catch up).

### Wire DTO (Gemini 86 refinement)
```kotlin
data class BootstrapEnvelopeDto(
    val protocolVersion: Int,           // 1
    val pairingId: String,
    val senderId: String,
    val bootstrapKeyId: String,         // base64
    val exp: String,                    // ISO Z
    val ephemeralPubkey: String,        // base64
    val nonce: String,                  // base64
    val ciphertext: String,             // base64
)
```
`aadJson` is NOT on the wire — it's reconstructable from the
trusted state on the broker side.

### UI states (Gemini 86 refinement)
- `Idle` / `Loading` / `PreviewLoaded` / `Confirming` / `Confirmed`
  (manual) / `BootstrapPosting` (new) / `BootstrapSucceeded` (new) /
  `BootstrapFailedFallback` (new).
- `BootstrapSucceeded` shows "Paired automatically with <sender>"
  + Done button. NOT the "copy these values" block — that's
  only for the manual + fallback paths.

## Item 2 — FastAPI broker (revised)

### Module + extra
- `sdk-python/syncler/broker/__init__.py` (exports `make_app`)
- `sdk-python/syncler/broker/app.py` (FastAPI factory + handler)
- `sdk-python/pyproject.toml`:
  ```toml
  [project.optional-dependencies]
  broker = ["fastapi>=0.110", "uvicorn[standard]>=0.27"]
  ```

### Broker URL strategy — **single fixed `sender_broker_url`** for V1.5
Per Codex 86: this weakens the "unknown pairing_id rejected" rule
relative to per-pairing URLs. Document this explicitly. V2 can
add a `pairing_id → broker_url` map.

### Storage / pending state — fixed-config deviation, documented
Codex 86 flagged that `BrokerStorage` doesn't track pending
pairings, so the broker can't reject unknown `pairing_id`. For
V1.5 we accept this — the AAD-bound `pairing_id` is part of the
authenticated envelope, so a wrong `pairing_id` decrypts to
garbage and the AEAD tag fails anyway. The "reject unknown
pairing_id" check is defense-in-depth, not the primary guard.

Document this in `broker_storage.py` doc-string and the
integration guide.

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

### Handler
1. Optional `rate_limiter(request)` hook — defaults None
   (Gemini 86 + Codex 86 both said "ship a hook even if optional").
2. Parse JSON, validate shape (all 8 fields present, types correct).
3. Validate base64 field lengths BEFORE decrypt (Codex 86 RED):
   `ephemeral_pubkey` decodes to exactly 32 bytes, `nonce` to 12,
   `bootstrap_key_id` to 16, `ciphertext` ≥ 16 bytes (AEAD tag is
   16). On length mismatch → 400.
4. Call `decrypt_bootstrap_envelope(...)` passing
   `sender_broker_url_from_trusted_state=sender_broker_url`.
5. Call `storage.complete(pairing_id, BrokerEntry(user_id,
   pairing_key))`.
6. **HTTP status semantics (Codex + Gemini 86):**
   - First completion → **201**
   - Idempotent replay with same values → **200**
   - `BrokerStorageConflictError` (different values) → **409**
   - `BootstrapDecryptError` (sig, AAD, AEAD tag, expired) → **401**
     with no detail (avoid info leak about which field mismatched).

To distinguish 200 vs 201 we need `storage.complete` to indicate
"was idempotent" — extend `BrokerStorage.complete` to return a
bool `was_first` or extend `BrokerEntry` with `_was_first`
metadata. Cleanest: change the Protocol signature to return
`bool` (True if this was the first write). Update
`InMemoryBrokerStorage.complete` accordingly.

### Tests
`sdk-python/tests/test_broker_app.py` (via `fastapi.testclient`):
- Happy path → 201 + storage populated.
- Replay same values → 200, storage unchanged.
- Replay different values → 409.
- Expired envelope → 401.
- Substituted sender_broker_url in AAD → 401.
- AAD bit-flip → 401.
- Malformed base64 lengths → 400.
- Optional rate_limiter raising → 429 (or whatever it raises).

## Item 3 — `docs/integration-guide.md` automated-pairing (§8.5)

Same plan as 86 (Gemini + Codex both GREEN). With these tweaks
from Codex/Gemini 86:
- Use shipped field name `sender_broker_url` consistently (not
  the older `broker_url` wording, except in the one paragraph
  that distinguishes the two).
- Add a "Production storage" callout — `InMemoryBrokerStorage`
  is not safe across multiple uvicorn workers; production needs
  Redis or Postgres.
- §1 overview gets a one-paragraph mention of automated pairing
  pointing to §8.5.

## Refinements accepted from consultation 86

| Source | Refinement | In revised plan |
|---|---|---|
| Codex 86 | No auth-header leak via unauthenticated client | ✓ Item 1 |
| Codex 86 | `/complete` field correction | ✓ Item 1 |
| Codex 86 | Hard error on partial/malformed bootstrap fields | ✓ Item 1 |
| Codex 86 | 2-3 retry with backoff | ✓ Item 1 (250ms/750ms/2s) |
| Codex 86 | Broker storage deviation documented | ✓ Item 2 |
| Codex 86 | 200 idempotent / 201 first | ✓ Item 2 |
| Codex 86 | Base64 length validation before decrypt | ✓ Item 2 |
| Codex 86 | Rate-limit hook | ✓ Item 2 |
| Codex 86 | Multi-worker InMemoryBrokerStorage note | ✓ Item 3 |
| Codex 86 | `sender_broker_url` wording consistency | ✓ Item 3 |
| Gemini 86 | `BootstrapEnvelopeDto` shape | ✓ Item 1 |
| Gemini 86 | `BootstrapSucceeded` UI state | ✓ Item 1 |
| Gemini 86 | §8.5 placement | ✓ Item 3 |

## Output

Per reviewer:
1. Per-item: GREEN / YELLOW / RED on the revised plan.
2. Anything still missing.
3. Anything new (security, footgun, doc accuracy).

If both GREEN, I implement and fire a separate code-review
consultation.
