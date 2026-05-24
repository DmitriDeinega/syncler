# Consultation 73 — Phase 5a-2 implementation plan

`.triad/70-phase5-agreement.md` is the dual-green-locked spec.
Phase 5a-1 (§9 bootstrap protocol in crypto-spec.md + test
vectors) shipped at commit `57bb488`. This consultation locks
the implementation plan for Phase 5a-2 before any code lands.

## What 5a-2 implements

The automated pairing handshake — server endpoints + Android
post-confirm flow + SDK polling. The spec is already locked in
§9 of `docs/crypto-spec.md` and the agreement document. This
consultation is about HOW, not WHAT.

## Proposed implementation plan

### Server (FastAPI)

#### New: `/v1/senders/me/bootstrap-key` (POST)

Lets a sender register or rotate its X25519 bootstrap key.

- Auth: sender-signed envelope (existing pattern, mirrors
  `/v1/senders/register`).
- Request body:
  ```python
  class BootstrapKeyRegisterRequest(BaseModel):
      sender_id: UUID
      bootstrap_key: str        # base64 32 bytes (X25519 pub)
      bootstrap_key_signature: str  # base64 64 bytes Ed25519
                                    # over "syncler-v1-bootstrap-key:" || bootstrap_key_raw
  ```
- Validation: decode + length check; verify Ed25519 sig against
  the sender's existing `public_key` from `senders` table; reject
  if the sender row is revoked.
- Storage: new columns on `senders` table — `bootstrap_key`
  (LargeBinary, nullable until V1.5 senders register one) and
  `bootstrap_key_signature` (LargeBinary, nullable). Server
  computes `bootstrap_key_id = SHA-256(bootstrap_key)[:16]` on
  the fly (no need to store it; it's deterministic).
- Alembic migration: `0008_sender_bootstrap_key.py` — two new
  nullable columns on `senders`.

#### Modified: `/v1/pairing/initiate` (POST)

Add an optional `broker_url` field to the request body.

- Body extends with `broker_url: str | None = None`. When
  present, it's part of the sender-signed canonical envelope.
- Server validates: `https://` in release, `http://<private LAN>`
  in debug only (10.x, 172.16-31.x, 192.168.x, localhost); no
  credentials in URL; no fragment; length ≤ 2048 chars; RFC
  3986-shape (use Python's `urllib.parse`).
- Server stores `broker_url` on the pending `pairings` row.
- Response shape gains `broker_url` (echoed) and
  `bootstrap_protocol_version: int = 1` when the sender
  supplied `broker_url`.

#### Modified: `/v1/pairing/preview` (GET)

When the underlying pending pairing has a non-null `broker_url`,
the response includes:
- `broker_url`: the stored value, echoed
- `bootstrap_key`: base64 of the sender's stored X25519 pub
- `bootstrap_key_signature`: base64 of the sender's stored sig
- `bootstrap_protocol_version`: 1

Otherwise these fields are absent (preserves V1 wire compat).

#### Schema additions to `server/app/schemas.py`

```python
class BootstrapKeyRegisterRequest(BaseModel): ...

class BootstrapKeyRegisterResponse(BaseModel):
    bootstrap_key_id: str  # base64 16 bytes = SHA-256[:16]

# Extend PairingInitiateRequest with broker_url
# Extend PairingInitiateResponse with broker_url + bootstrap_protocol_version
# Extend PairingPreviewResponse with broker_url + bootstrap_key + bootstrap_key_signature + bootstrap_protocol_version (all optional)
```

#### Tests

- `server/tests/test_bootstrap_key.py` (NEW): register success,
  register with bad signature, register on revoked sender,
  rotate (re-register).
- Extend `server/tests/test_pairing.py`: initiate with
  `broker_url`, initiate with bad `broker_url` shape, preview
  echoes broker fields.

### Android

#### New: `app.syncler.core.crypto.BootstrapEnvelope`

Pure crypto module. Builds and packs the HPKE-style envelope
matching the §9 spec.

```kotlin
object BootstrapEnvelope {
    fun buildEnvelope(
        senderBootstrapPub: ByteArray,      // 32 bytes
        bootstrapKeyId: ByteArray,           // 16 bytes
        pairingId: UUID,
        senderId: UUID,
        brokerUrl: String,
        userId: UUID,
        pairingKey: ByteArray,               // 32 bytes
        nowProvider: () -> Instant,          // injectable for tests
    ): EnvelopeBytes
}

data class EnvelopeBytes(
    val ephemeralPubkey: ByteArray,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val aadJson: String,
    val pairingId: UUID,
    val senderId: UUID,
    val bootstrapKeyId: ByteArray,
    val expIso: String,
)
```

Implementation:
- Generate ephemeral X25519 keypair via BouncyCastle.
- ECDH against sender's bootstrap pub.
- HKDF-SHA256 with the spec's salt (eph_pub || boot_pub), info
  (`"syncler-v1-bootstrap-aead"`), length 32.
- Canonical AAD JSON via the existing `canonicalJsonBytes`
  helper in `core/crypto/Aad.kt` (key sort, ensure_ascii, compact
  separators, integer literals not stringified).
- AES-256-GCM encrypt.
- Returns the components the caller serializes to JSON for POST.

Unit tests in `android/core/crypto/.../BootstrapEnvelopeTest.kt`:
- Round-trip against a known sender bootstrap private key.
- AAD canonical bytes match the §9 vector.
- Bootstrap key signature verification.

#### Modified: `app.syncler.feature.pairing.PairingRepository.complete(...)`

After the user confirms in the existing PairingPreview screen,
if the preview response carried a `broker_url`:
1. Verify the bootstrap key signature against the sender's
   Ed25519 public key. If invalid, fail loudly — treat as
   syncler-side substitution attack.
2. Build the bootstrap envelope via `BootstrapEnvelope.buildEnvelope`.
3. Serialize to the wire JSON shape.
4. POST to `broker_url` via an unauthenticated `OkHttpClient`
   (mirror the `TemplateActionRunner` pattern from Phase 3a — no
   Authorization header).
5. On success (2xx), proceed to the existing
   `pairedSenderStore.add(...)` path.
6. On failure (timeout, non-2xx, network), surface a banner on
   the existing post-confirm screen showing the V1 manual flow
   (`user_id` + `pairing_key_hex`) with a "retry" affordance.

The existing UI screen gets two new affordances:
- Loading state while POST is in-flight (`Pairing automatically…`).
- Error banner on POST failure with "Show manual code" button.

#### New: `BrokerEnvelopeDto` in `:core:network`

Wire DTO matching the §9 envelope shape, Moshi-annotated.

#### Tests

- `BootstrapEnvelopeTest.kt`: vector round-trip.
- `PairingRepositoryTest.kt` (or extend existing): broker POST
  success → calls `pairedSenderStore.add`; broker POST failure →
  retains manual fallback state.

### Python SDK

#### New: `BrokerStorage` protocol + in-memory default

```python
from typing import Protocol

class BrokerStorage(Protocol):
    def reserve(self, pairing_id: str) -> None:
        """Called at pairing/initiate to mark a slot."""
    def complete(self, pairing_id: str, user_id: str, pairing_key: bytes) -> None:
        """CAS: first call wins; same values is 200 idempotent;
           different values raises BrokerConflictError."""
    def fetch(self, pairing_id: str) -> tuple[str, bytes] | None:
        """SDK polls this. None until completed."""

class InMemoryBrokerStorage:
    """Default dev implementation. dict-of-pairings."""
```

#### New: `BrokerServer` ASGI/FastAPI mini-app

```python
def make_broker_app(*, sender: Client, storage: BrokerStorage) -> FastAPI:
    """Returns a FastAPI app the sender can mount at its
    chosen broker_url path. Handles POST /, decrypts the
    bootstrap envelope, validates AAD against stored pairing
    state, CAS-stores via storage."""
```

The sender's backend mounts this at whatever URL it chose for
`broker_url`. SDK ships the helper; production users can
substitute their own broker if they want fancier auth.

#### Modified: `client.py:create_pairing_qr(...)`

Add `broker_url: str | None = None` parameter. When supplied,
forwards to the server's `pairing/initiate` and reserves the
slot in the local broker storage.

#### Modified: `client.py:wait_for_pairing(...)` — implement

```python
def wait_for_pairing(
    self,
    *,
    timeout_seconds: int = 120,
    poll_interval_seconds: float = 1.0,
) -> Pairing:
    """Polls broker storage until a (user_id, pairing_key)
    tuple is present for the pending pairing_id. ±20% jitter on
    poll interval. Raises TimeoutError on deadline."""
```

Internally polls `self._broker_storage.fetch(pairing_id)` until
non-None or deadline. Calls `self.set_pairing(...)` on success.

#### New: `sdk-python/syncler/bootstrap.py`

Decrypt helper that mirrors `assemble_live_card_aad`'s style:
- `decrypt_bootstrap_envelope(envelope, sender_bootstrap_priv, broker_url_from_state)` → `(user_id, pairing_key)`.
- Reconstructs canonical AAD with `broker_url` from caller-
  supplied trusted state (NOT from the envelope JSON).

#### Tests

- `sdk-python/tests/test_bootstrap.py` (NEW): full
  build-on-Android-side-simulate / decrypt-on-sender-side
  round-trip with stable vectors.
- Extend `tests/test_client.py`: `wait_for_pairing` polls and
  returns; `wait_for_pairing` times out.

### Cross-cutting

#### `docs/integration-guide.md`

Update §8 (testing) to describe the automated path. Add a new
§5.5 or extend §5 documenting `broker_url` in
`create_pairing_qr` and `wait_for_pairing`. Note manual fallback
is preserved.

#### `docs/crypto-spec.md`

Already updated in 5a-1. No changes needed.

#### Build verification

- `./gradlew :core:crypto:testDebugUnitTest :feature:pairing:testDebugUnitTest`
- `cd server && pytest tests/test_bootstrap_key.py tests/test_pairing.py`
- `cd sdk-python && pytest tests/test_bootstrap.py tests/test_client.py`

## Open questions for the triad

1. **Bootstrap key column on `senders` table**: nullable until
   V1.5 senders register one — correct? Or should it be a
   separate `sender_bootstrap_keys` table for rotation history?
   (I'm proposing inline + nullable for V1.5; rotation history
   is V2 — the bootstrap key is per-sender, single-current-value.)

2. **Broker URL persistence**: I'm proposing the broker URL
   lives on the pending `pairings` row (added at `pairing/initiate`,
   read at `pairing/preview`). After the pairing completes,
   does the broker URL get retained? My current plan: yes, kept
   for audit — but it's never read again after the bootstrap
   POST happens. Push back if you want it scrubbed.

3. **BrokerServer placement**: I'm proposing the broker HTTP
   handler ships as part of the SDK (`make_broker_app(...)` →
   FastAPI app the user mounts). This means the SDK takes a
   FastAPI dependency. Acceptable? Alternative: ship the
   handler as a `BrokerProtocol` and let the user wire it to
   any HTTP framework. Simpler but more code per user.

4. **`wait_for_pairing` blocking semantics**: I'm proposing
   synchronous polling (the SDK is sync today). For async
   callers, they wrap in `asyncio.to_thread`. Push back if you
   want an `async def wait_for_pairing(...)` variant too.

5. **Android post-confirm UX**: I'm proposing the existing
   PairingPreview screen handles all three states (loading,
   success → next screen, failure → manual fallback banner +
   manual code shown). Alternative: dedicated
   PairingCompleteScreen. The existing screen is cleaner; the
   dedicated screen is more discoverable.

6. **Anything missing.** What's not in this plan that should be?

## Output

Per reviewer:
1. Per-section (Server / Android / SDK / Cross-cutting): GREEN
   / YELLOW / RED.
2. Per-open-question: concrete answer.
3. Anything missing.
4. Overall: ready to start coding / specific blockers / hold.

If both reviewers GREEN, I start implementing 5a-2 — server
first, then Android, then SDK, in that order (each compiles
independently; server defines the wire contract the other two
depend on).
