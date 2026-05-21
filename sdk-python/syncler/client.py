"""Sender client — the top-level API for backends sending into Syncler."""

from __future__ import annotations

import json
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

import requests
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def _canon_uuid(value: str) -> str:
    """Normalize a UUID string to lowercase no-brace canonical form.

    The server stores UUIDs as ``str(uuid.UUID(payload.xxx))`` which always
    lowercases and strips braces. The SDK signs over caller-supplied
    strings, so uppercase or braced inputs would silently mismatch
    (401 invalid signature). Normalize at the boundary.
    """
    return str(uuid.UUID(str(value)))

from syncler.preview import HOST_PREVIEW_KEY, validate_host_preview
from syncler.crypto import (
    assemble_envelope,
    b64,
    b64d,
    canonical_json,
    encrypt_payload,
    load_private_key,
    public_key_raw,
)
from syncler.errors import (
    PairingConflictError,
    PairingExpiredError,
    PluginRevokedError,
    RecipientUnreachableError,
    SignatureError,
    SynclerError,
)


DEFAULT_BASE_URL = "https://api.syncler.app"


@dataclass
class Pairing:
    pairing_id: str
    user_id: str  # the recipient's user UUID after pairing completes


@dataclass
class SendResult:
    message_id: str
    expires_at: str


class Client:
    """Thin sync wrapper over the Syncler server REST API.

    Usage::

        client = Client(
            base_url="https://api.syncler.app",
            sender_name="Trading Bot",
            private_key_path="~/.syncler/keys/trading.pem",
        )
        client.register_if_needed(contact="ops@example.com")

        qr_path = client.create_pairing_qr(ttl_seconds=300)
        pairing = client.wait_for_pairing(timeout_seconds=300)

        client.send_to(
            user_uuid=pairing.user_id,
            plugin_identifier="com.trading.app",
            payload={"pnl": 1234.56},
            min_plugin_version="1.0.0",
            # pairing_key produced by the device + sender during the
            # bootstrap exchange (M7+; for V1 dev set client.pairing_key
            # directly with a 32-byte shared secret).
        )
    """

    def __init__(
        self,
        *,
        sender_name: str,
        private_key_path: str,
        base_url: str = DEFAULT_BASE_URL,
        session: requests.Session | None = None,
    ):
        self.base_url = base_url.rstrip("/")
        self.sender_name = sender_name
        self.private_key: Ed25519PrivateKey = load_private_key(private_key_path)
        self.public_key = public_key_raw(self.private_key)
        self.session = session or requests.Session()

        # State that gets set after register / pairing.
        self.sender_id: str | None = None
        self._pending_pairing_token: str | None = None
        self._latest_pairing: Pairing | None = None
        # In V1 the per-pairing AES-GCM key needs to be exchanged with the
        # user's device during the pairing handshake. Until M7's bootstrap
        # is fully wired, callers can set this directly for dev.
        self.pairing_key: bytes | None = None

    # ---------------------------- Sender lifecycle -------------------------

    def _canonical_sender_id(self) -> str:
        if self.sender_id is None:
            raise SynclerError("sender_id not set")
        return _canon_uuid(self.sender_id)

    def register_if_needed(self, *, contact: str | None = None) -> str:
        """Register the sender public key once. Returns sender_id.

        Idempotent: if a server-side row already exists for this public key,
        the server returns 409. The caller is expected to persist
        ``self.sender_id`` between runs; in dev we cache to a JSON file
        beside the private key — but the SDK keeps that detail outside its
        responsibility. For now, the caller catches a 409 themselves.
        """
        body = {
            "public_key": b64(self.public_key),
            "name": self.sender_name,
            "contact": contact,
        }
        resp = self.session.post(f"{self.base_url}/v1/senders/register", json=body, timeout=10)
        if resp.status_code == 409:
            raise SynclerError("sender already registered (use the previously-issued sender_id)")
        resp.raise_for_status()
        self.sender_id = resp.json()["sender_id"]
        return self.sender_id

    def set_sender_id(self, sender_id: str) -> None:
        """Set sender_id from a persisted location (after first register)."""
        self.sender_id = sender_id

    # ---------------------------- Pairing ----------------------------------

    def create_pairing_qr(
        self,
        *,
        ttl_seconds: int = 300,
        metadata: dict[str, Any] | None = None,
        out_path: str | None = None,
    ) -> str:
        """Initiate a pairing and write a QR-code PNG of the broker URL.

        Returns the path to the generated PNG. Caller is responsible for
        showing the QR to the user.
        """
        self._require_sender_id()
        body = {
            "sender_id": self._canonical_sender_id(),
            "ttl_seconds": ttl_seconds,
            "metadata": metadata or {},
        }
        body["signature"] = b64(self.private_key.sign(canonical_json(body)))
        resp = self.session.post(f"{self.base_url}/v1/pairing/initiate", json=body, timeout=10)
        resp.raise_for_status()
        data = resp.json()
        self._pending_pairing_token = data["pairing_token"]

        broker_url = data["broker_url"]
        out_path = out_path or f"syncler-pairing-{int(time.time())}.png"
        _render_qr(broker_url, out_path)
        return out_path

    def wait_for_pairing(
        self,
        *,
        timeout_seconds: int = 300,
        poll_interval_seconds: int = 2,
    ) -> Pairing:
        """Poll until the user completes pairing (server holds pending row
        until POST /pairing/complete is called by the user device).

        V1 implementation: poll /v1/senders/{sender_id}/pairings (TODO: this
        list endpoint needs to be added server-side). For now this is a
        placeholder that requires the user_id to be supplied out-of-band
        once pairing completes (e.g. the user copies it from the device
        UI). Replace with proper polling once the server endpoint lands.
        """
        # TODO(M11): once a public "did this token complete?" endpoint exists,
        # poll it here. For V1 dev the caller passes user_id manually.
        raise NotImplementedError(
            "wait_for_pairing requires a server endpoint that's part of M11 polish — "
            "for dev use, ask the user for the user_id printed on the device after pairing."
        )

    def set_pairing(self, user_id: str, pairing_key: bytes) -> None:
        """Dev-mode shortcut: set the paired user_id and per-pairing AES key.

        Production flow has the device exchange this key with the sender
        during the pairing handshake (M7+).
        """
        if len(pairing_key) != 32:
            raise ValueError("pairing_key must be 32 bytes")
        self._latest_pairing = Pairing(pairing_id="", user_id=user_id)
        self.pairing_key = pairing_key

    # ---------------------------- Sending ----------------------------------

    def send_to(
        self,
        *,
        user_uuid: str,
        plugin_identifier: str,
        plugin_id: str,
        payload: dict[str, Any],
        min_plugin_version: str = "",
        ttl_seconds: int = 7 * 24 * 3600,
    ) -> SendResult:
        # Canonicalize UUIDs so signature bytes match server reconstruction.
        user_uuid = _canon_uuid(user_uuid)
        plugin_id = _canon_uuid(plugin_id)
        """Encrypt the payload, sign the envelope, POST to /messages/send.

        ``plugin_id`` is the row-UUID of a specific published version (from
        ``client.publish_plugin``'s return), NOT the plugin_identifier.
        ``plugin_identifier`` is the stable sender-chosen string.
        """
        self._require_sender_id()
        if self.pairing_key is None:
            raise SynclerError("no pairing key set; call set_pairing() first")

        # Validate the optional hostPreview block in the payload. The host
        # uses it to render the inbox row natively without invoking the
        # plugin. Missing block → fallback row; malformed block → raises so
        # the sender catches the mistake at send time rather than the user
        # seeing "New message from X" with no detail.
        if HOST_PREVIEW_KEY in payload:
            validate_host_preview(payload[HOST_PREVIEW_KEY])

        expires_at = (datetime.now(UTC) + timedelta(seconds=ttl_seconds)).isoformat().replace("+00:00", "Z")
        plaintext = json.dumps(payload, separators=(",", ":")).encode("utf-8")

        nonce, ciphertext = encrypt_payload(
            pairing_key=self.pairing_key,
            plaintext=plaintext,
            aad=_aad(
                sender_id=self._canonical_sender_id(),
                user_id=user_uuid,
                plugin_id=plugin_id,
                min_plugin_version=min_plugin_version,
                expires_at=expires_at,
            ),
        )
        encrypted_body_b64 = b64(ciphertext)
        nonce_b64 = b64(nonce)

        envelope = assemble_envelope(
            sender_id=self._canonical_sender_id(),
            user_id=user_uuid,
            plugin_id=plugin_id,
            min_plugin_version=min_plugin_version,
            expires_at=expires_at,
            encrypted_body_b64=encrypted_body_b64,
            nonce_b64=nonce_b64,
        )
        signature = self.private_key.sign(envelope)

        body = {
            "sender_id": self._canonical_sender_id(),
            "user_id": user_uuid,
            "plugin_id": plugin_id,
            "encrypted_body": encrypted_body_b64,
            "nonce": nonce_b64,
            "envelope_signature": b64(signature),
            "min_plugin_version": min_plugin_version or None,
            "expires_at": expires_at,
        }

        resp = self.session.post(f"{self.base_url}/v1/messages/send", json=body, timeout=10)
        if resp.status_code == 401:
            raise SignatureError("server rejected envelope signature")
        if resp.status_code == 410:
            detail = resp.json().get("detail", "")
            if "plugin" in detail:
                raise PluginRevokedError(detail)
            raise RecipientUnreachableError(detail or "recipient unreachable")
        if resp.status_code == 409:
            raise SynclerError("nonce replay or pairing conflict")
        resp.raise_for_status()
        data = resp.json()
        return SendResult(message_id=data["message_id"], expires_at=data["expires_at"])

    # ---------------------------- Plugin publishing ------------------------

    def publish_plugin(
        self,
        *,
        plugin_identifier: str,
        version: str,
        manifest_hash: bytes,
        bundle_hash: bytes,
        bundle_signature: bytes,
        signed_bundle_url: str,
        capabilities: list[str],
        endpoints: list[str],
    ) -> dict[str, Any]:
        """Publish a new plugin version. Returns the server response (with
        ``plugin_row_id`` which the sender then uses in messages).
        """
        self._require_sender_id()
        body = {
            "sender_id": self._canonical_sender_id(),
            "plugin_identifier": plugin_identifier,
            "version": version,
            "manifest_hash": b64(manifest_hash),
            "bundle_hash": b64(bundle_hash),
            "signature": b64(bundle_signature),
            "signed_bundle_url": signed_bundle_url,
            "capabilities": capabilities,
            "endpoints": endpoints,
        }
        body["sender_signature"] = b64(self.private_key.sign(canonical_json(body)))
        resp = self.session.post(f"{self.base_url}/v1/plugins/publish", json=body, timeout=10)
        resp.raise_for_status()
        return resp.json()

    # ---------------------------- Helpers ----------------------------------

    def _require_sender_id(self) -> None:
        if self.sender_id is None:
            raise SynclerError(
                "sender not registered; call register_if_needed() or set_sender_id()"
            )


def _aad(
    *, sender_id: str, user_id: str, plugin_id: str, min_plugin_version: str, expires_at: str
) -> bytes:
    return canonical_json(
        {
            "sender_id": sender_id,
            "user_id": user_id,
            "plugin_id": plugin_id,
            "min_plugin_version": min_plugin_version,
            "expires_at": expires_at,
        }
    )


def _render_qr(text: str, path: str) -> None:
    import qrcode

    qr = qrcode.QRCode(border=2)
    qr.add_data(text)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")
    img.save(path)
