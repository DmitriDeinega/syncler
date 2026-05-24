"""Minimal Syncler trading bot example.

Phase 5b — full round-trip example. Sends a periodic synthetic
"trading report" card AND serves the `/api/ack` callback that the
plugin POSTs to when the user taps "Acknowledge." Demonstrates the
smallest useful end-to-end sender + plugin pair — pairing, sending,
plugin rendering, action callback — without external deps beyond
the syncler SDK and Python stdlib's http.server.

Usage:
  python bot.py register         # one-time, prints sender_id; save it
  python bot.py pair             # writes a QR PNG; user scans it
  python bot.py set-pairing <user_id> <pairing_key_hex>
                                 # paste values from device UI
  python bot.py publish-plugin   # signs + publishes plugin/dist/plugin.bundle.js
  python bot.py ack-server       # serves /api/ack on :8001 (run alongside loop)
  python bot.py loop             # sends a report every 30 minutes

State persists in ./state.json next to this script (sender_id,
user_id, pairing_key_hex, plugin_row_id, ack_count, ack_history).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import sys
import time
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, HTTPServer

# Make the SDK importable when running from the example dir
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "sdk-python"))

from syncler import Client, SynclerError  # noqa: E402

STATE_FILE = os.path.join(HERE, "state.json")
PRIVATE_KEY_FILE = os.path.expanduser("~/.syncler/keys/trading-bot.pem")
BASE_URL = os.environ.get("SYNCLER_BASE_URL", "http://localhost:8000")
# Connectivity defaults are chosen for the Android emulator: 10.0.2.2 is
# the emulator's loopback to the host machine. Physical-device users
# either set SYNCLER_LAN_HOST=<dev-machine-LAN-IP> or run
# `adb reverse tcp:8001 tcp:8001` and set SYNCLER_LAN_HOST=localhost.
# See README §"Connecting your Android device" for the full matrix.
DEVICE_LAN_HOST = os.environ.get("SYNCLER_LAN_HOST", "10.0.2.2")
ACK_PORT = int(os.environ.get("SYNCLER_ACK_PORT", "8001"))
ACK_URL = os.environ.get("SYNCLER_ACK_URL", f"http://{DEVICE_LAN_HOST}:{ACK_PORT}/api/ack")
BUNDLE_URL = os.environ.get("SYNCLER_BUNDLE_URL", f"http://{DEVICE_LAN_HOST}:{ACK_PORT}/plugin.bundle.js")


def load_state() -> dict:
    if not os.path.exists(STATE_FILE):
        return {}
    return json.loads(open(STATE_FILE).read())


def save_state(state: dict) -> None:
    with open(STATE_FILE, "w") as f:
        json.dump(state, f, indent=2)


def make_client(state: dict) -> Client:
    client = Client(
        sender_name="Trading Bot",
        private_key_path=PRIVATE_KEY_FILE,
        base_url=BASE_URL,
    )
    if "sender_id" in state:
        client.set_sender_id(state["sender_id"])
    return client


def cmd_register() -> None:
    state = load_state()
    client = make_client(state)
    if "sender_id" in state:
        print(f"Already registered as {state['sender_id']}")
        return
    sender_id = client.register_if_needed(contact="ops@example.com")
    state["sender_id"] = sender_id
    save_state(state)
    print(f"Registered. sender_id = {sender_id}")
    print("Next: python bot.py pair")


def cmd_pair() -> None:
    state = load_state()
    client = make_client(state)
    if "sender_id" not in state:
        print("Run `register` first.", file=sys.stderr)
        sys.exit(1)
    qr_path = client.create_pairing_qr(ttl_seconds=300, out_path=os.path.join(HERE, "pairing.png"))
    print(f"QR written to {qr_path}")
    print("Scan it in the Syncler app, confirm the fingerprint, then:")
    print("  python bot.py set-pairing <user_id> <pairing_key_hex>")


def cmd_set_pairing(user_id: str, pairing_key_hex: str) -> None:
    state = load_state()
    state["user_id"] = user_id
    state["pairing_key_hex"] = pairing_key_hex
    save_state(state)
    print("Pairing recorded.")


def cmd_loop() -> None:
    state = load_state()
    client = make_client(state)
    if "sender_id" not in state or "user_id" not in state or "pairing_key_hex" not in state:
        print("Run `register` + `pair` + `set-pairing` first.", file=sys.stderr)
        sys.exit(1)

    plugin_row_id = state.get("plugin_row_id")
    if not plugin_row_id:
        print(
            "Run `python bot.py publish-plugin` first to publish the plugin "
            "and write the returned plugin_row_id into state.json. Requires "
            "the plugin bundle to be built and signed; see plugin/build.sh.",
            file=sys.stderr,
        )
        sys.exit(1)

    client.set_pairing(user_id=state["user_id"], pairing_key=bytes.fromhex(state["pairing_key_hex"]))

    while True:
        # Generate a synthetic report. The message_id is sent inside the
        # payload (NOT the server-assigned one — the plugin echoes this
        # back to /api/ack so we can correlate). For real backends use a
        # stable, sender-generated identifier (order id, ticket id, etc).
        report = synthetic_report()
        try:
            result = client.send_to(
                user_uuid=state["user_id"],
                plugin_identifier="com.trading.app",
                plugin_id=plugin_row_id,
                payload=report,
                min_plugin_version="1.0.0",
            )
            print(f"Sent {result.message_id} at {datetime.now(UTC).isoformat()}")
        except SynclerError as e:
            print(f"send failed: {e}", file=sys.stderr)
        time.sleep(int(os.environ.get("SYNCLER_TICK_SECONDS", "1800")))


def synthetic_report() -> dict:
    # message_id is a sender-generated correlation id the plugin echoes
    # back to /api/ack. Distinct from the server's internal message_id
    # (which the sender doesn't pre-know).
    #
    # ack_url is sent per-message so the same plugin bundle works for
    # any of the three connectivity modes (emulator/LAN/adb reverse)
    # without rebuilding. The host's network bridge still enforces
    # declaredEndpoints — `ACK_URL` must match one of the manifest's
    # globs.
    return {
        "message_id": f"trade-{int(time.time()*1000)}-{random.randint(1000, 9999)}",
        "ack_url": ACK_URL,
        "as_of": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "pnl": round(random.uniform(-500.0, 500.0), 2),
        "open_positions": random.randint(0, 8),
        "headline": random.choice([
            "AAPL up 1.2% on volume",
            "BTC chop, holding 60k support",
            "Portfolio steady; no alerts",
        ]),
        "hostPreview": {
            "title": "Trading report",
            "subtitle": "Synthetic example",
            "summary": "Tap to view details and acknowledge.",
        },
    }


# ----------------------------- Phase 5b additions -----------------------------


def cmd_publish_plugin() -> None:
    """Sign + publish the bundled plugin/dist/plugin.bundle.js and persist
    the returned plugin_row_id in state.json. Requires the plugin to be
    built first (plugin/build.sh) and a signed manifest produced via
    sign-bundle.ts; if manifest.signed.json doesn't exist, we point the
    user at the signing command and exit."""
    state = load_state()
    client = make_client(state)
    if "sender_id" not in state:
        print("Run `register` first.", file=sys.stderr)
        sys.exit(1)

    plugin_dir = os.path.join(HERE, "plugin")
    bundle_path = os.path.join(plugin_dir, "dist", "plugin.bundle.js")
    signed_manifest_path = os.path.join(plugin_dir, "manifest.signed.json")
    if not os.path.exists(bundle_path):
        print(
            "Bundle not found at plugin/dist/plugin.bundle.js. Build first:\n"
            "  cd plugin && ./build.sh",
            file=sys.stderr,
        )
        sys.exit(1)
    if not os.path.exists(signed_manifest_path):
        print(
            "Signed manifest not found. Sign first (from plugin/):\n"
            "  npx tsx ../../../sdk-plugin/tools/sign-bundle.ts \\\n"
            "      dist/plugin.bundle.js \\\n"
            "      ~/.syncler/keys/trading-bot.pem \\\n"
            "      manifest.json \\\n"
            "      manifest.signed.json",
            file=sys.stderr,
        )
        sys.exit(1)

    bundle_bytes = open(bundle_path, "rb").read()
    manifest = json.loads(open(signed_manifest_path).read())

    # Canonical manifest hash: SHA-256 over the manifest JSON with the
    # `signature` field removed, sorted keys, compact separators. The
    # signing tool already emits this shape; we recompute here to keep
    # this script independent.
    canonical_manifest = json.dumps(
        {k: v for k, v in manifest.items() if k != "signature"},
        sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")
    manifest_hash = hashlib.sha256(canonical_manifest).digest()
    bundle_hash = hashlib.sha256(bundle_bytes).digest()
    bundle_signature = bytes.fromhex(manifest["signature"])

    response = client.publish_plugin(
        plugin_identifier="com.trading.app",
        version=manifest["version"],
        manifest_hash=manifest_hash,
        bundle_hash=bundle_hash,
        bundle_signature=bundle_signature,
        # For LAN dev: serve the bundle off the bot's HTTP server at the
        # device-reachable host. Default 10.0.2.2 is the Android emulator's
        # loopback to the host machine. Override via SYNCLER_LAN_HOST or
        # SYNCLER_BUNDLE_URL for physical devices. For production, host
        # it on a stable HTTPS URL (CDN, S3).
        signed_bundle_url=BUNDLE_URL,
        capabilities=["network"],
        endpoints=[
            "http://*.*.*.*:8001/api/*",
            "http://localhost:8001/api/*",
            "https://*.example.com/api/*",
        ],
    )
    state["plugin_row_id"] = response["plugin_row_id"]
    save_state(state)
    print(f"Published. plugin_row_id = {response['plugin_row_id']}")


class _AckHandler(BaseHTTPRequestHandler):
    """Serves the bundle (GET /plugin.bundle.js) AND accepts the ack
    callback (POST /api/ack). Stdlib http.server — no Flask/FastAPI
    dependency for the example. Production callers should use a real
    web framework with auth + logging."""

    def log_message(self, fmt: str, *args) -> None:  # noqa: A003
        # Print with timestamp; default impl writes to stderr unsorted.
        sys.stderr.write(f"[{datetime.now(UTC).isoformat()}] {fmt % args}\n")

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/plugin.bundle.js":
            bundle_path = os.path.join(HERE, "plugin", "dist", "plugin.bundle.js")
            if not os.path.exists(bundle_path):
                self.send_response(404)
                self.end_headers()
                return
            data = open(bundle_path, "rb").read()
            self.send_response(200)
            self.send_header("Content-Type", "application/javascript")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/api/ack":
            self.send_response(404)
            self.end_headers()
            return
        try:
            length = int(self.headers.get("Content-Length") or "0")
            body = self.rfile.read(length).decode("utf-8")
            payload = json.loads(body)
            message_id = str(payload.get("message_id") or "")
            acted_at = str(payload.get("acted_at") or "")
        except Exception:
            self.send_response(400)
            self.end_headers()
            return
        if not message_id:
            self.send_response(400)
            self.end_headers()
            return

        # Mutate state.json visibly so the round-trip is observable
        # without setting up a database. ack_history is FIFO-capped at
        # 100 to keep the file readable.
        state = load_state()
        history = list(state.get("ack_history") or [])
        # Idempotent dedupe by message_id (Codex consultation 62 §7).
        if any(h.get("message_id") == message_id for h in history):
            self.send_response(409)
            self.end_headers()
            return
        history.append({"message_id": message_id, "acted_at": acted_at})
        if len(history) > 100:
            history = history[-100:]
        state["ack_count"] = int(state.get("ack_count") or 0) + 1
        state["ack_history"] = history
        save_state(state)

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok": true}')


def cmd_ack_server() -> None:
    """Run the /api/ack + bundle server on 0.0.0.0:<port> in the
    foreground. Run alongside `loop` in a separate terminal. Binds to
    0.0.0.0 so a physical Android device on the same LAN can reach it;
    the device-side URL is `SYNCLER_ACK_URL` / `SYNCLER_BUNDLE_URL`."""
    server = HTTPServer(("0.0.0.0", ACK_PORT), _AckHandler)
    print(f"Ack server listening on 0.0.0.0:{ACK_PORT}")
    print(f"  device-reachable ack URL: {ACK_URL}")
    print(f"  device-reachable bundle URL: {BUNDLE_URL}")
    print("Override via SYNCLER_LAN_HOST (default 10.0.2.2 for emulator),")
    print("or set SYNCLER_ACK_URL / SYNCLER_BUNDLE_URL directly.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.server_close()


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("register")
    sub.add_parser("pair")
    sp = sub.add_parser("set-pairing")
    sp.add_argument("user_id")
    sp.add_argument("pairing_key_hex")
    sub.add_parser("publish-plugin")
    sub.add_parser("ack-server")
    sub.add_parser("loop")
    args = parser.parse_args()

    if args.cmd == "register":
        cmd_register()
    elif args.cmd == "pair":
        cmd_pair()
    elif args.cmd == "set-pairing":
        cmd_set_pairing(args.user_id, args.pairing_key_hex)
    elif args.cmd == "publish-plugin":
        cmd_publish_plugin()
    elif args.cmd == "ack-server":
        cmd_ack_server()
    elif args.cmd == "loop":
        cmd_loop()


if __name__ == "__main__":
    main()
