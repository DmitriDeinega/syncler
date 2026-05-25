"""Phase 9b unit tests for the HPKE crypto module.

Round-trip + canonical-info determinism. Wire-vector fixtures land
in a separate ``test_phase9_vectors`` module once we have byte-for-byte
fixtures pinned in ``server/tests/fixtures/phase9_vectors.json``.
"""

from __future__ import annotations

import pytest

from app.crypto.hpke import (
    CEK_BYTES,
    HPKE_KEM_OUTPUT_BYTES,
    HPKE_OUTPUT_BYTES,
    HPKE_WRAP_BYTES,
    build_hpke_info,
    build_payload_aad,
    decrypt_payload,
    encrypt_payload,
    generate_cek,
    generate_x25519_keypair,
    open_cek_for_device,
    seal_cek_for_device,
    sha256_hex,
)


SENDER_ID = "11111111-1111-1111-1111-111111111111"
USER_ID = "22222222-2222-2222-2222-222222222222"
PLUGIN_ID = "33333333-3333-3333-3333-333333333333"
DEVICE_A = "44444444-4444-4444-4444-444444444444"
DEVICE_B = "55555555-5555-5555-5555-555555555555"
DEVICE_C = "66666666-6666-6666-6666-666666666666"
EXPIRES_AT = "2026-06-01T00:00:00Z"


def _publish_inputs(payload: bytes = b"hello-world"):
    cek = generate_cek()
    payload_nonce = b"\x00" * 12
    payload_aad = build_payload_aad(
        protocol_version=2,
        envelope_kind="event",
        sender_id=SENDER_ID,
        user_id=USER_ID,
        plugin_id=PLUGIN_ID,
        expires_at=EXPIRES_AT,
        min_plugin_version="",
    )
    payload_ct = encrypt_payload(
        payload=payload, cek=cek, payload_nonce=payload_nonce, payload_aad=payload_aad
    )
    return cek, payload_nonce, payload_aad, payload_ct


def _info_for(device_id: str, payload_ct: bytes):
    return build_hpke_info(
        protocol_version=2,
        envelope_kind="event",
        sender_id=SENDER_ID,
        user_id=USER_ID,
        plugin_id=PLUGIN_ID,
        expires_at=EXPIRES_AT,
        min_plugin_version="",
        payload_nonce_b64="AAAAAAAAAAAAAAAA",
        payload_ciphertext_sha256_hex=sha256_hex(payload_ct),
        device_id=device_id,
    )


def test_round_trip_single_recipient():
    sk, pk = generate_x25519_keypair()
    cek, nonce, aad, ct = _publish_inputs()
    info = _info_for(DEVICE_A, ct)

    env = seal_cek_for_device(
        cek=cek, recipient_public_key=pk, info=info, device_id=DEVICE_A
    )
    assert len(env.hpke_kem_output) == HPKE_KEM_OUTPUT_BYTES
    assert len(env.hpke_ciphertext) == HPKE_WRAP_BYTES
    assert env.device_id == DEVICE_A

    recovered_cek = open_cek_for_device(
        private_key=sk,
        hpke_kem_output=env.hpke_kem_output,
        hpke_ciphertext=env.hpke_ciphertext,
        info=info,
    )
    assert recovered_cek == cek

    assert decrypt_payload(
        payload_ciphertext=ct, cek=recovered_cek, payload_nonce=nonce, payload_aad=aad
    ) == b"hello-world"


def test_multi_recipient_independent_decrypt():
    """Three devices each get their own wrap of the SAME CEK; each
    device opens only its own envelope but recovers the same CEK."""
    devices = []
    for device_id in (DEVICE_A, DEVICE_B, DEVICE_C):
        sk, pk = generate_x25519_keypair()
        devices.append((device_id, sk, pk))

    cek, nonce, aad, ct = _publish_inputs()

    envelopes = [
        seal_cek_for_device(
            cek=cek,
            recipient_public_key=pk,
            info=_info_for(device_id, ct),
            device_id=device_id,
        )
        for (device_id, _sk, pk) in devices
    ]

    for (device_id, sk, _pk), env in zip(devices, envelopes):
        recovered = open_cek_for_device(
            private_key=sk,
            hpke_kem_output=env.hpke_kem_output,
            hpke_ciphertext=env.hpke_ciphertext,
            info=_info_for(device_id, ct),
        )
        assert recovered == cek


def test_cross_device_open_rejected():
    """Device B's private key cannot open device A's HPKE wrap."""
    sk_a, pk_a = generate_x25519_keypair()
    sk_b, pk_b = generate_x25519_keypair()
    cek, _nonce, _aad, ct = _publish_inputs()

    env_a = seal_cek_for_device(
        cek=cek, recipient_public_key=pk_a, info=_info_for(DEVICE_A, ct), device_id=DEVICE_A
    )
    with pytest.raises(Exception):
        open_cek_for_device(
            private_key=sk_b,
            hpke_kem_output=env_a.hpke_kem_output,
            hpke_ciphertext=env_a.hpke_ciphertext,
            info=_info_for(DEVICE_A, ct),
        )


def test_info_mismatch_rejected():
    """device_id in info is part of the canonical bytes — using device A's
    wrap with device B's info must fail open."""
    sk_a, pk_a = generate_x25519_keypair()
    cek, _nonce, _aad, ct = _publish_inputs()

    env_a = seal_cek_for_device(
        cek=cek, recipient_public_key=pk_a, info=_info_for(DEVICE_A, ct), device_id=DEVICE_A
    )
    with pytest.raises(Exception):
        open_cek_for_device(
            private_key=sk_a,
            hpke_kem_output=env_a.hpke_kem_output,
            hpke_ciphertext=env_a.hpke_ciphertext,
            info=_info_for(DEVICE_B, ct),  # wrong device_id binds
        )


def test_payload_sha256_binds():
    """Swapping payload_ciphertext (and thus its sha256) into a fresh info
    invalidates the HPKE open."""
    sk_a, pk_a = generate_x25519_keypair()
    cek, _nonce, _aad, ct = _publish_inputs(b"original")
    other_ct = encrypt_payload(
        payload=b"swapped",
        cek=generate_cek(),
        payload_nonce=b"\x01" * 12,
        payload_aad=build_payload_aad(
            protocol_version=2,
            envelope_kind="event",
            sender_id=SENDER_ID,
            user_id=USER_ID,
            plugin_id=PLUGIN_ID,
            expires_at=EXPIRES_AT,
            min_plugin_version="",
        ),
    )

    env_a = seal_cek_for_device(
        cek=cek, recipient_public_key=pk_a, info=_info_for(DEVICE_A, ct), device_id=DEVICE_A
    )
    with pytest.raises(Exception):
        # Same env but the info now claims a different payload ciphertext.
        open_cek_for_device(
            private_key=sk_a,
            hpke_kem_output=env_a.hpke_kem_output,
            hpke_ciphertext=env_a.hpke_ciphertext,
            info=_info_for(DEVICE_A, other_ct),
        )


def test_canonical_info_byte_stable():
    """Same inputs → byte-identical canonical info. Cross-platform vectors
    rely on this; if it ever changes, the Android client breaks."""
    a = build_hpke_info(
        protocol_version=2,
        envelope_kind="event",
        sender_id=SENDER_ID,
        user_id=USER_ID,
        plugin_id=PLUGIN_ID,
        expires_at=EXPIRES_AT,
        min_plugin_version="1.2.3",
        payload_nonce_b64="AAAAAAAAAAAAAAAA",
        payload_ciphertext_sha256_hex="deadbeef" * 8,
        device_id=DEVICE_A,
    )
    b = build_hpke_info(
        protocol_version=2,
        envelope_kind="event",
        sender_id=SENDER_ID,
        user_id=USER_ID,
        plugin_id=PLUGIN_ID,
        expires_at=EXPIRES_AT,
        min_plugin_version="1.2.3",
        payload_nonce_b64="AAAAAAAAAAAAAAAA",
        payload_ciphertext_sha256_hex="deadbeef" * 8,
        device_id=DEVICE_A,
    )
    assert a == b
    # Sorted keys: first key alphabetically should be card_key (absent here)
    # OR device_id (present).
    assert a.startswith(b'{"device_id":')


def test_card_fields_omitted_for_event():
    """`envelope_kind="event"` info MUST NOT contain card_key / card_type /
    sequence_number — even at the byte level."""
    info = build_hpke_info(
        protocol_version=2,
        envelope_kind="event",
        sender_id=SENDER_ID,
        user_id=USER_ID,
        plugin_id=PLUGIN_ID,
        expires_at=EXPIRES_AT,
        min_plugin_version="",
        payload_nonce_b64="AAAAAAAAAAAAAAAA",
        payload_ciphertext_sha256_hex="00" * 32,
        device_id=DEVICE_A,
    )
    assert b"card_key" not in info
    assert b"card_type" not in info
    assert b"sequence_number" not in info


def test_card_fields_present_for_upsert():
    info = build_hpke_info(
        protocol_version=2,
        envelope_kind="live_card_upsert",
        sender_id=SENDER_ID,
        user_id=USER_ID,
        plugin_id=PLUGIN_ID,
        expires_at=EXPIRES_AT,
        min_plugin_version="",
        payload_nonce_b64="AAAAAAAAAAAAAAAA",
        payload_ciphertext_sha256_hex="00" * 32,
        device_id=DEVICE_A,
        card_key="match-123",
        card_type="standard_card",
        sequence_number=17,
    )
    assert b'"card_key":"match-123"' in info
    assert b'"card_type":"standard_card"' in info
    assert b'"sequence_number":17' in info


def test_upsert_info_requires_card_fields():
    with pytest.raises(ValueError):
        build_hpke_info(
            protocol_version=2,
            envelope_kind="live_card_upsert",
            sender_id=SENDER_ID,
            user_id=USER_ID,
            plugin_id=PLUGIN_ID,
            expires_at=EXPIRES_AT,
            min_plugin_version="",
            payload_nonce_b64="AAAAAAAAAAAAAAAA",
            payload_ciphertext_sha256_hex="00" * 32,
            device_id=DEVICE_A,
            # missing card_key / card_type / sequence_number
        )


def test_hpke_output_size_constants():
    """If PyCA ever changes the underlying suite, fail loud here rather
    than silently mis-slicing the wire."""
    sk, pk = generate_x25519_keypair()
    cek = generate_cek()
    assert len(cek) == CEK_BYTES
    info = _info_for(DEVICE_A, b"deadbeef")
    env = seal_cek_for_device(
        cek=cek, recipient_public_key=pk, info=info, device_id=DEVICE_A
    )
    total = env.hpke_kem_output + env.hpke_ciphertext
    assert len(total) == HPKE_OUTPUT_BYTES
