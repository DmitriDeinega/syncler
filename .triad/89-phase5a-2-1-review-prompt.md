=================================================================
ABSOLUTE INSTRUCTION — READ THIS BEFORE DOING ANYTHING ELSE

You are in REVIEW MODE. Your ONLY output is a text reply to my
prompt below. You are FORBIDDEN from:

  - Writing or editing ANY file (no write_file, no edit_file, no
    str_replace, no create_file, no apply_diff, etc.).
  - Running ANY shell command that mutates state (no git commit,
    no git add, no git checkout, no git push, no git stash, no
    mkdir, no pip install, no npm install, no rm, no mv, no touch,
    no chmod).
  - Running tests, builds, signing keys, gradle, uvicorn, or any
    other side-effecting operation.
  - Creating or modifying ANY directory.

You MAY use:
  - read_file / cat / Get-Content (to inspect files for context)
  - grep / ripgrep / Select-String (read-only search)
  - list_directory / ls / Get-ChildItem (read-only)
  - git status / git log / git diff / git show (read-only)

The orchestrator (human + me) is the only entity that writes code
or runs git. Your verdict goes through us before any change to the
repo lands.

Three prior consultations in this session had review-mode
violations and were reverted. If you violate this again the repo
will be reset without your input and the remainder of the work
will continue without your verdict.

Reply with ONLY the verdict — no implementation summary, no
"I have updated...", no "All tests passing..."

=================================================================

# Consultation 89 — Phase 5a-2.1 code review

The Phase 5a-2.1 plan reached dual-GREEN at consult 88 (with two
tiny edits Codex 88 flagged: retry-count phrasing + DTO
serialization test, both folded into the implementation).

This consultation reviews the actual code.

## Files changed

### Item 1 — Android UX

- `android/core/network/src/main/kotlin/app/syncler/core/network/SynclerApi.kt`
  - `PairingPreviewResponseDto` gained 4 nullable bootstrap fields.
- `android/core/network/src/main/kotlin/app/syncler/core/network/NetworkModule.kt`
  - New `@BrokerOkHttp` qualifier + `provideBrokerOkHttpClient`
    (plain OkHttpClient, NO auth interceptor, NO logging).
  - New `provideBrokerApi` builds a Retrofit instance off the
    unauthenticated client with a placeholder baseUrl (every call
    uses `@Url`).
- `android/core/network/src/main/kotlin/app/syncler/core/network/BrokerApi.kt` (NEW)
  - `BrokerApi` Retrofit interface with one `@POST @Url` method.
  - `BootstrapEnvelopeDto` with explicit `@Json(name = "...")` on
    every field — snake_case wire format byte-for-byte matching
    Python.
- `android/feature/pairing/src/main/kotlin/app/syncler/feature/pairing/PairingRepository.kt`
  - `BrokerApi` injected alongside `SynclerApi`.
  - `classifyBootstrap(preview): BootstrapClassification` — returns
    Manual / Automated / HardError. Validates all four metadata
    fields exist, base64 decodes to 32+64 bytes, `protocol_version == 1`,
    `sender_broker_url` scheme allowed.
  - `verifyBootstrapKeySignature(senderEdPubRaw, bootstrapKeyRaw,
    bootstrapKeySignatureRaw): Boolean` — Ed25519-verifies
    `b"syncler-v1-bootstrap-key:" || bootstrap_pub`.
  - `postBootstrapEnvelope(url, dto, maxAttempts=3,
    backoffMillis=[250, 750]): Result<Unit>` — retries network/5xx
    only; terminal on 4xx.
  - `buildEnvelopeDto(automated, pairingId, senderId, userId,
    pairingKey): BootstrapEnvelopeDto` — wraps `BootstrapEnvelope.build`,
    constructs the wire DTO with base64-encoded fields.
  - `BootstrapClassification` sealed interface added.
- `android/feature/pairing/src/main/kotlin/app/syncler/feature/pairing/PairingScreen.kt`
  - `PairingState` extended with `BootstrapPosting`,
    `BootstrapSucceeded`, `BootstrapFailedFallback`, `BootstrapHardError`.
  - `PairingViewModel.confirm()` branches on the classification:
      - HardError → state = `BootstrapHardError`, do NOT call `/complete`.
      - Automated: verify signature; on fail → BootstrapHardError;
        else `/complete` → persist PairedSender (Codex 87 RED: this
        happens regardless of broker POST result) → POST envelope →
        BootstrapSucceeded / BootstrapFailedFallback.
      - Manual → existing flow unchanged.
  - New `BootstrapSuccessCard` composable; existing
    `PairingSuccessCard` reused for the fallback path.
- `android/core/network/src/test/kotlin/app/syncler/core/network/BrokerApiTest.kt` (NEW)
  - 3 tests: snake_case JSON keys assertion, no `Authorization`
    header on broker POST, Content-Type is JSON.

### Item 2 — FastAPI broker

- `sdk-python/pyproject.toml`
  - New `[project.optional-dependencies] broker = [fastapi, uvicorn]`.
- `sdk-python/syncler/broker_storage.py`
  - `BrokerStorage.complete` Protocol now returns `bool` (True =
    first completion, False = idempotent replay).
  - `InMemoryBrokerStorage.complete` updated to match.
  - Doc-string explicitly notes V1.5 fixed-config deviation (no
    pending-pairing registry; UUID entropy + rate limiter are the
    V1.5 mitigations; pointer to crypto-spec §9.3).
- `sdk-python/syncler/broker/__init__.py` (NEW) — exports `make_app`.
- `sdk-python/syncler/broker/app.py` (NEW)
  - Single `POST /` endpoint mounted at root.
  - Strict 8-field envelope shape validation; rejects missing AND
    unexpected fields (Codex 88 note).
  - Base64 length validation before decrypt (32/12/16/≥17).
  - Optional `rate_limiter: Callable[[Request], Awaitable[None]]`
    hook fires BEFORE body parse. HTTPException propagates as-is;
    other exceptions → 500 (default FastAPI behavior).
  - Status codes: 201 first / 200 idempotent / 401 opaque /
    409 conflict / 400 shape error.
  - `make_app` validates pub key length at factory time.
- `sdk-python/tests/test_broker_app.py` (NEW) — 16 tests covering
  every above behavior. All passing locally.
- `sdk-python/tests/test_bootstrap.py` — single CAS test asserts
  the new `complete -> bool` return value.

### Item 3 — Docs

- `docs/integration-guide.md`
  - §1 gains one paragraph mentioning automated pairing as V1.5
    default, pointer to §8.5.
  - New §8.5 "Automated pairing (V1.5)" between §8 and §9 with
    subsections: Why automated, Sender setup (4 numbered steps with
    code snippets), Android user flow, Failure modes (table),
    Security boundaries (3 guards), Production hardening.
- `docs/crypto-spec.md`
  - §9.3 gains "V1.5 deviation: fixed-config broker" subsection
    explicitly documenting that the shipped broker can't reject
    unknown `pairing_id`s, with mitigations and V2 plan.

## Test counts (local)

- `sdk-python/tests/` — 32/32 passing (16 new broker tests +
  pre-existing 16, including the updated CAS test).
- Android — `BrokerApiTest` written but not run in this env (no
  gradle available). The 3 tests use MockWebServer with a plain
  `OkHttpClient.Builder().build()` (no interceptors) to assert
  the auth-header isolation and wire format.

## What I need

Per reviewer, per-item:
1. GREEN / YELLOW / RED on each of Item 1 / Item 2 / Item 3.
2. Anything missing from the dual-GREEN plan at consult 88.
3. Anything new (security, footgun, doc accuracy).
4. Commit-readiness vote.

Reply text only. Do NOT call any write/mutation tool.
