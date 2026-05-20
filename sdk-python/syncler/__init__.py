"""Syncler sender SDK — for backends that send messages into the Syncler platform."""

from syncler.client import Client, Pairing, SendResult
from syncler.errors import (
    SynclerError,
    PluginRevokedError,
    RecipientUnreachableError,
    SignatureError,
)

__all__ = [
    "Client",
    "Pairing",
    "SendResult",
    "SynclerError",
    "PluginRevokedError",
    "RecipientUnreachableError",
    "SignatureError",
]

__version__ = "0.1.0"
