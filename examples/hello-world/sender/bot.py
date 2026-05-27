"""Hello-world Syncler sender.

Registers, runs an in-process bootstrap broker, prints a pairing QR,
waits for a phone to pair, then sends one card every 30 seconds.

Designed to be the smallest possible working sender. ~110 lines incl.
comments + persistence + error handling.

Usage:
    cp .env.example .env
    # edit .env: set SYNCLER_BASE_URL
    python bot.py
"""

from __future__ import annotations

import json
import os
import threading
import time
import uuid
from pathlib import Path

import requests
import uvicorn
from dotenv import load_dotenv

from syncler import Client
from syncler.bootstrap import (
    load_x25519_private_key_from_raw,
    x25519_keypair_pem,
)
from syncler.broker import make_app
from syncler.broker_storage import InMemoryBrokerStorage
from syncler.crypto import compute_manifest_hash

load_dotenv()

BASE_URL = os.environ.get("SYNCLER_BASE_URL", "https://63-181-3-204.sslip.io")
BROKER_HOST = os.environ.get("BROKER_HOST", "localhost")
BROKER_PORT = int(os.environ.get("BROKER_PORT", "8088"))
SENDER_BROKER_URL = os.environ.get(
    "SENDER_BROKER_URL", f"http://{BROKER_HOST}:{BROKER_PORT}/syncler/bootstrap"
)
STATE_FILE = Path(".bot.state")
SENDER_KEY = Path("sender-ed25519.pem")
BOOTSTRAP_KEY_RAW = Path("bootstrap-x25519.priv")
BOOTSTRAP_PUB_RAW = Path("bootstrap-x25519.pub")
PLUGIN_BUNDLE = Path("../plugin/dist/plugin.bundle.js")
PLUGIN_MANIFEST = Path("../plugin/dist/manifest.signed.json")


def load_state() -> dict:
    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text())
    return {}


def save_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps(state, indent=2))


def ensure_bootstrap_keys() -> tuple[bytes, bytes]:
    """Generate the X25519 bootstrap keypair on first run; cache to disk."""
    if BOOTSTRAP_KEY_RAW.exists() and BOOTSTRAP_PUB_RAW.exists():
        return BOOTSTRAP_KEY_RAW.read_bytes(), BOOTSTRAP_PUB_RAW.read_bytes()
    priv_pem, pub_raw = x25519_keypair_pem()
    BOOTSTRAP_KEY_RAW.write_bytes(priv_pem)
    BOOTSTRAP_PUB_RAW.write_bytes(pub_raw)
    return priv_pem, pub_raw


def main() -> None:
    state = load_state()

    # 1. Client + key generation
    client = Client(
        base_url=BASE_URL,
        sender_name="hello-world",
        private_key_path=str(SENDER_KEY),
    )
    sender_id = client.register_if_needed(contact="hello-world@example.com")
    print(f"sender_id={sender_id}")

    # 2. Bootstrap key (one per sender; persisted across runs)
    bootstrap_priv_pem, bootstrap_pub_raw = ensure_bootstrap_keys()
    if not state.get("bootstrap_registered"):
        client.register_bootstrap_key(bootstrap_public_key_raw=bootstrap_pub_raw)
        state["bootstrap_registered"] = True
        save_state(state)

    # 3. In-process broker (background thread)
    storage = InMemoryBrokerStorage()
    bootstrap_priv = load_x25519_private_key_from_raw(bootstrap_priv_pem)
    broker_app = make_app(
        bootstrap_private_key=bootstrap_priv,
        bootstrap_public_key_raw=bootstrap_pub_raw,
        sender_broker_url=SENDER_BROKER_URL,
        storage=storage,
    )
    broker_thread = threading.Thread(
        target=lambda: uvicorn.run(
            broker_app, host=BROKER_HOST, port=BROKER_PORT, log_level="warning"
        ),
        daemon=True,
    )
    broker_thread.start()
    print(f"broker listening at {SENDER_BROKER_URL}")

    # 4. Plugin publish (skip if same hash already published)
    plugin_row_id = state.get("plugin_row_id")
    if not plugin_row_id:
        if not PLUGIN_BUNDLE.exists() or not PLUGIN_MANIFEST.exists():
            raise SystemExit(
                "Plugin not built. Run `bash ../plugin/build.sh` first."
            )
        manifest = json.loads(PLUGIN_MANIFEST.read_text())
        # Host the bundle: simplest path is a static file your phone can reach.
        # For local testing the example doesn't host — it embeds the bundle in
        # the signed_bundle_url as `data:` (NOT for production). See README.
        # For the canonical flow, upload bundle.js to your CDN + set the
        # signed_bundle_url to its public URL before publishing.
        bundle_url = os.environ.get("SIGNED_BUNDLE_URL")
        if not bundle_url:
            raise SystemExit(
                "Set SIGNED_BUNDLE_URL in .env to a publicly-reachable URL "
                "where dist/plugin.bundle.js is served (any CDN works)."
            )
        # The signed manifest contains `bundleHash` (hex) and `signature` (hex)
        # written by sign-bundle.ts. `manifest_hash` is NOT a stored field —
        # the server expects SHA-256 over the canonical manifest minus
        # signature, which `compute_manifest_hash` produces from the same
        # dict. Codex 163 caught the older bot.py reading a nonexistent
        # manifest["manifest_hash"] which broke first-time partners.
        result = client.publish_plugin(
            plugin_identifier=manifest["id"],
            version=manifest["version"],
            manifest_hash=compute_manifest_hash(manifest),
            bundle_hash=bytes.fromhex(manifest["bundleHash"]),
            bundle_signature=bytes.fromhex(manifest["signature"]),
            signed_bundle_url=bundle_url,
            capabilities=manifest.get("declaredCapabilities", []),
            endpoints=manifest.get("declaredEndpoints", []),
            renderer="template",
            template=manifest["template"],
        )
        plugin_row_id = result["plugin_row_id"]
        state["plugin_row_id"] = plugin_row_id
        save_state(state)
        print(f"plugin published: plugin_row_id={plugin_row_id}")
    else:
        print(f"plugin already published: plugin_row_id={plugin_row_id}")

    # 5. Pairing QR — fresh per run
    pairing_id = state.get("pairing_id")
    user_id = state.get("user_id")
    if not user_id:
        qr_path = client.create_pairing_qr(
            ttl_seconds=300,
            out_path="pair.png",
            sender_broker_url=SENDER_BROKER_URL,
        )
        print(f"\n>>> scan pair.png in the Syncler app (also at {qr_path})\n")
        pairing = client.wait_for_pairing(timeout_seconds=300)
        user_id = pairing.user_id
        state["user_id"] = user_id
        state["pairing_id"] = pairing.pairing_id
        # In a real service you'd encrypt this at rest. For the hello-world
        # example we accept the trade-off; .bot.state is in .gitignore.
        state["pairing_key_hex"] = client.pairing_key.hex()
        save_state(state)
        print(f"paired: user_id={user_id}")
    else:
        print(f"already paired with user_id={user_id}; reloading pairing key")
        client.set_pairing(
            user_id=user_id,
            pairing_key=bytes.fromhex(state["pairing_key_hex"]),
        )

    # 6. The fun part: loop forever, send a card every 30 seconds.
    print("\nsending one card every 30 seconds. Ctrl-C to stop.\n")
    count = state.get("send_count", 0)
    while True:
        count += 1
        client.send_to(
            user_uuid=user_id,
            plugin_id=plugin_row_id,
            payload={
                "hostPreview": {
                    "title": f"hello world #{count}",
                    "subtitle": "from the hello-world sender",
                    "summary": f"sent at {time.strftime('%H:%M:%S')}",
                },
                "greeting": f"Hello, world! (message #{count})",
                "sent_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            },
        )
        state["send_count"] = count
        save_state(state)
        print(f"sent #{count}")
        time.sleep(30)


if __name__ == "__main__":
    main()
