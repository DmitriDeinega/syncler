# syncler (Python sender SDK)

Send messages from your backend into the Syncler platform.

## Install

```sh
pip install syncler
```

## Quickstart

```python
from syncler import Client

client = Client(
    sender_name="Trading Bot",
    private_key_path="~/.syncler/keys/trading.pem",
    base_url="https://api.syncler.app",
)

# Once: register the sender's public key. Persist the returned sender_id.
sender_id = client.register_if_needed(contact="ops@example.com")
print("Save this:", sender_id)

# Or on subsequent runs:
client.set_sender_id("the-saved-sender-id")

# Pair with a user (one-time per user-device).
qr_path = client.create_pairing_qr(ttl_seconds=300)
print(f"Show this QR to the user: {qr_path}")
# Once the user pairs in their Syncler app, they'll get a confirmation
# screen showing your sender's fingerprint. They confirm. You need their
# user_id + pairing_key from the device-side exchange (M7 wires this; for
# now in dev, use client.set_pairing(user_id, pairing_key)).

client.set_pairing(user_id="...", pairing_key=b"...32-bytes-shared-secret...")

# Send a message.
result = client.send_to(
    user_uuid=client._latest_pairing.user_id,
    plugin_identifier="com.trading.app",
    plugin_id="row-uuid-of-published-version",
    payload={"pnl": 1234.56, "trades": 7},
    min_plugin_version="1.0.0",
)
print(f"Sent {result.message_id}, expires {result.expires_at}")
```

## Publishing a plugin

```python
import hashlib

bundle_bytes = open("path/to/plugin.bundle.js", "rb").read()
bundle_hash = hashlib.sha256(bundle_bytes).digest()

# manifest_hash is the SHA-256 of the canonical manifest JSON; the
# bundle_signature is the Ed25519 signature over canonical_manifest
# + bundleHash bytes (see docs/crypto-spec.md §5).
response = client.publish_plugin(
    plugin_identifier="com.trading.app",
    version="1.0.0",
    manifest_hash=manifest_hash,
    bundle_hash=bundle_hash,
    bundle_signature=bundle_signature,
    signed_bundle_url="https://your-cdn.com/plugin.bundle.js",
    capabilities=["network"],
    endpoints=["https://your-backend.com/api/*"],
)
print("Published version row:", response["plugin_row_id"])
```

## Threading

The client is sync. Wrap calls in your own thread pool / asyncio executor
if you need concurrent sends.

## Spec

See `docs/crypto-spec.md` (top of repo) for the canonical AAD + envelope
shapes. The SDK is the reference Python implementation.
