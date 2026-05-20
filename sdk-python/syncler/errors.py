"""SDK-side exceptions."""

from __future__ import annotations


class SynclerError(Exception):
    """Base for all SDK errors."""


class SignatureError(SynclerError):
    """Server rejected our signature, or we failed to sign."""


class PluginRevokedError(SynclerError):
    """Plugin row has been revoked."""


class RecipientUnreachableError(SynclerError):
    """Recipient has no active device with the plugin installed."""


class PairingExpiredError(SynclerError):
    """Pairing token TTL elapsed before user completed pairing."""


class PairingConflictError(SynclerError):
    """User is already paired with this sender."""
