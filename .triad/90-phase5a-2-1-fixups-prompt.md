=================================================================
ABSOLUTE INSTRUCTION — READ THIS BEFORE DOING ANYTHING ELSE

You are in REVIEW MODE. Your ONLY output is a text reply to the
prompt below. You are FORBIDDEN from:

  - Writing or editing ANY file (no write_file, no edit_file, no
    str_replace, no create_file, no apply_diff).
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

The orchestrator (human + me) commits. Three prior consultations
had review-mode violations and were reverted. If you violate this
again, the work proceeds without your verdict.

Reply with ONLY the verdict — no implementation summary, no
"I have updated...", no "All tests passing..."
=================================================================

# Consultation 90 — Phase 5a-2.1 fix-ups (post-89)

Consultation 89 voted: **Gemini GREEN, Codex RED on three concrete
bugs**. This consultation reviews the fix-ups.

## What Codex 89 caught

### RED — Item 2 — Broker leaks 500 on invalid X25519 ephemeral pubkey

> `sdk-python/syncler/bootstrap.py:149-150` can raise
> non-`BootstrapDecryptError` exceptions for attacker-controlled
> but length-valid X25519 public keys, e.g. low-order/invalid points.
> `sdk-python/syncler/broker/app.py:227-240` only maps
> `BootstrapDecryptError` to opaque `401`, so this can become a
> public `500` instead of the promised opaque decrypt failure.

### RED — Item 2 — Packaging footgun

> New broker tests import FastAPI, but `sdk-python[dev]` does not
> include the `broker` extra. A clean dev install may not be able
> to run the full SDK test suite.

### RED — Item 3 — Docs reference non-existent API

> `docs/integration-guide.md:616` calls `client.set_bootstrap_keypair(...)`,
> but `Client` has no such method.
> `docs/integration-guide.md:627` calls `wait_for_pairing(timeout=120)`,
> but the API parameter is `timeout_seconds`.
> `docs/integration-guide.md:628` uses `pairing.pairing_key`, but
> `Pairing` only contains `pairing_id` and `user_id`; the key is
> stored on `client.pairing_key`.

### YELLOW — Item 1 — Minor

> `PairingScreen.kt:110` decodes `senderPublicKey` outside a
> guarded path. A malformed preview can crash instead of becoming
> `BootstrapHardError`.

> `PairingRepository.postBootstrapEnvelope()` documents retrying
> `429`, but the implementation treats all 4xx as terminal.

## Fix-ups applied

### 1. `sdk-python/syncler/bootstrap.py` — wrap X25519 ECDH errors

```python
# X25519 ECDH. `from_public_bytes` validates input length and
# `exchange` rejects low-order / invalid points — both can raise
# ValueError / cryptography-library exceptions. Wrap as
# BootstrapDecryptError so the broker's exception handler doesn't
# let an opaque-401 path leak a 500 (Codex consultation 89 RED).
try:
    eph_pub = X25519PublicKey.from_public_bytes(ephemeral_pubkey)
    shared_secret = sender_bootstrap_priv.exchange(eph_pub)
except Exception as exc:
    raise BootstrapDecryptError(
        "X25519 ECDH failed — invalid ephemeral_pubkey",
    ) from exc
```

### 2. New regression test in `sdk-python/tests/test_broker_app.py`

```python
def test_invalid_low_order_ephemeral_pubkey_returns_401_opaque(client, keypair):
    """Codex 89 RED: X25519 low-order/invalid points must surface as
    opaque 401, not a 500 from the X25519 library bubbling up."""
    priv, pub = keypair
    envelope = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    # All-zero is a well-known low-order point that cryptography
    # rejects at exchange() time.
    envelope["ephemeral_pubkey"] = _b64(b"\x00" * 32)
    response = client.post("/", json=envelope)
    assert response.status_code == 401, response.text
    assert response.json()["detail"] == "bootstrap decrypt failed"
```

22/22 sdk-python tests pass (16 broker + 5 bootstrap + 1 CAS).

### 3. `sdk-python/pyproject.toml` `[dev]` extra now includes broker deps

```toml
dev = [
    "pytest>=8.0,<9",
    "fastapi>=0.110,<1",
    "uvicorn[standard]>=0.27,<1",
    "httpx>=0.27",
]
```

So `pip install -e .[dev]` on a clean checkout runs the full suite.

### 4. Docs API references corrected

`docs/integration-guide.md §8.5` step 4 now reads:

```python
client = Client(
    sender_name="Example",
    private_key_path="sender.pem",
    broker_storage=storage,
)
# The sender's X25519 bootstrap keypair lives outside Client (the
# broker app holds it). The Client only needs the storage handle
# to learn when a pairing has completed.

path = client.create_pairing_qr(
    ttl_seconds=300,
    out_path="pair.png",
    sender_broker_url="https://sender.example.com/syncler/bootstrap",
)

# Blocks until the broker writes (user_id, pairing_key) to storage.
# On success the Pairing dataclass carries `pairing_id` + `user_id`;
# the 32-byte pairing key is stored on `client.pairing_key` so
# subsequent `send_to(...)` calls just work.
pairing = client.wait_for_pairing(timeout_seconds=120)
# `client.pairing_key` is now set; no further `set_pairing` call needed.
```

Removed: `set_bootstrap_keypair` (no such method), `timeout=`
(actual is `timeout_seconds`), `pairing.pairing_key` (key is
on `client.pairing_key`).

### 5. Android YELLOWs

`PairingScreen.kt` now does the senderPublicKey base64 decode
inside a `runCatching` and routes failures to `BootstrapHardError`:

```kotlin
val senderEdPub = runCatching {
    android.util.Base64.decode(current.preview.senderPublicKey, android.util.Base64.NO_WRAP)
}.getOrNull()
if (senderEdPub == null || senderEdPub.size != 32) {
    _state.value = PairingState.BootstrapHardError(
        preview = current.preview,
        message = "preview senderPublicKey is not a valid 32-byte base64 Ed25519 key",
    )
    return
}
```

`PairingRepository.postBootstrapEnvelope` comment corrected to
match implementation (all 4xx terminal; 429+Retry-After deferred
to V2).

## What I need

Per reviewer:
1. Per-item: GREEN / YELLOW / RED.
2. Confirm: Codex 89 REDs are all addressed.
3. Anything new.
4. Commit-readiness vote.

Reply text only. Do NOT call any write/mutation tool.
