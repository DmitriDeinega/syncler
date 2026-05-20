"""Minimal Syncler trading bot example.

Sends a periodic synthetic "trading report" card. Demonstrates the smallest
useful end-to-end sender — pairing + sending — without external deps beyond
the syncler SDK.

Usage:
  python bot.py register     # one-time, prints the sender_id; save it
  python bot.py pair         # writes a QR PNG; user scans it
  python bot.py loop         # sends a report every 30 minutes

State persists in ./state.json next to this script.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
from datetime import UTC, datetime

# Make the SDK importable when running from the example dir
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "sdk-python"))

from syncler import Client, SynclerError  # noqa: E402

STATE_FILE = os.path.join(HERE, "state.json")
PRIVATE_KEY_FILE = os.path.expanduser("~/.syncler/keys/trading-bot.pem")
BASE_URL = os.environ.get("SYNCLER_BASE_URL", "http://localhost:8000")


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
            "set state['plugin_row_id'] in state.json — the UUID returned by "
            "`client.publish_plugin(...)` after you publish your trading-bot plugin.",
            file=sys.stderr,
        )
        sys.exit(1)

    client.set_pairing(user_id=state["user_id"], pairing_key=bytes.fromhex(state["pairing_key_hex"]))

    while True:
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
    return {
        "as_of": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "pnl": round(random.uniform(-500.0, 500.0), 2),
        "open_positions": random.randint(0, 8),
        "headline": random.choice([
            "AAPL up 1.2% on volume",
            "BTC chop, holding 60k support",
            "Portfolio steady; no alerts",
        ]),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("register")
    sub.add_parser("pair")
    sp = sub.add_parser("set-pairing")
    sp.add_argument("user_id")
    sp.add_argument("pairing_key_hex")
    sub.add_parser("loop")
    args = parser.parse_args()

    if args.cmd == "register":
        cmd_register()
    elif args.cmd == "pair":
        cmd_pair()
    elif args.cmd == "set-pairing":
        cmd_set_pairing(args.user_id, args.pairing_key_hex)
    elif args.cmd == "loop":
        cmd_loop()


if __name__ == "__main__":
    main()
