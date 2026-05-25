"""V1.5 automated pairing — ready-to-mount FastAPI broker app.

This module ships behind the optional ``syncler[broker]`` extra. A
vanilla ``pip install syncler`` does NOT pull FastAPI / uvicorn — only
senders that opt into automated pairing install the extra and run
their own broker behind their ``sender_broker_url``.

Usage::

    from syncler.broker import make_app
    from syncler.broker_storage import InMemoryBrokerStorage
    from syncler.bootstrap import load_x25519_private_key_from_raw

    priv = load_x25519_private_key_from_raw(...)
    pub_raw = ...  # 32 raw X25519 pub bytes, matches what was registered
    storage = InMemoryBrokerStorage()

    app = make_app(
        bootstrap_private_key=priv,
        bootstrap_public_key_raw=pub_raw,
        sender_broker_url="https://sender.example.com/syncler/bootstrap",
        storage=storage,
    )

Then ``uvicorn sender.broker:app`` or wherever the caller mounts it.

See ``docs/integration-guide.md §8.5`` for the full walkthrough and
``docs/crypto-spec.md §9`` for the wire format. The V1.5 fixed-config
deviation from the spec's "MUST use pairing_id-indexed trusted state"
is documented in ``§9.3 V1.5 deviation``.
"""

from __future__ import annotations

from .app import make_app

__all__ = ["make_app"]
