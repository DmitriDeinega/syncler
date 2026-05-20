from __future__ import annotations

import pytest
from argon2.low_level import Type, hash_secret_raw
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.crypto.aead import assemble_aad, assemble_envelope, decrypt_message_body
from app.crypto.argon2 import ARGON2_PARAMS_V1, params_for_version, verify_auth_key_hash
from app.crypto.hkdf import derive_pairing_key
from app.crypto.nonce import NonceRegistry, generate_nonce
from app.crypto.signatures import canonical_manifest_for_signing, verify_message_envelope, verify_plugin_bundle
from app.crypto.wire import pack_message, unpack_message


ARGON2_PASSWORD = b"syncler-test-password"
ARGON2_SALT = bytes.fromhex("00112233445566778899aabbccddeeff")
ARGON2_HASH_HEX = (
    "e23ed7b136661e69f2424d8440777943827d9981e2fb409d69e48bce72dd7f82"
    "c8327524d69330d1993ba67e26a3576718d29f0602e44a881d924ca36836699c"
)
AUTH_KEY_HEX = "e23ed7b136661e69f2424d8440777943827d9981e2fb409d69e48bce72dd7f82"
WRAP_KEY_HEX = "c8327524d69330d1993ba67e26a3576718d29f0602e44a881d924ca36836699c"

MASTER_KEY = bytes.fromhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
SENDER_ID = b"sender-alpha"
PAIRING_KEY_HEX = "f6ed649481dd8a5ffc57401b816803fba79556731c5c9ff53be49f7862f8cb8e"

AAD_FIELDS = {
    "sender_id": "sender-alpha",
    "user_id": "user-123",
    "plugin_id": "plugin.weather",
    "min_plugin_version": "1.0.0",
    "expires_at": "2026-05-20T00:00:00Z",
}
AAD_BYTES = (
    b'{"expires_at":"2026-05-20T00:00:00Z","min_plugin_version":"1.0.0",'
    b'"plugin_id":"plugin.weather","sender_id":"sender-alpha","user_id":"user-123"}'
)
ENVELOPE_FIELDS = AAD_FIELDS | {
    "encrypted_body": "Y2lwaGVydGV4dC1zYW1wbGU=",
    "nonce": "EBESExQVFhcYGRob",
}
ENVELOPE_BYTES = (
    b'{"encrypted_body":"Y2lwaGVydGV4dC1zYW1wbGU=","expires_at":"2026-05-20T00:00:00Z",'
    b'"min_plugin_version":"1.0.0","nonce":"EBESExQVFhcYGRob",'
    b'"plugin_id":"plugin.weather","sender_id":"sender-alpha","user_id":"user-123"}'
)
NONCE = bytes.fromhex("101112131415161718191a1b")
PLAINTEXT = b'{"temperature_c":21}'

MANIFEST = {
    "name": "Weather Plugin",
    "pluginId": "plugin.weather",
    "version": "1.0.0",
    "bundleHash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    "signature": "ignored",
}
CANONICAL_MANIFEST_HEX = (
    "7b2262756e646c6548617368223a223966383664303831383834633764363539613266656161306335356164303135"
    "6133626634663162326230623832326364313564366331356230663030613038222c226e616d65223a225765617468"
    "657220506c7567696e222c22706c7567696e4964223a22706c7567696e2e77656174686572222c2276657273696f"
    "6e223a22312e302e30227d9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
)
ED25519_PRIVATE_SEED = bytes.fromhex("1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100")
ED25519_PUBLIC_KEY_HEX = "712651f450ba05b63898b99ef5f7ba45632e8e2527f7f715cd671ec4024cc51e"
ED25519_SIGNATURE_HEX = (
    "3d3a4963d6390f4392b36dac13938cadf015da019c6d0b2004e701656f544f6b"
    "336bb9da81ef4fde0b392f3ac33884c7dbb40dcd6f0ac30f1bbc06a464e68a06"
)


def test_argon2_params_and_auth_key_hash_vector() -> None:
    derived_hash = hash_secret_raw(
        secret=ARGON2_PASSWORD,
        salt=ARGON2_SALT,
        time_cost=ARGON2_PARAMS_V1["time_cost"],
        memory_cost=ARGON2_PARAMS_V1["m_cost"],
        parallelism=ARGON2_PARAMS_V1["parallelism"],
        hash_len=ARGON2_PARAMS_V1["hash_len"],
        type=Type.ID,
    )

    assert derived_hash == bytes.fromhex(ARGON2_HASH_HEX)
    assert derived_hash[:32] == bytes.fromhex(AUTH_KEY_HEX)
    assert derived_hash[32:] == bytes.fromhex(WRAP_KEY_HEX)
    assert params_for_version(1) == ARGON2_PARAMS_V1
    assert verify_auth_key_hash(derived_hash[:32], bytes.fromhex(AUTH_KEY_HEX))
    assert not verify_auth_key_hash(derived_hash[:32], bytes.fromhex(WRAP_KEY_HEX))

    with pytest.raises(ValueError):
        params_for_version(2)


def test_hkdf_pairing_key_vector() -> None:
    # Computed with: py -3.14 - < vector script in docs/crypto-spec.md
    pairing_key = derive_pairing_key(MASTER_KEY, SENDER_ID)

    assert len(pairing_key) == 32
    assert pairing_key == bytes.fromhex(PAIRING_KEY_HEX)
    assert derive_pairing_key(MASTER_KEY, SENDER_ID) == pairing_key
    assert derive_pairing_key(MASTER_KEY, b"sender-beta") != pairing_key


def test_signatures_verify_plugin_bundle_and_message_envelope() -> None:
    private_key = Ed25519PrivateKey.generate()
    public_key = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    canonical_manifest = canonical_manifest_for_signing(MANIFEST)
    signature = private_key.sign(canonical_manifest)

    assert canonical_manifest == bytes.fromhex(CANONICAL_MANIFEST_HEX)
    assert verify_plugin_bundle(public_key, canonical_manifest, signature)
    assert verify_message_envelope(public_key, canonical_manifest, signature)
    assert not verify_plugin_bundle(public_key, canonical_manifest, signature[:-1] + b"\x00")

    tampered_manifest = dict(MANIFEST)
    tampered_manifest["version"] = "1.0.1"
    assert not verify_plugin_bundle(public_key, canonical_manifest_for_signing(tampered_manifest), signature)


def test_signatures_fixed_ed25519_vector() -> None:
    private_key = Ed25519PrivateKey.from_private_bytes(ED25519_PRIVATE_SEED)
    public_key = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    canonical_manifest = canonical_manifest_for_signing(MANIFEST)
    signature = private_key.sign(canonical_manifest)

    assert public_key == bytes.fromhex(ED25519_PUBLIC_KEY_HEX)
    assert signature == bytes.fromhex(ED25519_SIGNATURE_HEX)
    assert verify_plugin_bundle(public_key, canonical_manifest, signature)


def test_aead_encrypt_decrypt_and_vectors() -> None:
    pairing_key = bytes.fromhex(PAIRING_KEY_HEX)
    aad = assemble_aad(AAD_FIELDS)
    ciphertext_with_tag = AESGCM(pairing_key).encrypt(NONCE, PLAINTEXT, aad)
    wire = pack_message(NONCE, ciphertext_with_tag)

    assert aad == AAD_BYTES
    assert decrypt_message_body(pairing_key, wire, aad) == PLAINTEXT
    # AES-GCM is deterministic given fixed key/nonce/plaintext/aad — sanity check.
    assert AESGCM(pairing_key).encrypt(NONCE, PLAINTEXT, aad) == ciphertext_with_tag

    with pytest.raises(InvalidTag):
        decrypt_message_body(pairing_key, wire, aad + b"tampered")
    with pytest.raises(ValueError):
        pack_message(NONCE[:-1], ciphertext_with_tag)
    with pytest.raises(ValueError):
        assemble_aad({"sender_id": "sender-alpha"})
    with pytest.raises(ValueError):
        assemble_aad(AAD_FIELDS | {"extra": "not-in-v1"})


def test_assemble_envelope_canonical_and_validation() -> None:
    assert assemble_envelope(ENVELOPE_FIELDS) == ENVELOPE_BYTES

    with pytest.raises(ValueError):
        assemble_envelope(AAD_FIELDS)  # missing encrypted_body + nonce
    with pytest.raises(ValueError):
        assemble_envelope(ENVELOPE_FIELDS | {"unrelated": "x"})


def test_nonce_registry_detects_replay_and_isolates_senders() -> None:
    registry = NonceRegistry(window=2)
    nonce = generate_nonce()

    assert len(nonce) == 12
    assert not registry.seen(b"sender-a", nonce)
    assert registry.seen(b"sender-a", nonce)
    assert not registry.seen(b"sender-b", nonce)

    assert not registry.seen(b"sender-a", b"111111111111")
    assert not registry.seen(b"sender-a", b"222222222222")
    assert not registry.seen(b"sender-a", nonce)


def test_wire_pack_unpack_round_trip() -> None:
    pairing_key = bytes.fromhex(PAIRING_KEY_HEX)
    aad = assemble_aad(AAD_FIELDS)
    ciphertext_with_tag = AESGCM(pairing_key).encrypt(NONCE, PLAINTEXT, aad)
    wire = pack_message(NONCE, ciphertext_with_tag)
    unpacked_nonce, unpacked_ciphertext = unpack_message(wire)

    assert unpacked_nonce == NONCE
    assert unpacked_ciphertext == ciphertext_with_tag

    with pytest.raises(ValueError):
        unpack_message(wire[:27])
