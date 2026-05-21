"""Syncler sender SDK — for backends that send messages into the Syncler platform."""

from syncler.client import Client, Pairing, SendResult
from syncler.errors import (
    SynclerError,
    PluginRevokedError,
    RecipientUnreachableError,
    SignatureError,
)
from syncler.preview import (
    HOST_PREVIEW_KEY,
    HostPreview,
    HostPreviewValidationError,
    validate_host_preview,
)

__all__ = [
    "Client",
    "Pairing",
    "SendResult",
    "SynclerError",
    "PluginRevokedError",
    "RecipientUnreachableError",
    "SignatureError",
    "HOST_PREVIEW_KEY",
    "HostPreview",
    "HostPreviewValidationError",
    "validate_host_preview",
]

__version__ = "0.1.0"
