import base64
import binascii
import importlib.util
import re
from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_serializer, field_validator, model_validator

EmailField = (
    EmailStr
    if importlib.util.find_spec("email_validator")
    else Annotated[str, Field(pattern=r"^[^@\s]+@[^@\s]+\.[^@\s]+$")]
)


def decode_base64(value: str, *, field_name: str, exact: int | None = None, minimum: int | None = None) -> bytes:
    try:
        decoded = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError(f"{field_name} must be valid base64") from exc

    if exact is not None and len(decoded) != exact:
        raise ValueError(f"{field_name} must decode to {exact} bytes")
    if minimum is not None and len(decoded) < minimum:
        raise ValueError(f"{field_name} must decode to at least {minimum} bytes")

    return decoded


class SignupRequest(BaseModel):
    email: EmailField
    auth_key_hash: str
    encrypted_master_key: str
    auth_salt: str
    argon2_params_version: Annotated[int, Field(ge=1)]
    # Phase 8d (docs/crypto-spec.md §10.9): the master-key wrap AAD
    # binds {"auth_salt_b64": ..., "user_id": ...}. The client must
    # know the user_id BEFORE wrapping, so we accept a client-
    # generated UUID v4. None → server generates (pre-Phase-8d
    # clients). UUID format is validated by the type system; the
    # uniqueness constraint on users.id catches collisions.
    user_id: UUID | None = None

    @field_validator("auth_key_hash")
    @classmethod
    def validate_auth_key_hash(cls, value: str) -> str:
        decode_base64(value, field_name="auth_key_hash", exact=32)
        return value

    @field_validator("encrypted_master_key")
    @classmethod
    def validate_encrypted_master_key(cls, value: str) -> str:
        decode_base64(value, field_name="encrypted_master_key", minimum=32)
        return value

    @field_validator("auth_salt")
    @classmethod
    def validate_auth_salt(cls, value: str) -> str:
        decode_base64(value, field_name="auth_salt", exact=16)
        return value


class SignupResponse(BaseModel):
    user_id: UUID
    created_at: datetime


class PreLoginRequest(BaseModel):
    email: EmailField


class PreLoginResponse(BaseModel):
    auth_salt: str
    argon2_params_version: int


class LoginRequest(BaseModel):
    email: EmailField
    auth_key_hash: str

    @field_validator("auth_key_hash")
    @classmethod
    def validate_auth_key_hash(cls, value: str) -> str:
        decode_base64(value, field_name="auth_key_hash", exact=32)
        return value


class LoginResponse(BaseModel):
    user_id: UUID
    session_token: str
    encrypted_master_key: str
    auth_salt: str
    argon2_params_version: int
    # Phase 8: master-key generation the wrapped MK and the user-state
    # blob are encrypted under. The client checks this against its
    # locally-persisted high-water mark BEFORE unwrapping the MK
    # (docs/crypto-spec.md §10.10 downgrade defense).
    key_generation: int = 1


class DeviceEnrollRequest(BaseModel):
    public_key: str
    fcm_token: str | None = None

    @field_validator("public_key")
    @classmethod
    def validate_public_key(cls, value: str) -> str:
        decode_base64(value, field_name="public_key", exact=32)
        return value


class DeviceEnrollResponse(BaseModel):
    device_id: UUID
    created_at: datetime
    # Device-bound JWT — clients replace their bootstrap token (from
    # /v1/auth/login) with this so every subsequent request to a
    # sensitive endpoint carries a `did` claim. Re-enrolling on a
    # logged-in device also rotates this token.
    session_token: str


class DeviceListItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    created_at: datetime | None
    last_seen: datetime | None
    revoked_at: datetime | None
    has_fcm_token: bool


class SenderRegisterRequest(BaseModel):
    public_key: str
    name: Annotated[str, Field(min_length=1, max_length=200)]
    contact: Annotated[str | None, Field(max_length=200)] = None

    @field_validator("public_key")
    @classmethod
    def validate_public_key(cls, value: str) -> str:
        decode_base64(value, field_name="public_key", exact=32)
        return value


class SenderRegisterResponse(BaseModel):
    sender_id: UUID
    created_at: datetime


class MessageSendRequest(BaseModel):
    sender_id: UUID
    user_id: UUID
    plugin_id: UUID
    encrypted_body: str
    nonce: str
    envelope_signature: str
    min_plugin_version: str | None = None
    expires_at: datetime

    @field_validator("expires_at")
    @classmethod
    def require_timezone_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("expires_at must be timezone-aware (ISO-8601 with offset, e.g. ...Z)")
        return value

    @field_validator("encrypted_body")
    @classmethod
    def validate_encrypted_body(cls, value: str) -> str:
        decode_base64(value, field_name="encrypted_body", minimum=16)
        return value

    @field_validator("nonce")
    @classmethod
    def validate_nonce(cls, value: str) -> str:
        decode_base64(value, field_name="nonce", exact=12)
        return value

    @field_validator("envelope_signature")
    @classmethod
    def validate_envelope_signature(cls, value: str) -> str:
        decode_base64(value, field_name="envelope_signature", exact=64)
        return value


class MessageSendResponse(BaseModel):
    message_id: UUID
    expires_at: datetime


class MessageInboxItem(BaseModel):
    id: UUID
    sender_id: UUID
    plugin_id: UUID
    # Stable sender-chosen identifier (e.g. "com.lottery.app"). The device
    # uses this to fetch the plugin manifest at
    # `/v1/plugins/{sender_id}/{plugin_identifier}/latest` and render the
    # message's card via the sender's JS plugin bundle.
    plugin_identifier: str
    min_plugin_version: str | None
    encrypted_body: str
    nonce: str
    envelope_signature: str
    sent_at: datetime
    # Required so the recipient can reconstruct AAD/envelope for decrypt + sig verify.
    expires_at: datetime

    # Senders sign/encrypt with a `Z`-suffixed UTC string for `expires_at`
    # (see sdk-python crypto.py + the canonicalization in
    # routers/messages.py:_build_envelope_bytes). Pydantic's default datetime
    # serializer emits `+00:00`, which would make the device's AAD bytes
    # diverge from what the sender encrypted with and break AES-GCM decrypt.
    # Force the canonical form on the wire.
    @field_serializer("expires_at")
    def _serialize_expires_at(self, value: datetime) -> str:
        return value.isoformat().replace("+00:00", "Z")


class MessageInboxResponse(BaseModel):
    messages: list[MessageInboxItem]
    next_since: datetime | None


class LiveCardUpsertRequest(BaseModel):
    sender_id: UUID
    user_id: UUID
    plugin_id: UUID
    card_key: str
    encrypted_payload: str  # base64
    nonce: str  # base64 12 bytes
    sequence_number: int
    expires_at: datetime
    envelope_signature: str  # base64 64 bytes Ed25519 over canonical upsert body

    @field_validator("encrypted_payload")
    @classmethod
    def validate_payload(cls, value: str) -> str:
        decode_base64(value, field_name="encrypted_payload", minimum=16)
        return value

    @field_validator("nonce")
    @classmethod
    def validate_nonce(cls, value: str) -> str:
        decode_base64(value, field_name="nonce", exact=12)
        return value

    @field_validator("envelope_signature")
    @classmethod
    def validate_signature(cls, value: str) -> str:
        decode_base64(value, field_name="envelope_signature", exact=64)
        return value


class LiveCardDeleteRequest(BaseModel):
    sender_id: UUID
    # Phase 3b: user_id is REQUIRED on delete (Codex consultation 62 RED #5).
    # The earlier shape signed only `(sender_id, card_key)` and looked the
    # card up without user scoping, which made cross-user deletion possible
    # when two users happened to share the same card_key from the same
    # sender — collision is rare in practice but a hard security boundary
    # cannot rely on rarity.
    user_id: UUID
    card_key: str
    # Phase 12 (V1.5 backlog from Codex 95): the delete envelope MUST carry
    # a nonce + expires_at so a captured delete can't be replayed forever
    # against a current card with the same (sender_id, user_id, card_key).
    # Same shapes + constraints as the upsert envelope: 12-byte nonce
    # checked against the shared `nonce_replay` registry; `expires_at`
    # must be a future instant ≤ 48h ahead (the same MAX_TTL cap upsert
    # uses).
    nonce: str  # base64 12 bytes
    expires_at: datetime
    # base64 64 bytes Ed25519 over canonical
    #   (sender_id, user_id, card_key, nonce, expires_at).
    envelope_signature: str

    @field_validator("envelope_signature")
    @classmethod
    def validate_signature(cls, value: str) -> str:
        decode_base64(value, field_name="envelope_signature", exact=64)
        return value

    @field_validator("nonce")
    @classmethod
    def validate_nonce(cls, value: str) -> str:
        decode_base64(value, field_name="nonce", exact=12)
        return value


class LiveCardItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    sender_id: UUID
    plugin_id: UUID
    plugin_identifier: str
    card_key: str
    encrypted_payload: str
    nonce: str
    sequence_number: int
    updated_at: datetime
    expires_at: datetime


class MessageInboxItemExtended(MessageInboxItem):
    """Inbox item with optional discriminator for live cards."""
    type: Literal["event"] = "event"


class LiveCardInboxItem(LiveCardItem):
    """Live card projected into the inbox feed."""
    type: Literal["live"] = "live"


class InboxFeedResponse(BaseModel):
    items: list[MessageInboxItemExtended | LiveCardInboxItem]
    next_since: datetime | None


class MessageDetailResponse(MessageInboxItem):
    pass


class PairingInitiateRequest(BaseModel):
    sender_id: UUID
    ttl_seconds: Annotated[int, Field(ge=1, le=900)] = 300
    metadata: dict | None = None
    # V1.5 automated pairing (Phase 5a-2): sender's broker URL for the
    # encrypted bootstrap POST. Optional; when None, falls back to V1
    # manual flow (user types user_id+pairing_key_hex into sender's
    # backend by hand). The URL is bound to the sender-signed canonical
    # envelope; the server validates shape (HTTPS in release, private
    # LAN http only in debug). Named `sender_broker_url` to avoid
    # collision with `PairingInitiateResponse.broker_url` which means
    # the Syncler-side broker URL the QR encodes.
    sender_broker_url: str | None = None
    signature: str  # Ed25519 over canonical body (sender authentication)

    @field_validator("signature")
    @classmethod
    def validate_signature(cls, value: str) -> str:
        decode_base64(value, field_name="signature", exact=64)
        return value


class PairingInitiateResponse(BaseModel):
    pairing_id: UUID
    pairing_token: str  # base64
    broker_url: str
    expires_at: datetime
    # V1.5 automated pairing — echoed from the request, null if the
    # sender opted out. `bootstrap_protocol_version` is set to 1
    # whenever `sender_broker_url` is set, absent otherwise.
    sender_broker_url: str | None = None
    bootstrap_protocol_version: int | None = None


class PairingPreviewResponse(BaseModel):
    sender_id: UUID
    sender_name: str
    sender_public_key: str
    sender_public_key_fingerprint: str
    sender_name_hash: str
    expires_at: datetime
    # V1.5 automated pairing — all four fields are present together or
    # all absent. The device verifies `bootstrap_key_signature` against
    # the sender's Ed25519 `sender_public_key` BEFORE building the
    # encrypted envelope (defense against syncler-side key
    # substitution).
    sender_broker_url: str | None = None
    bootstrap_key: str | None = None              # base64 X25519 pub (32 bytes)
    bootstrap_key_signature: str | None = None    # base64 Ed25519 sig (64 bytes)
    bootstrap_protocol_version: int | None = None


class BootstrapKeyRegisterRequest(BaseModel):
    """V1.5 (Phase 5a-2): sender registers its X25519 bootstrap public
    key. Signed by the existing Ed25519 sender key over the literal
    ASCII bytes `"syncler-v1-bootstrap-key:"` followed by the raw 32-
    byte X25519 public key. Re-registering rotates the bootstrap key.
    """
    sender_id: UUID
    bootstrap_key: str             # base64 X25519 pub (32 bytes raw)
    bootstrap_key_signature: str   # base64 Ed25519 sig (64 bytes)

    @field_validator("bootstrap_key")
    @classmethod
    def validate_bootstrap_key(cls, value: str) -> str:
        decode_base64(value, field_name="bootstrap_key", exact=32)
        return value

    @field_validator("bootstrap_key_signature")
    @classmethod
    def validate_bootstrap_key_signature(cls, value: str) -> str:
        decode_base64(value, field_name="bootstrap_key_signature", exact=64)
        return value


class BootstrapKeyRegisterResponse(BaseModel):
    """Returns the deterministic `bootstrap_key_id` (first 16 bytes of
    SHA-256 over the raw X25519 pub key) so the sender can use it for
    AAD reconstruction without re-hashing client-side."""
    bootstrap_key_id: str  # base64 16 bytes


class PairingCompleteRequest(BaseModel):
    pairing_token: str  # url-safe or standard base64 (32 bytes)
    encrypted_initial_state: str  # base64
    # Phase 8 (docs/crypto-spec.md §10.5): the AAD on encrypted_initial_state
    # binds this generation; mismatch → 409 so the client refetches.
    key_generation_observed: int | None = None
    # Phase 8d (docs/crypto-spec.md §10.9): the pairing-state AAD
    # binds the pairing_id. Same chicken-and-egg as user_id at
    # signup — the client must know the UUID BEFORE encrypting the
    # initial state. None → server generates (pre-Phase-8d clients).
    pairing_id: UUID | None = None

    @field_validator("pairing_token")
    @classmethod
    def validate_token(cls, value: str) -> str:
        try:
            if "-" in value or "_" in value or not value.endswith("="):
                pad = "=" * (-len(value) % 4)
                decoded = base64.urlsafe_b64decode(value + pad)
            else:
                decoded = base64.b64decode(value, validate=True)
        except Exception as exc:
            raise ValueError("pairing_token must be valid base64") from exc
        if len(decoded) != 32:
            raise ValueError("pairing_token must decode to 32 bytes")
        return value

    @field_validator("encrypted_initial_state")
    @classmethod
    def validate_state(cls, value: str) -> str:
        decode_base64(value, field_name="encrypted_initial_state", minimum=16)
        return value


class PairingCompleteResponse(BaseModel):
    pairing_id: UUID
    sender_id: UUID
    sender_name: str
    sender_public_key: str
    sender_public_key_fingerprint: str
    sender_name_hash: str
    paired_at: datetime


class PairingItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    sender_id: UUID
    created_at: datetime | None
    revoked_at: datetime | None


class PairingStateResponse(BaseModel):
    """Phase 8e (docs/crypto-spec.md §10) — the encrypted_state for
    a specific pairing the user owns. Used by the client during
    root_* rotations: the client GETs every pairing's state, decrypts
    under the old MK, re-encrypts under the new MK, and POSTs them
    all back via /v1/account/rotate-master-key.

    Server is content-blind to the blob — this endpoint just returns
    what's stored verbatim.
    """

    pairing_id: UUID
    encrypted_state: str  # base64
    state_version: int
    key_generation: int


class StateGetResponse(BaseModel):
    state_version: int
    encrypted_blob: str  # base64
    updated_at: datetime | None
    # Phase 8: the generation the row is encrypted under. Defaults to 1
    # for rows that pre-date the migration's server_default.
    key_generation: int = 1


class StatePutRequest(BaseModel):
    expected_state_version: Annotated[int, Field(ge=0)]
    new_encrypted_blob: str  # base64
    # Phase 8 (docs/crypto-spec.md §10.5): Phase-8-aware clients pass the
    # generation they encrypted under; the server 409s if it has rotated
    # since. Optional in the wire format for legacy compatibility — the
    # mixed-client gate enforces presence when the client claims Phase 8+.
    key_generation_observed: int | None = None

    @field_validator("new_encrypted_blob")
    @classmethod
    def validate_blob(cls, value: str) -> str:
        decode_base64(value, field_name="new_encrypted_blob", minimum=1)
        return value


class StatePutResponse(BaseModel):
    new_state_version: int
    # Phase 8: echo the row's current generation so the client can refresh
    # its high-water mark.
    key_generation: int = 1


class StateConflictBody(BaseModel):
    current_state_version: int
    current_encrypted_blob: str


# Phase 3a — template renderer.
#
# Layouts and per-layout required fields. Senders publish a `template` object
# alongside `renderer: "template"` to declare a native Compose rendering
# instead of a WebView bundle. The publish-time validator enforces the
# schema constraints below so the client never has to render a malformed
# template at message time (Phase 3a in `.triad/50-agreement-and-plan.md`).
_TEMPLATE_LAYOUTS: frozenset[str] = frozenset({"standard_card"})
_LAYOUT_REQUIRED_FIELDS: dict[str, frozenset[str]] = {
    "standard_card": frozenset({"title"}),
}
_LAYOUT_OPTIONAL_FIELDS: dict[str, frozenset[str]] = {
    "standard_card": frozenset({"subtitle", "body"}),
}
# Only $.dotted.path navigation is supported in V1 (no array indexing, no
# wildcards, no filters). Keep the surface tight so the client-side resolver
# stays small and auditable.
_JSONPATH_REGEX = re.compile(r"^\$(?:\.[A-Za-z_][A-Za-z0-9_]*)+$")
# Path-length cap is shared with the hostPreview value cap (200 chars).
_TEMPLATE_PATH_MAX = 200
_ACTION_ID_MAX = 64
_ACTION_LABEL_MAX = 64
_ACTION_ENDPOINT_MAX = 2048


_ACTION_ENDPOINT_PATTERN = re.compile(
    r"(https?):\/\/([a-z0-9._-]+)(?::[0-9]+)?(?:[/?#][\x21-\x7e]*)?",
)
"""scheme://<host>[:port][/path|?query|#fragment].

Strict canonical authority grammar — every character class is explicit
ASCII, no `re.IGNORECASE` (would do Unicode case folding even with
`re.ASCII`, since that flag only restricts `\\w`, `\\d`, `\\s`, `\\b`
shorthands — Codex consultation 84 caught `K` U+212A and `ſ` U+017F
folding into `[A-Z]` and `[A-S]` respectively).

Lowercase scheme + host enforced; authors must write `https://...`,
not `HTTPS://...`. URLs in published plugin manifests are normalized
to lowercase by convention.

Host: ASCII alnum + `.` `-` `_` — covers DNS labels, IPv4 dotted
decimal, and dev hostnames (docker-compose underscores).
Path/query/fragment, when present: printable ASCII (0x21..0x7e) —
excludes whitespace, `\\r`, `\\n`, control chars, raw IDN. Port:
`[0-9]+` (not `\\d`, which would match Unicode digits like `١٢`
under default Python regex behavior — Codex 84).

IPv6 bracketed hosts (`https://[::1]/x`) are intentionally
out-of-scope for V1 — no plugin example uses them and the bracketed
authority syntax would need a dedicated branch.

Mirror of the SDK's `ACTION_ENDPOINT_PATTERN`. Use with ``fullmatch``
— Python's ``$`` in ``re.match`` matches before a trailing ``\\n``,
which JS regex does not, and that would create SDK/server parity
drift on URLs with a stray newline (Codex consultation 83).
"""


def _is_allowed_action_endpoint_scheme(url: str) -> bool:
    """Phase 5d. HTTPS is always allowed. HTTP is only allowed when the
    host is `localhost` or an IPv4 literal in a LAN private range
    (10.x, 172.16-31, 192.168.x, 127.x). Mirror of the SDK's
    `isAllowedActionEndpointScheme`.

    Consultations 80/81/82/83 walked through a series of SDK/server
    parity mismatches when each side parsed the URL with its native
    parser (WHATWG URL in TypeScript, urllib.parse in Python). The
    fix on both sides: skip the parsers, validate the raw string
    against a strict canonical authority grammar with ``fullmatch``.
    See the SDK counterpart for the full rationale.
    """
    match = _ACTION_ENDPOINT_PATTERN.fullmatch(url)
    if not match:
        return False
    scheme = match.group(1).lower()
    host = match.group(2).lower()
    if scheme == "https":
        return True
    if host == "localhost":
        return True
    return _is_lan_private_ipv4(host)


def _is_lan_private_ipv4(host: str) -> bool:
    match = re.fullmatch(r"(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})", host)
    if not match:
        return False
    # Reject leading-zero octets so `010.0.0.1` is not treated as `10.x`.
    # Codex 81 RED: keeps SDK/server in agreement since WHATWG URL
    # interprets leading-zero octets as octal but Python urlparse doesn't.
    raw_octets = [match.group(i) for i in range(1, 5)]
    for raw in raw_octets:
        if len(raw) > 1 and raw.startswith("0"):
            return False
    octets = [int(raw) for raw in raw_octets]
    if any(octet < 0 or octet > 255 for octet in octets):
        return False
    a, b = octets[0], octets[1]
    if a == 10:
        return True
    if a == 172 and 16 <= b <= 31:
        return True
    if a == 192 and b == 168:
        return True
    if a == 127:
        return True  # 127.0.0.0/8 loopback
    return False


def _endpoint_pattern_matches(url: str, pattern: str) -> bool:
    """Mirror of the Kotlin EndpointMatcher in :plugin-host. Treats `*` in
    the host as `[^./]*` (no subdomain leak), `*` in the path as `[^/]*`.
    Required so the client and server agree on which action endpoints are
    "in" the declared-endpoints set.
    """
    # Find where the path starts (first `/` after the scheme).
    scheme_end = pattern.find("://")
    if scheme_end == -1:
        path_start = len(pattern)
    else:
        slash_after_authority = pattern.find("/", scheme_end + 3)
        path_start = slash_after_authority if slash_after_authority != -1 else len(pattern)

    out: list[str] = []
    for index, character in enumerate(pattern):
        if character == "*":
            out.append("[^./]*" if index < path_start else "[^/]*")
        else:
            out.append(re.escape(character))
    return re.match(f"^{''.join(out)}$", url) is not None


class TemplateField(BaseModel):
    """One template field — a JSONPath expression that resolves against the
    decrypted payload at render time."""
    path: Annotated[str, Field(min_length=1, max_length=_TEMPLATE_PATH_MAX)]

    @field_validator("path")
    @classmethod
    def validate_path(cls, value: str) -> str:
        if not _JSONPATH_REGEX.match(value):
            raise ValueError(
                f"invalid JSONPath {value!r}: must match $.field(.subfield)*",
            )
        return value


class TemplateAction(BaseModel):
    """One declared action button. Endpoint must match the plugin's
    `endpoints` globs (enforced at publish time)."""
    id: Annotated[str, Field(min_length=1, max_length=_ACTION_ID_MAX)]
    label: Annotated[str, Field(min_length=1, max_length=_ACTION_LABEL_MAX)]
    endpoint: Annotated[str, Field(min_length=1, max_length=_ACTION_ENDPOINT_MAX)]


class TemplateObject(BaseModel):
    """The Phase 3a template manifest block. Senders ship one of these
    instead of a JS bundle when they want native Compose rendering."""
    layout: str
    fields: dict[str, TemplateField]
    actions: list[TemplateAction] = []

    @field_validator("layout")
    @classmethod
    def validate_layout(cls, value: str) -> str:
        if value not in _TEMPLATE_LAYOUTS:
            raise ValueError(
                f"unknown template layout {value!r}; supported: {sorted(_TEMPLATE_LAYOUTS)}",
            )
        return value

    @model_validator(mode="after")
    def validate_fields_for_layout(self) -> "TemplateObject":
        required = _LAYOUT_REQUIRED_FIELDS.get(self.layout, frozenset())
        optional = _LAYOUT_OPTIONAL_FIELDS.get(self.layout, frozenset())
        allowed = required | optional
        missing = required - self.fields.keys()
        if missing:
            raise ValueError(
                f"layout {self.layout!r} requires fields {sorted(missing)}",
            )
        extra = self.fields.keys() - allowed
        if extra:
            raise ValueError(
                f"layout {self.layout!r} does not accept fields {sorted(extra)}",
            )
        # Action ids must be unique within a template so the client can route
        # an action tap unambiguously.
        action_ids = [a.id for a in self.actions]
        if len(action_ids) != len(set(action_ids)):
            raise ValueError(f"duplicate action ids in template")
        return self


_PLUGIN_IDENTIFIER_REGEX = re.compile(
    r"^[a-zA-Z][a-zA-Z0-9-]*(\.[a-zA-Z][a-zA-Z0-9-]*)+$"
)
"""Mirror of the SDK's idPattern (sdk-plugin/src/manifest.ts). Reverse-DNS
identifier, ASCII only, must contain at least one dot. Phase 5d parity
fix — previously the server only enforced a length cap, letting the SDK
catch malformed identifiers but accepting them server-side."""


class PluginPublishRequest(BaseModel):
    sender_id: UUID
    plugin_identifier: Annotated[str, Field(min_length=1, max_length=200)]
    version: Annotated[str, Field(min_length=1, max_length=64)]
    manifest_hash: str  # base64 32 bytes
    bundle_hash: str    # base64 32 bytes
    signature: str      # base64 64 bytes Ed25519 (bundle signature)
    signed_bundle_url: str
    capabilities: list[str]
    endpoints: list[str]
    # Phase 3a. Defaults to "script" for backwards compat with senders
    # published before the template renderer existed — their canonical
    # publish envelope does not include this field, so the conditional
    # serialization in `_publish_envelope` keeps their signature valid.
    renderer: Literal["script", "template"] = "script"
    template: TemplateObject | None = None
    # Phase 3b: card_type ("event" or "live") and optional card_key_path
    # for live cards.
    card_type: Literal["event", "live"] = "event"
    card_key_path: str | None = None
    sender_signature: str  # base64 Ed25519 over canonical publish body

    @field_validator("manifest_hash")
    @classmethod
    def validate_manifest_hash(cls, value: str) -> str:
        decode_base64(value, field_name="manifest_hash", exact=32)
        return value

    @field_validator("bundle_hash")
    @classmethod
    def validate_bundle_hash(cls, value: str) -> str:
        decode_base64(value, field_name="bundle_hash", exact=32)
        return value

    @field_validator("signature")
    @classmethod
    def validate_signature(cls, value: str) -> str:
        decode_base64(value, field_name="signature", exact=64)
        return value

    @field_validator("sender_signature")
    @classmethod
    def validate_sender_signature(cls, value: str) -> str:
        decode_base64(value, field_name="sender_signature", exact=64)
        return value

    @field_validator("plugin_identifier")
    @classmethod
    def validate_plugin_identifier(cls, value: str) -> str:
        # Phase 5d parity fix: mirror the SDK's reverse-DNS check.
        if not _PLUGIN_IDENTIFIER_REGEX.match(value):
            raise ValueError(
                "plugin_identifier must be reverse-DNS "
                "(e.g. com.example.weather)",
            )
        return value

    @field_validator("card_key_path")
    @classmethod
    def validate_card_key_path(cls, value: str | None) -> str | None:
        # Phase 5d: enforce the same $.field(.subfield)* grammar that
        # template field paths already use. Previously this was just a
        # startsWith("$") check (server services/plugins.py).
        if value is None:
            return value
        if not _JSONPATH_REGEX.match(value):
            raise ValueError(
                f"card_key_path {value!r} must match $.field(.subfield)* "
                "(no array indexing, wildcards, or filters)",
            )
        return value

    @model_validator(mode="after")
    def validate_renderer_template_pairing(self) -> "PluginPublishRequest":
        # The pairing rules live here (not on TemplateObject) because the
        # action-endpoint check needs the surrounding `endpoints` glob list.
        if self.renderer == "template" and self.template is None:
            raise ValueError("template required when renderer == 'template'")
        if self.renderer == "script" and self.template is not None:
            raise ValueError("template must be omitted when renderer == 'script'")
        if self.template is not None:
            for action in self.template.actions:
                # Phase 5d: action endpoints must be HTTPS, or HTTP targeting
                # localhost / a LAN private range (mirrors the SDK).
                if not _is_allowed_action_endpoint_scheme(action.endpoint):
                    raise ValueError(
                        f"action {action.id!r} endpoint {action.endpoint!r} "
                        f"must be HTTPS, or HTTP targeting localhost / a "
                        f"LAN private range",
                    )
                if not any(
                    _endpoint_pattern_matches(action.endpoint, p)
                    for p in self.endpoints
                ):
                    raise ValueError(
                        f"action {action.id!r} endpoint {action.endpoint!r} "
                        f"not allowed by declared endpoints",
                    )
        # Phase 3b: live cards MUST have a card_key_path to extract their
        # stable identity from the payload.
        if self.card_type == "live" and not self.card_key_path:
            raise ValueError("card_key_path required when card_type == 'live'")
        return self


class PluginPublishResponse(BaseModel):
    plugin_row_id: UUID
    plugin_identifier: str
    version: str
    created_at: datetime


class PluginLatestResponse(BaseModel):
    plugin_row_id: UUID
    sender_id: UUID
    plugin_identifier: str
    version: str
    signed_bundle_url: str
    manifest_hash: str
    bundle_hash: str
    signature: str
    capabilities: list[str]
    endpoints: list[str]
    created_at: datetime
    # M11.4: when the row is revoked, surface why so devices can render
    # differentiated UX (silent for ``superseded``, security alert for
    # ``compromised``, neutral "unavailable" for ``sender_disabled``).
    # The /latest endpoint filters revoked rows out so these are always
    # null there; the /by-id endpoint (used for historical lookups by
    # plugin_row_id) returns whatever the row's current revoke state is.
    revoked_at: datetime | None = None
    revocation_reason: str | None = None
    # Phase 3a. Defaults to "script" so pre-Phase-3a stored rows (where the
    # backfilled server default fills NULL with "script") and pre-Phase-3a
    # clients (which ignore the field) both see the WebView path.
    renderer: Literal["script", "template"] = "script"
    template: TemplateObject | None = None
    # Phase 3b: card_type ("event" or "live") and optional card_key_path.
    card_type: Literal["event", "live"] = "event"
    card_key_path: str | None = None


# M11.4: classified revocation reasons. Devices use this to decide UX:
# - ``superseded``: silent; show in update prompts.
# - ``compromised``: refuse to execute; show security alert.
# - ``sender_disabled``: stop rendering; show neutral "unavailable".
# - ``unspecified``: legacy revokes (pre-M11.4) where no reason was given;
#   conservative client treats this as ``compromised`` until clarified.
REVOCATION_REASONS = frozenset({"superseded", "compromised", "sender_disabled", "unspecified"})


class PluginRevokeRequest(BaseModel):
    sender_id: UUID
    plugin_row_id: UUID
    sender_signature: str  # base64 Ed25519 over canonical body
    # Optional in V1 for backwards compatibility with the M8.1 contract;
    # senders SHOULD always supply one. Missing reason → stored as null;
    # client code interprets null as ``unspecified``.
    reason: str | None = None

    @field_validator("sender_signature")
    @classmethod
    def validate_sender_signature(cls, value: str) -> str:
        decode_base64(value, field_name="sender_signature", exact=64)
        return value

    @field_validator("reason")
    @classmethod
    def validate_reason(cls, value: str | None) -> str | None:
        if value is None:
            return None
        if value not in REVOCATION_REASONS:
            raise ValueError(
                f"reason must be one of {sorted(REVOCATION_REASONS)}; got {value!r}"
            )
        return value


# Phase 8: master-key rotation (docs/crypto-spec.md §10).


class RotationChallengeResponse(BaseModel):
    """Response from POST /v1/account/rotate-master-key/challenge."""

    rotation_challenge: str  # base64 32 random bytes
    expires_at: datetime


class RotationPairingEntry(BaseModel):
    """One pairing's new state in a rotation request."""

    pairing_id: UUID
    state_version_observed: int
    new_encrypted_state: str  # base64

    @field_validator("new_encrypted_state")
    @classmethod
    def _validate_new_state(cls, value: str) -> str:
        decode_base64(value, field_name="new_encrypted_state", minimum=16)
        return value


class RotationUserStateBody(BaseModel):
    """The encrypted_user_state body inside a rotation request."""

    encrypted_blob: str  # base64
    state_version_observed: int

    @field_validator("encrypted_blob")
    @classmethod
    def _validate_blob(cls, value: str) -> str:
        decode_base64(value, field_name="encrypted_blob", minimum=16)
        return value


class RotateMasterKeyRequest(BaseModel):
    """Request body for POST /v1/account/rotate-master-key.

    Required-field combinations vary by `reason` — see model_validator
    below. Spec: docs/crypto-spec.md §10.6.
    """

    reason: Literal[
        "password_rewrap", "root_hygiene_rotation", "root_compromise_rotation"
    ]
    key_generation_observed: int
    rotation_challenge: str  # base64 32 bytes
    current_password_proof: str  # base64 32 bytes (auth_key)
    new_encrypted_master_key: str  # base64

    # Required for password_rewrap and root_compromise_rotation;
    # forbidden for root_hygiene_rotation.
    new_auth_salt: str | None = None
    new_auth_key_proof: str | None = None

    # Forbidden for password_rewrap; required for root_*.
    new_encrypted_user_state: RotationUserStateBody | None = None
    pairings: list[RotationPairingEntry] | None = None

    @field_validator("rotation_challenge")
    @classmethod
    def _validate_challenge(cls, value: str) -> str:
        decode_base64(value, field_name="rotation_challenge", exact=32)
        return value

    @field_validator("current_password_proof")
    @classmethod
    def _validate_proof(cls, value: str) -> str:
        decode_base64(value, field_name="current_password_proof", exact=32)
        return value

    @field_validator("new_encrypted_master_key")
    @classmethod
    def _validate_new_wrapped(cls, value: str) -> str:
        decode_base64(value, field_name="new_encrypted_master_key", minimum=32)
        return value

    @model_validator(mode="after")
    def _validate_combinations(self) -> "RotateMasterKeyRequest":
        # Per §10.1 table.
        if self.reason == "password_rewrap":
            if self.new_auth_salt is None or self.new_auth_key_proof is None:
                raise ValueError(
                    "password_rewrap requires new_auth_salt + new_auth_key_proof",
                )
            if self.new_encrypted_user_state is not None or self.pairings is not None:
                raise ValueError(
                    "password_rewrap MUST omit new_encrypted_user_state + pairings",
                )
        elif self.reason == "root_hygiene_rotation":
            if self.new_auth_salt is not None or self.new_auth_key_proof is not None:
                raise ValueError(
                    "root_hygiene_rotation MUST omit new_auth_salt + new_auth_key_proof",
                )
            if self.new_encrypted_user_state is None or self.pairings is None:
                raise ValueError(
                    "root_hygiene_rotation requires new_encrypted_user_state + pairings",
                )
        else:  # root_compromise_rotation
            if self.new_auth_salt is None or self.new_auth_key_proof is None:
                raise ValueError(
                    "root_compromise_rotation requires new_auth_salt + new_auth_key_proof",
                )
            if self.new_encrypted_user_state is None or self.pairings is None:
                raise ValueError(
                    "root_compromise_rotation requires new_encrypted_user_state + pairings",
                )

        # Validate the conditional base64 fields when they ARE present.
        if self.new_auth_salt is not None:
            decode_base64(self.new_auth_salt, field_name="new_auth_salt", exact=16)
        if self.new_auth_key_proof is not None:
            decode_base64(
                self.new_auth_key_proof, field_name="new_auth_key_proof", exact=32,
            )
        return self


class RotationUserStateResult(BaseModel):
    state_version: int
    key_generation: int


class RotationPairingResult(BaseModel):
    pairing_id: UUID
    state_version: int
    key_generation: int


class RotateMasterKeyResponse(BaseModel):
    """Response body for a successful rotation. Spec §10.8."""

    key_generation: int
    encrypted_user_state: RotationUserStateResult | None = None  # null for password_rewrap
    pairings: list[RotationPairingResult] = []
