"""Phase 9b V2 envelope service.

Recipient-set classifier (spec §11.10), V2 signed-envelope-bytes
builder for Ed25519 verification, V2 pointer serializer for the
Message.encrypted_body_pointer column.

The classifier is the heart of §11.10 — eight rows of behavior
that distinguish duplicate / unknown / missing-active / stale-
version / extra-recently-revoked cases. Each row maps to a
deterministic HTTP status + error code that the tests in
test_phase9_recipient_classifier.py assert on.
"""

from __future__ import annotations

import base64
import json
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Device, User


REVOKED_GRACE_WINDOW = timedelta(minutes=5)


# ---------------------------------------------------------------------------
# Recipient-set classifier
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class RecipientSetOK:
    """The classifier accepted the recipient set; publish proceeds."""

    directory_version: int


@dataclass(frozen=True)
class RecipientSetError:
    """A row from §11.10 fired. ``http_status`` and ``code`` map directly to
    the response. ``current_directory_version`` is included for the 409
    body shape; ``missing_device_ids`` populated for stale_recipient_set."""

    http_status: int
    code: str
    message: str
    current_directory_version: int
    missing_device_ids: list[uuid.UUID]


RecipientSetResult = RecipientSetOK | RecipientSetError


async def classify_recipient_set(
    db: AsyncSession,
    *,
    user_id: uuid.UUID,
    recipient_envelopes: list[Any],
    sender_directory_version: int,
) -> RecipientSetResult:
    """Apply the §11.10 matrix.

    ``recipient_envelopes`` is the parsed list of
    ``RecipientEnvelopeWire`` from the Pydantic body — only
    ``.device_id`` is read here. Duplicates were already rejected at
    the schema layer (Pydantic ``reject_duplicate_devices`` validator),
    but we re-check defensively below in case a future caller bypasses
    Pydantic.

    All DB reads happen in the caller's transaction so the directory
    version + active-device set are mutually consistent (Codex 127
    guardrail #3).
    """
    now = datetime.now(UTC)
    incoming: list[uuid.UUID] = [env.device_id for env in recipient_envelopes]

    # (1) Duplicate device_id — defense in depth.
    if len(set(incoming)) != len(incoming):
        return RecipientSetError(
            http_status=400,
            code="duplicate_device_id",
            message="duplicate device_id in recipient_envelopes",
            current_directory_version=0,
            missing_device_ids=[],
        )

    # Single round-trip: read the user's directory_version + all
    # devices in one go.
    user_row = await db.execute(
        select(User.device_directory_version).where(User.id == user_id)
    )
    server_directory_version = user_row.scalar_one_or_none()
    if server_directory_version is None:
        # User doesn't exist. Bubble up as 404 at the caller.
        return RecipientSetError(
            http_status=404,
            code="user_not_found",
            message="user not found",
            current_directory_version=0,
            missing_device_ids=[],
        )

    devices_row = await db.execute(
        select(Device).where(Device.user_id == user_id)
    )
    all_devices = list(devices_row.scalars().all())

    active_ids = {
        d.id for d in all_devices
        if d.revoked_at is None and d.encryption_public_key is not None
    }
    recently_revoked_ids = {
        d.id for d in all_devices
        if d.revoked_at is not None and d.revoked_at > now - REVOKED_GRACE_WINDOW
    }
    all_known_ids = active_ids | recently_revoked_ids

    # (2) Unknown device — includes never-existed, belongs-to-different-user,
    # long-revoked (revoked_at <= now - 5m), or active-but-NULL-encryption_pubkey.
    unknown = [did for did in incoming if did not in all_known_ids]
    if unknown:
        return RecipientSetError(
            http_status=400,
            code="unknown_recipient",
            message=f"unknown or long-revoked devices: {unknown}",
            current_directory_version=server_directory_version,
            missing_device_ids=[],
        )

    # (3) directory_version too new — sender claims to know a future
    # state. Either lying or reading from a stale replica.
    if sender_directory_version > server_directory_version:
        return RecipientSetError(
            http_status=400,
            code="invalid_directory_version",
            message=(
                f"recipient_directory_version={sender_directory_version} > "
                f"server={server_directory_version}"
            ),
            current_directory_version=server_directory_version,
            missing_device_ids=[],
        )

    # (4) Missing active device — fanout incomplete.
    incoming_set = set(incoming)
    missing = sorted(active_ids - incoming_set)
    if missing:
        return RecipientSetError(
            http_status=409,
            code="stale_recipient_set",
            message=(
                f"active devices missing from recipient_envelopes: {missing}"
            ),
            current_directory_version=server_directory_version,
            missing_device_ids=missing,
        )

    # (5) directory_version stale AND active set differs would have
    # already returned in (4). If active set matches but version is
    # stale, the sender's view is on-the-edge — surface as stale so
    # they refetch and pin the new version.
    if sender_directory_version < server_directory_version:
        return RecipientSetError(
            http_status=409,
            code="stale_recipient_set",
            message=(
                f"recipient_directory_version={sender_directory_version} < "
                f"server={server_directory_version}"
            ),
            current_directory_version=server_directory_version,
            missing_device_ids=[],
        )

    # (6/7) Recently-revoked extras are tolerated; logging is the
    # caller's job (it has access to the audit logger / structured
    # log channel).
    return RecipientSetOK(directory_version=server_directory_version)


# ---------------------------------------------------------------------------
# Canonical signed envelope bytes
# ---------------------------------------------------------------------------


def _canonical_json(payload: dict[str, Any]) -> bytes:
    return json.dumps(
        payload,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def _serialize_recipient_envelopes(
    recipient_envelopes: list[Any],
) -> list[dict[str, str]]:
    """Sorted-by-device_id (lowercase UUID lex) list of envelope dicts —
    the canonical form per spec §11.8. Bytes pass through as base64 to
    match the wire JSON.
    """
    items = [
        {
            "device_id": str(env.device_id).lower(),
            "hpke_ciphertext": env.hpke_ciphertext,
            "hpke_kem_output": env.hpke_kem_output,
        }
        for env in recipient_envelopes
    ]
    items.sort(key=lambda item: item["device_id"])
    return items


def build_event_envelope_bytes(payload: Any) -> bytes:
    """Canonical Ed25519 signing input for ``envelope_kind="event"``.
    Spec §11.8.

    Caller passes ``MessageSendRequestV2``. base64 strings on the wire
    flow through unchanged so the sender + server compute the same bytes
    deterministically.
    """
    return _canonical_json(
        {
            "envelope_kind": "event",
            "expires_at": payload.expires_at.isoformat().replace("+00:00", "Z"),
            "min_plugin_version": payload.min_plugin_version or "",
            "payload_ciphertext": payload.payload_ciphertext,
            "payload_nonce": payload.payload_nonce,
            "plugin_id": str(payload.plugin_id),
            "protocol_version": payload.protocol_version,
            "recipient_directory_version": payload.recipient_directory_version,
            "recipient_envelopes": _serialize_recipient_envelopes(
                payload.recipient_envelopes
            ),
            "sender_id": str(payload.sender_id),
            "user_id": str(payload.user_id),
        }
    )


def build_live_card_upsert_envelope_bytes(payload: Any) -> bytes:
    """Canonical Ed25519 signing input for ``envelope_kind="live_card_upsert"``.
    Adds ``card_key``, ``card_type``, ``sequence_number`` to the event
    shape.
    """
    return _canonical_json(
        {
            "card_key": payload.card_key,
            "card_type": payload.card_type,
            "envelope_kind": "live_card_upsert",
            "expires_at": payload.expires_at.isoformat().replace("+00:00", "Z"),
            "min_plugin_version": payload.min_plugin_version or "",
            "payload_ciphertext": payload.payload_ciphertext,
            "payload_nonce": payload.payload_nonce,
            "plugin_id": str(payload.plugin_id),
            "protocol_version": payload.protocol_version,
            "recipient_directory_version": payload.recipient_directory_version,
            "recipient_envelopes": _serialize_recipient_envelopes(
                payload.recipient_envelopes
            ),
            "sender_id": str(payload.sender_id),
            "sequence_number": payload.sequence_number,
            "user_id": str(payload.user_id),
        }
    )


def build_card_patch_envelope_bytes(payload: Any) -> bytes:
    """V3 #16 — canonical Ed25519 signing input for
    ``envelope_kind="card_patch"``.

    Spec: docs/live-card-patch.md. The patches themselves are
    INSIDE the encrypted payload_ciphertext; this signing
    envelope only commits to the routing/sequence metadata +
    the encrypted bytes.
    """
    return _canonical_json(
        {
            "base_seq": payload.base_seq,
            "card_id": str(payload.card_id),
            "envelope_kind": "card_patch",
            "patch_seq": payload.patch_seq,
            "payload_ciphertext": payload.payload_ciphertext,
            "payload_nonce": payload.payload_nonce,
            "plugin_id": str(payload.plugin_id),
            "protocol_version": payload.protocol_version,
            "recipient_directory_version": payload.recipient_directory_version,
            "recipient_envelopes": _serialize_recipient_envelopes(
                payload.recipient_envelopes
            ),
            "sender_id": str(payload.sender_id),
            "user_id": str(payload.user_id),
        }
    )


def build_live_card_delete_envelope_bytes(payload: Any) -> bytes:
    """Canonical Ed25519 signing input for ``envelope_kind="live_card_delete"``.
    Spec §11.6.
    """
    return _canonical_json(
        {
            "card_key": payload.card_key,
            "envelope_kind": "live_card_delete",
            "expires_at": payload.expires_at.isoformat().replace("+00:00", "Z"),
            "nonce": payload.nonce,
            "plugin_id": str(payload.plugin_id),
            "protocol_version": payload.protocol_version,
            "sender_id": str(payload.sender_id),
            "user_id": str(payload.user_id),
        }
    )


# ---------------------------------------------------------------------------
# Pointer storage for Message.encrypted_body_pointer
# ---------------------------------------------------------------------------


def build_v2_pointer(payload: Any) -> str:
    """Serialize a V2 publish payload to the Message.encrypted_body_pointer
    column. Format: ``v2:<base64 canonical JSON>``.

    The pointer stores everything the inbox needs to reconstruct the
    full V2 envelope on fetch: payload_ciphertext, payload_nonce,
    recipient_envelopes, recipient_directory_version, envelope_signature.
    """
    blob = _canonical_json(
        {
            "payload_ciphertext": payload.payload_ciphertext,
            "payload_nonce": payload.payload_nonce,
            "recipient_envelopes": [
                {
                    "device_id": str(env.device_id),
                    "hpke_kem_output": env.hpke_kem_output,
                    "hpke_ciphertext": env.hpke_ciphertext,
                }
                for env in payload.recipient_envelopes
            ],
            "recipient_directory_version": payload.recipient_directory_version,
            "envelope_signature": payload.envelope_signature,
        }
    )
    return "v2:" + base64.b64encode(blob).decode("ascii")


@dataclass(frozen=True)
class V2PointerFields:
    payload_ciphertext: str
    payload_nonce: str
    recipient_envelopes: list[dict[str, str]]
    recipient_directory_version: int
    envelope_signature: str


def parse_v2_pointer(pointer: str) -> V2PointerFields:
    """Inverse of ``build_v2_pointer``. Raises ValueError on malformed input."""
    if not pointer.startswith("v2:"):
        raise ValueError("not a v2 pointer")
    blob = base64.b64decode(pointer[len("v2:"):])
    fields = json.loads(blob.decode("utf-8"))
    return V2PointerFields(
        payload_ciphertext=fields["payload_ciphertext"],
        payload_nonce=fields["payload_nonce"],
        recipient_envelopes=list(fields["recipient_envelopes"]),
        recipient_directory_version=int(fields["recipient_directory_version"]),
        envelope_signature=fields["envelope_signature"],
    )
