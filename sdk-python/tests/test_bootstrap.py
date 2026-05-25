"""V1.5 automated pairing — round-trip + vector tests.

Asserts that the Python SDK's encrypt-on-device + decrypt-on-broker
round-trip yields byte-identical canonical AAD bytes to
``docs/crypto-spec.md §9.4``. The cross-impl byte equivalence is
already asserted in `server/tests/test_crypto.py` and Android's
`SpecVectorsTest.kt`; this file proves the SDK's helpers compose
correctly.
"""

from __future__ import annotations

import base64
import json
from datetime import UTC, datetime, timedelta

import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from syncler.bootstrap import (
    BootstrapDecryptError,
    BROKER_CLOCK_SKEW_SECONDS,
    assemble_bootstrap_aad,
    bootstrap_key_id,
    decrypt_bootstrap_envelope,
    x25519_keypair_pem,
)
from syncler.broker_storage import (
    BrokerEntry,
    BrokerStorageConflictError,
    InMemoryBrokerStorage,
    UnknownPairingIdError,
)


HKDF_INFO_BOOTSTRAP_AEAD = b"syncler-v1-bootstrap-aead"


def _build_device_envelope(
    sender_bootstrap_pub: bytes,
    *,
    pairing_id: str,
    sender_id: str,
    sender_broker_url: str,
    plaintext: bytes,
    exp_iso: str,
    eph_seed: bytes | None = None,
    nonce: bytes | None = None,
) -> dict[str, bytes | str]:
    """Mirror of `BootstrapEnvelope.build` on the Android side. Used to
    drive end-to-end tests without spinning up an Android emulator."""
    eph_priv = X25519PrivateKey.from_private_bytes(eph_seed) if eph_seed else X25519PrivateKey.generate()
    eph_pub = eph_priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    shared = eph_priv.exchange(__import__("cryptography.hazmat.primitives.asymmetric.x25519", fromlist=["X25519PublicKey"]).X25519PublicKey.from_public_bytes(sender_bootstrap_pub))
    aead_key = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=eph_pub + sender_bootstrap_pub,
        info=HKDF_INFO_BOOTSTRAP_AEAD,
    ).derive(shared)
    kid_b64 = base64.b64encode(bootstrap_key_id(sender_bootstrap_pub)).decode("ascii")
    aad = assemble_bootstrap_aad(
        bootstrap_key_id_b64=kid_b64,
        exp_iso=exp_iso,
        pairing_id=pairing_id,
        sender_broker_url=sender_broker_url,
        sender_id=sender_id,
    )
    import os
    nonce_bytes = nonce or os.urandom(12)
    ciphertext = AESGCM(aead_key).encrypt(nonce_bytes, plaintext, aad)
    return {
        "ephemeral_pubkey": eph_pub,
        "nonce": nonce_bytes,
        "ciphertext": ciphertext,
        "bootstrap_key_id_b64": kid_b64,
        "exp_iso": exp_iso,
    }


def test_bootstrap_aad_canonical_bytes_match_spec_vector():
    """Asserts the SDK's `assemble_bootstrap_aad` produces the exact
    byte string vector documented in `docs/crypto-spec.md §9.4`. If
    this assertion fails, SDK ↔ host parity is broken."""
    aad = assemble_bootstrap_aad(
        bootstrap_key_id_b64="oCiYEAMutBcnTuvEo45omQ==",
        exp_iso="2026-05-24T12:00:00Z",
        pairing_id="00000000-1111-2222-3333-444444444444",
        sender_broker_url="https://broker.example.com/api/v1",
        sender_id="55555555-6666-7777-8888-999999999999",
    )
    expected = (
        b'{"bootstrap_key_id":"oCiYEAMutBcnTuvEo45omQ==",'
        b'"exp":"2026-05-24T12:00:00Z",'
        b'"pairing_id":"00000000-1111-2222-3333-444444444444",'
        b'"protocol_version":1,'
        b'"sender_broker_url":"https://broker.example.com/api/v1",'
        b'"sender_id":"55555555-6666-7777-8888-999999999999"}'
    )
    assert aad == expected


def test_bootstrap_round_trip():
    sender_priv, sender_pub = x25519_keypair_pem()
    pairing_id = "00000000-1111-2222-3333-444444444444"
    sender_id = "55555555-6666-7777-8888-999999999999"
    broker_url = "https://broker.example.com/api/v1"
    user_id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    pairing_key = bytes(range(32))
    plaintext = json.dumps(
        {"user_id": user_id, "pairing_key": base64.b64encode(pairing_key).decode("ascii")},
    ).encode("utf-8")
    exp_iso = (datetime.now(UTC) + timedelta(seconds=60)).isoformat().replace("+00:00", "Z")

    envelope = _build_device_envelope(
        sender_pub,
        pairing_id=pairing_id,
        sender_id=sender_id,
        sender_broker_url=broker_url,
        plaintext=plaintext,
        exp_iso=exp_iso,
    )

    decrypted_user_id, decrypted_pairing_key = decrypt_bootstrap_envelope(
        sender_bootstrap_priv=sender_priv,
        sender_bootstrap_pub=sender_pub,
        pairing_id=pairing_id,
        sender_id=sender_id,
        sender_broker_url_from_trusted_state=broker_url,
        ephemeral_pubkey=envelope["ephemeral_pubkey"],
        nonce=envelope["nonce"],
        ciphertext=envelope["ciphertext"],
        exp_iso=envelope["exp_iso"],
        bootstrap_key_id_b64_from_envelope=envelope["bootstrap_key_id_b64"],
    )
    assert decrypted_user_id == user_id
    assert decrypted_pairing_key == pairing_key


def test_bootstrap_decrypt_rejects_substituted_broker_url():
    """If the broker uses a different `sender_broker_url` for AAD
    reconstruction (simulating a syncler-server substitution attack
    where the client-supplied AAD echo was tampered), the AEAD tag
    must fail to verify. This is the substitution-attack guard."""
    sender_priv, sender_pub = x25519_keypair_pem()
    exp_iso = (datetime.now(UTC) + timedelta(seconds=60)).isoformat().replace("+00:00", "Z")
    envelope = _build_device_envelope(
        sender_pub,
        pairing_id="00000000-1111-2222-3333-444444444444",
        sender_id="55555555-6666-7777-8888-999999999999",
        sender_broker_url="https://legit-broker.example.com/api/v1",
        plaintext=b'{"user_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","pairing_key":"AA=="}',
        exp_iso=exp_iso,
    )
    with pytest.raises(BootstrapDecryptError):
        decrypt_bootstrap_envelope(
            sender_bootstrap_priv=sender_priv,
            sender_bootstrap_pub=sender_pub,
            pairing_id="00000000-1111-2222-3333-444444444444",
            sender_id="55555555-6666-7777-8888-999999999999",
            sender_broker_url_from_trusted_state="https://attacker.example.com/api/v1",
            ephemeral_pubkey=envelope["ephemeral_pubkey"],
            nonce=envelope["nonce"],
            ciphertext=envelope["ciphertext"],
            exp_iso=envelope["exp_iso"],
            bootstrap_key_id_b64_from_envelope=envelope["bootstrap_key_id_b64"],
        )


def test_bootstrap_decrypt_rejects_stale_exp():
    sender_priv, sender_pub = x25519_keypair_pem()
    # Build with an `exp` outside the ±5min tolerance.
    stale_exp = (datetime.now(UTC) - timedelta(seconds=BROKER_CLOCK_SKEW_SECONDS + 60)).isoformat().replace("+00:00", "Z")
    envelope = _build_device_envelope(
        sender_pub,
        pairing_id="00000000-1111-2222-3333-444444444444",
        sender_id="55555555-6666-7777-8888-999999999999",
        sender_broker_url="https://broker.example.com/api/v1",
        plaintext=b'{"user_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","pairing_key":"AA=="}',
        exp_iso=stale_exp,
    )
    with pytest.raises(BootstrapDecryptError):
        decrypt_bootstrap_envelope(
            sender_bootstrap_priv=sender_priv,
            sender_bootstrap_pub=sender_pub,
            pairing_id="00000000-1111-2222-3333-444444444444",
            sender_id="55555555-6666-7777-8888-999999999999",
            sender_broker_url_from_trusted_state="https://broker.example.com/api/v1",
            ephemeral_pubkey=envelope["ephemeral_pubkey"],
            nonce=envelope["nonce"],
            ciphertext=envelope["ciphertext"],
            exp_iso=envelope["exp_iso"],
            bootstrap_key_id_b64_from_envelope=envelope["bootstrap_key_id_b64"],
        )


def test_in_memory_broker_storage_cas():
    s = InMemoryBrokerStorage()
    pairing_id = "00000000-1111-2222-3333-444444444444"
    entry_a = BrokerEntry(user_id="aaaa", pairing_key=b"\x01" * 32)
    # Phase 6 pending-pairing registry: reserve is now required
    # before complete (defense-in-depth alongside the broker handler's
    # 404 gate). Unreserved complete raises UnknownPairingIdError.
    with pytest.raises(UnknownPairingIdError):
        s.complete(pairing_id, entry_a)
    s.reserve(pairing_id)
    assert s.is_reserved(pairing_id)
    # Phase 5a-2.1: complete() returns True on first completion,
    # False on idempotent replay. Broker uses this to send 201 vs 200.
    assert s.complete(pairing_id, entry_a) is True
    # is_reserved stays True after completion so a re-POST gets to
    # the CAS layer, not bounced as 404 (Gemini 93 + Codex 93).
    assert s.is_reserved(pairing_id)
    # Idempotent second store with same values.
    assert s.complete(pairing_id, entry_a) is False
    assert s.fetch(pairing_id) == entry_a
    # Conflict on different values.
    entry_b = BrokerEntry(user_id="bbbb", pairing_key=b"\x02" * 32)
    with pytest.raises(BrokerStorageConflictError):
        s.complete(pairing_id, entry_b)


def test_client_create_pairing_qr_reserves_pairing_id(tmp_path):
    """Phase 6 / Codex 93 missing-test: confirm
    Client.create_pairing_qr(sender_broker_url=...) calls
    storage.reserve(pairing_id) so the broker handler's 404 gate
    accepts the subsequent envelope."""
    from unittest.mock import MagicMock, patch

    from syncler.client import Client

    storage = InMemoryBrokerStorage()
    # Build a Client with our storage. private_key_path needs a real
    # Ed25519 PEM; generate one to a tmp file.
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives.serialization import (
        Encoding, NoEncryption, PrivateFormat,
    )
    key_path = tmp_path / "test-sender.pem"
    key_pem = Ed25519PrivateKey.generate().private_bytes(
        Encoding.PEM, PrivateFormat.PKCS8, NoEncryption(),
    )
    key_path.write_bytes(key_pem)

    client = Client(
        sender_name="Test",
        private_key_path=str(key_path),
        broker_storage=storage,
    )
    client.set_sender_id("11111111-1111-1111-1111-111111111111")

    pairing_id = "22222222-3333-4444-5555-666666666666"
    server_response = MagicMock()
    server_response.json.return_value = {
        "pairing_id": pairing_id,
        "pairing_token": "tkn",
        "broker_url": "https://syncler.example.com/pair?token=tkn",
    }
    server_response.raise_for_status = lambda: None

    qr_path = tmp_path / "qr.png"
    with patch.object(client.session, "post", return_value=server_response):
        # Patch the QR renderer too so we don't need PIL/qrcode here.
        with patch("syncler.client._render_qr"):
            client.create_pairing_qr(
                sender_broker_url="https://broker.example.com/",
                out_path=str(qr_path),
            )

    assert storage.is_reserved(pairing_id), \
        "create_pairing_qr(sender_broker_url=...) must call storage.reserve(pairing_id)"


def test_client_create_pairing_qr_does_not_reserve_without_broker_url(tmp_path):
    """V1 manual-pairing path: when sender_broker_url is None, the
    Client should NOT call reserve() — there's no broker to gate.
    """
    from unittest.mock import MagicMock, patch

    from syncler.client import Client
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives.serialization import (
        Encoding, NoEncryption, PrivateFormat,
    )

    storage = InMemoryBrokerStorage()
    key_path = tmp_path / "test-sender.pem"
    key_path.write_bytes(Ed25519PrivateKey.generate().private_bytes(
        Encoding.PEM, PrivateFormat.PKCS8, NoEncryption(),
    ))

    client = Client(
        sender_name="Test",
        private_key_path=str(key_path),
        broker_storage=storage,
    )
    client.set_sender_id("11111111-1111-1111-1111-111111111111")

    pairing_id = "abcdef00-0000-0000-0000-000000000000"
    server_response = MagicMock()
    server_response.json.return_value = {
        "pairing_id": pairing_id,
        "pairing_token": "tkn",
        "broker_url": "https://syncler.example.com/pair?token=tkn",
    }
    server_response.raise_for_status = lambda: None

    with patch.object(client.session, "post", return_value=server_response):
        with patch("syncler.client._render_qr"):
            client.create_pairing_qr(out_path=str(tmp_path / "qr.png"))

    assert not storage.is_reserved(pairing_id)


def test_in_memory_broker_storage_reserve_idempotent():
    """Phase 6: reserving an already-reserved or already-completed
    pairing_id is a no-op (an immediate re-pair after wait_for_pairing
    returned would otherwise be racy)."""
    s = InMemoryBrokerStorage()
    pairing_id = "11111111-2222-3333-4444-555555555555"
    s.reserve(pairing_id)
    s.reserve(pairing_id)  # idempotent
    assert s.is_reserved(pairing_id)
    s.complete(pairing_id, BrokerEntry(user_id="xx", pairing_key=b"\x00" * 32))
    s.reserve(pairing_id)  # still a no-op after completion
    assert s.is_reserved(pairing_id)
    # Unknown ID is not reserved.
    assert not s.is_reserved("99999999-9999-9999-9999-999999999999")
