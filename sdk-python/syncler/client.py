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
from syncler.broker_storage import BrokerStorage
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
        broker_storage: "BrokerStorage | None" = None,
    ):
        self.base_url = base_url.rstrip("/")
        self.sender_name = sender_name
        self.private_key: Ed25519PrivateKey = load_private_key(private_key_path)
        self.public_key = public_key_raw(self.private_key)
        self.session = session or requests.Session()

        # State that gets set after register / pairing.
        self.sender_id: str | None = None
        self._pending_pairing_token: str | None = None
        self._pending_pairing_id: str | None = None
        self._latest_pairing: Pairing | None = None
        # V1 per-pairing AES-GCM key. Set automatically by
        # ``wait_for_pairing(...)`` on the V1.5 automated flow; can be
        # set manually via ``set_pairing(...)`` for the V1 manual flow.
        self.pairing_key: bytes | None = None
        # V1.5 Phase 5a-2: broker storage the sender's broker handler
        # writes to AND ``wait_for_pairing(...)`` polls from. None for
        # senders sticking with the V1 manual flow.
        self._broker_storage: "BrokerStorage | None" = broker_storage

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
        sender_broker_url: str | None = None,
    ) -> str:
        """Initiate a pairing and write a QR-code PNG of the broker URL.

        Returns the path to the generated PNG. Caller is responsible for
        showing the QR to the user.

        V1.5 (Phase 5a-2): pass ``sender_broker_url`` to enable the
        automated pairing flow. The Android app will POST the encrypted
        bootstrap envelope to that URL after the user confirms; pair
        ``Client.wait_for_pairing(...)`` against the same broker storage
        you mounted the broker handler on. Pre-requisite: register the
        sender's X25519 bootstrap key via
        ``Client.register_bootstrap_key(...)`` before calling this with
        ``sender_broker_url`` set — the server rejects otherwise.
        """
        self._require_sender_id()
        body: dict[str, Any] = {
            "sender_id": self._canonical_sender_id(),
            "ttl_seconds": ttl_seconds,
            "metadata": metadata or {},
        }
        if sender_broker_url is not None:
            body["sender_broker_url"] = sender_broker_url
        body["signature"] = b64(self.private_key.sign(canonical_json(body)))
        resp = self.session.post(f"{self.base_url}/v1/pairing/initiate", json=body, timeout=10)
        resp.raise_for_status()
        data = resp.json()
        self._pending_pairing_token = data["pairing_token"]
        self._pending_pairing_id = data["pairing_id"]
        # Reserve broker storage slot when running the automated flow.
        if sender_broker_url is not None and self._broker_storage is not None:
            self._broker_storage.reserve(self._pending_pairing_id)

        broker_url = data["broker_url"]
        out_path = out_path or f"syncler-pairing-{int(time.time())}.png"
        _render_qr(broker_url, out_path)
        return out_path

    def register_bootstrap_key(
        self,
        *,
        bootstrap_public_key_raw: bytes,
    ) -> str:
        """V1.5 Phase 5a-2: register (or rotate) the sender's X25519
        bootstrap public key. Required before calling
        ``create_pairing_qr(sender_broker_url=...)``.

        Signs the literal ASCII bytes ``"syncler-v1-bootstrap-key:"``
        (24 bytes) concatenated with the raw 32-byte X25519 public key,
        per `docs/crypto-spec.md §9.1`. Returns the base64
        ``bootstrap_key_id`` (server-computed SHA-256(pub)[:16]).
        """
        self._require_sender_id()
        if len(bootstrap_public_key_raw) != 32:
            raise ValueError("bootstrap_public_key_raw must be 32 bytes")
        sig_input = b"syncler-v1-bootstrap-key:" + bootstrap_public_key_raw
        signature = self.private_key.sign(sig_input)
        body = {
            "sender_id": self._canonical_sender_id(),
            "bootstrap_key": b64(bootstrap_public_key_raw),
            "bootstrap_key_signature": b64(signature),
        }
        resp = self.session.post(
            f"{self.base_url}/v1/senders/me/bootstrap-key", json=body, timeout=10,
        )
        resp.raise_for_status()
        return resp.json()["bootstrap_key_id"]

    def wait_for_pairing(
        self,
        *,
        timeout_seconds: int = 120,
        poll_interval_seconds: float = 1.0,
    ) -> Pairing:
        """V1.5 Phase 5a-2: poll the broker storage until the device's
        bootstrap POST lands, then automatically set the pairing on
        this client.

        Defaults: 120s timeout, 1s poll with ±20% jitter (jitter avoids
        synchronized polling when multiple pairings are in flight).
        Raises ``TimeoutError`` on deadline. Caller must have wired a
        broker storage via ``Client(...)``'s constructor — otherwise
        there's nothing to poll.
        """
        import random
        if self._broker_storage is None:
            raise SynclerError(
                "wait_for_pairing requires a broker storage. Pass one to "
                "Client(broker_storage=InMemoryBrokerStorage()) at construction time.",
            )
        if self._pending_pairing_id is None:
            raise SynclerError(
                "no pending pairing — call create_pairing_qr(sender_broker_url=...) first",
            )
        deadline = time.monotonic() + timeout_seconds
        while True:
            entry = self._broker_storage.fetch(self._pending_pairing_id)
            if entry is not None:
                pairing = Pairing(
                    pairing_id=self._pending_pairing_id,
                    user_id=entry.user_id,
                )
                self._latest_pairing = pairing
                self.pairing_key = entry.pairing_key
                return pairing
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(
                    f"wait_for_pairing exceeded {timeout_seconds}s timeout",
                )
            jitter = poll_interval_seconds * 0.2 * (random.random() * 2 - 1)
            sleep_for = min(remaining, max(0.05, poll_interval_seconds + jitter))
            time.sleep(sleep_for)

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
        renderer: str = "script",
        template: dict[str, Any] | None = None,
        card_type: str = "event",
        card_key_path: str | None = None,
    ) -> dict[str, Any]:
        """Publish a new plugin version. Returns the server response (with
        ``plugin_row_id`` which the sender then uses in messages).

        Phase 3a: pass ``renderer="template"`` plus a ``template`` block to
        ship a native Compose-rendered card instead of a WebView bundle.

        Phase 3b: pass ``card_type="live"`` plus a ``card_key_path`` (JSONPath
        yielding the stable key for the card) to enable persistent,
        upsertable live cards.
        """
        self._require_sender_id()
        body: dict[str, Any] = {
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
        # Mirror the server's `_publish_envelope` conditional inclusion.
        if renderer != "script":
            body["renderer"] = renderer
        if template is not None:
            body["template"] = template
        if card_type != "event":
            body["card_type"] = card_type
        if card_key_path is not None:
            body["card_key_path"] = card_key_path

        body["sender_signature"] = b64(self.private_key.sign(canonical_json(body)))
        resp = self.session.post(f"{self.base_url}/v1/plugins/publish", json=body, timeout=10)
        resp.raise_for_status()
        return resp.json()

    def upsert_card(
        self,
        *,
        user_id: str,
        plugin_id: str,
        card_key: str,
        encrypted_payload: bytes,
        nonce: bytes,
        sequence_number: int,
        expires_at: datetime,
    ) -> dict[str, Any]:
        """Upsert a persistent live card for a user. Fires card.upsert SSE.

        UUIDs are normalized via [_canon_uuid] before signing so an uppercase
        or braced caller input produces the same canonical bytes as the
        server-side `str(uuid.UUID(...))` form. Without this, a sender-side
        self-401 can sneak in on UUIDs that look fine to the eye but
        differ in case (Codex consultation 63 YELLOW follow-up).
        """
        self._require_sender_id()
        body: dict[str, Any] = {
            "sender_id": self._canonical_sender_id(),
            "user_id": _canon_uuid(user_id),
            "plugin_id": _canon_uuid(plugin_id),
            "card_key": card_key,
            "encrypted_payload": b64(encrypted_payload),
            "nonce": b64(nonce),
            "sequence_number": sequence_number,
            "expires_at": expires_at.isoformat().replace("+00:00", "Z"),
        }

        # Envelope for upsert includes card_type: live. `sequence_number`
        # stays as int here to match the server's canonical envelope
        # (see routers/cards.py:_build_upsert_envelope_bytes).
        envelope = dict(body)
        envelope["card_type"] = "live"
        body["envelope_signature"] = b64(self.private_key.sign(canonical_json(envelope)))

        resp = self.session.post(f"{self.base_url}/v1/cards/upsert", json=body, timeout=10)
        resp.raise_for_status()
        return resp.json()

    def delete_card(
        self,
        *,
        user_id: str,
        card_key: str,
        nonce: bytes | None = None,
        expires_at: datetime | None = None,
    ) -> None:
        """Delete a persistent live card for a specific user. Fires card.delete SSE.

        Phase 3b: `user_id` is REQUIRED in the canonical signed envelope so a
        delete signature for one user's card cannot be replayed against
        another user's card with the same (sender, card_key). Mirrors the
        server's `_build_delete_envelope_bytes`. Closes Codex consultation
        62 RED #5.

        Phase 12 (Codex 95): the envelope also binds a freshly-generated
        ``nonce`` + ``expires_at`` so a captured delete can't be replayed
        indefinitely against a future card with the same
        ``(sender_id, user_id, card_key)``. Defaults: ``nonce`` =
        ``os.urandom(12)``, ``expires_at`` = now + 24 h (server caps at
        48 h). Callers can pass either to control the freshness window
        explicitly — usually you'll just let the defaults ride.
        """
        import os

        self._require_sender_id()
        sender_id = self._canonical_sender_id()
        user_id_canonical = _canon_uuid(user_id)
        nonce_bytes = nonce if nonce is not None else os.urandom(12)
        if len(nonce_bytes) != 12:
            raise ValueError(f"nonce must be 12 bytes, got {len(nonce_bytes)}")
        # Server caps at 48h. Default at 24h gives a comfortable
        # freshness window that's well under the cap; callers can
        # override if they need shorter (or longer, up to 48h).
        if expires_at is None:
            expires_at = datetime.now(UTC) + timedelta(hours=24)
        expires_at_str = expires_at.astimezone(UTC).isoformat().replace("+00:00", "Z")
        nonce_b64_str = b64(nonce_bytes)
        envelope = canonical_json(
            {
                "sender_id": sender_id,
                "user_id": user_id_canonical,
                "card_key": card_key,
                "nonce": nonce_b64_str,
                "expires_at": expires_at_str,
            }
        )
        body = {
            "sender_id": sender_id,
            "user_id": user_id_canonical,
            "card_key": card_key,
            "nonce": nonce_b64_str,
            "expires_at": expires_at_str,
            "envelope_signature": b64(self.private_key.sign(envelope)),
        }
        resp = self.session.post(f"{self.base_url}/v1/cards/delete", json=body, timeout=10)
        resp.raise_for_status()

    # Accepted classifications for the optional ``reason`` field on revoke.
    # The host renders different UX per reason: silent for ``superseded``,
    # security alert with refuse-to-execute for ``compromised``, neutral
    # "no longer available" banner for ``sender_disabled``. ``unspecified``
    # is the conservative default for legacy revokes with no reason given;
    # callers SHOULD always supply a real classification.
    REVOCATION_REASONS = ("superseded", "compromised", "sender_disabled", "unspecified")

    def revoke_plugin(
        self,
        *,
        plugin_row_id: str,
        reason: str | None = None,
    ) -> None:
        """Revoke a previously-published plugin row, optionally with a
        classified reason.

        ``reason`` must be one of :attr:`REVOCATION_REASONS` or None. If
        supplied, the value is included in the canonical signed envelope so
        an in-flight MITM cannot strip a security classification down to a
        harmless one. The server's revoke service is monotone: a higher-
        severity reason can overwrite a lower one (``superseded`` ->
        ``compromised``), but downgrades are silently ignored.

        Returns None on success; raises for HTTP errors.
        """
        self._require_sender_id()
        if reason is not None and reason not in self.REVOCATION_REASONS:
            raise SynclerError(
                f"reason must be one of {self.REVOCATION_REASONS}; got {reason!r}"
            )
        envelope: dict[str, str] = {
            "sender_id": self._canonical_sender_id(),
            "plugin_row_id": _canon_uuid(plugin_row_id),
        }
        if reason is not None:
            envelope["reason"] = reason
        signature = self.private_key.sign(canonical_json(envelope))
        body: dict[str, Any] = dict(envelope)
        body["sender_signature"] = b64(signature)
        resp = self.session.post(
            f"{self.base_url}/v1/plugins/revoke", json=body, timeout=10
        )
        resp.raise_for_status()

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
