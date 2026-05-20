"""Ed25519 verification helpers for message and plugin signatures."""

from __future__ import annotations

import copy
import json
from typing import Any, Final

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

MANIFEST_SIGNATURE_FIELD: Final[str] = "signature"
MANIFEST_BUNDLE_HASH_FIELD: Final[str] = "bundleHash"


def verify_message_envelope(public_key: bytes, envelope: bytes, signature: bytes) -> bool:
    """Verify an Ed25519 signature over a message envelope."""

    return _verify_ed25519(public_key, envelope, signature)


def verify_plugin_bundle(sender_public_key: bytes, canonical_manifest: bytes, signature: bytes) -> bool:
    """Verify an Ed25519 signature over canonical plugin-bundle bytes."""

    return _verify_ed25519(sender_public_key, canonical_manifest, signature)


def canonical_manifest_for_signing(manifest: dict[str, Any]) -> bytes:
    """Return V1 plugin signing bytes: canonical manifest without signature plus bundle hash bytes."""

    manifest_without_signature = copy.deepcopy(manifest)
    manifest_without_signature.pop(MANIFEST_SIGNATURE_FIELD, None)

    bundle_hash_hex = manifest_without_signature.get(MANIFEST_BUNDLE_HASH_FIELD)
    if not isinstance(bundle_hash_hex, str):
        raise ValueError("manifest bundleHash must be a hex string")

    try:
        bundle_hash = bytes.fromhex(bundle_hash_hex)
    except ValueError as exc:
        raise ValueError("manifest bundleHash must be valid hex") from exc

    canonical_json = json.dumps(
        manifest_without_signature,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return canonical_json + bundle_hash


def _verify_ed25519(public_key: bytes, payload: bytes, signature: bytes) -> bool:
    try:
        key = Ed25519PublicKey.from_public_bytes(public_key)
        key.verify(signature, payload)
    except (InvalidSignature, ValueError):
        return False
    return True

