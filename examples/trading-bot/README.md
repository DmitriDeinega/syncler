# trading-bot

**Full round-trip Syncler example** (Phase 5b). The smallest realistic plugin + sender pair: a Python backend that registers, pairs with the user, publishes a JS plugin, and pushes synthetic trading reports. The plugin renders each report with an "Acknowledge" button that POSTs back to the bot, which mutates a visible `state.json` so the round-trip is observable without setting up a database.

## What's in this directory

```
bot.py                # sender + /api/ack server + plugin publisher
state.json            # gitignored; created by `bot.py register`
README.md             # this file
plugin/
  src/plugin.ts       # plugin source: renders TradingReport detail view + Acknowledge button
  manifest.json       # unsigned manifest (placeholders); signer rewrites bundleHash/signature
  build.sh            # esbuild wrapper, outputs plugin/dist/plugin.bundle.js
  dist/               # build output (gitignored)
  manifest.signed.json  # written by sign-bundle.ts; what bot.py publish-plugin reads
```

## Prerequisites

- Python 3.10+ (we use `from __future__ import annotations` + stdlib only)
- Node 18+ (for `npx esbuild` and `npx tsx`)
- A running Syncler server (`cd ../../server && uvicorn app.main:app`)
- The Syncler Android app installed on a phone or emulator, with network reach back to the bot (see "Connecting your Android device" below)

## End-to-end walkthrough

### 1. Install the SDK locally

```sh
pip install -e ../../sdk-python
```

### 2. Register the sender

```sh
python bot.py register
```

Prints a `sender_id` and writes it to `state.json`. Persists across runs.

### 3. Pair with a user

```sh
python bot.py pair
```

Writes `pairing.png` (the QR). Scan it in the Syncler app and confirm the fingerprint. The app shows your `user_id` and a `pairing_key_hex` after confirm. Paste them back:

```sh
python bot.py set-pairing <user_id> <pairing_key_hex>
```

(Phase 5a-2 lands automated pairing — see `docs/ROADMAP.md` V1.5 DX. Until 5a-2.1 wires the Android UX, the manual copy-paste is the V1 flow.)

### 4. Build + sign the plugin

```sh
cd plugin
chmod +x build.sh && ./build.sh

# Replace the senderId placeholder in manifest.json with your sender_id
# (printed by `bot.py register`) before signing — the signed manifest is
# what the server validates.

npx tsx ../../../sdk-plugin/tools/sign-bundle.ts \
  dist/plugin.bundle.js \
  ~/.syncler/keys/trading-bot.pem \
  manifest.json \
  manifest.signed.json

cd ..
```

The signer reads the unsigned manifest, computes SHA-256 over the bundle, signs `(canonical_manifest || bundleHash)` with the bot's Ed25519 sender key, and writes `plugin/manifest.signed.json` with `bundleHash` + `signature` populated.

### 5. Publish the plugin

```sh
python bot.py publish-plugin
```

Reads `plugin/dist/plugin.bundle.js` + `plugin/manifest.signed.json`, calls `client.publish_plugin(...)`, and persists `plugin_row_id` to `state.json`. The Syncler server validates the signature against your sender's Ed25519 key.

### 6. Start the ack server

In **terminal 1**:

```sh
python bot.py ack-server
```

Listens on `:8001`. Serves the plugin bundle at `GET /plugin.bundle.js` (so the Syncler Android app can fetch + verify it) and accepts the action callback at `POST /api/ack`. Mutates `state.json` to add `ack_count` + `ack_history` so you can `cat state.json` and see the round-trip happen.

### 7. Run the send loop

In **terminal 2**:

```sh
python bot.py loop
```

Sends a synthetic report every `SYNCLER_TICK_SECONDS` seconds (default 1800). For demo purposes, set it lower:

```sh
SYNCLER_TICK_SECONDS=10 python bot.py loop
```

### 8. Tap "Acknowledge" in the Syncler app

The plugin POSTs to the payload's `ack_url` (default `http://10.0.2.2:8001/api/ack` for emulator; see "Connecting your Android device" below) with `{"message_id": "...", "acted_at": "..."}`. The bot increments `ack_count` and appends to `ack_history`. Refresh `state.json` to see:

```json
{
  "sender_id": "...",
  "user_id": "...",
  "pairing_key_hex": "...",
  "plugin_row_id": "...",
  "ack_count": 1,
  "ack_history": [{"message_id": "trade-...", "acted_at": "..."}]
}
```

## What this example demonstrates

- `/v1/senders/register` + `/v1/pairing/initiate/preview/complete` flow
- `/v1/plugins/publish` with a real signed manifest + bundle
- `client.send_to(...)` with `hostPreview` populated
- Plugin's `render(payload)` returning HTML + an `onclick` handler
- `platform.network.fetch(url, init)` POSTing to a declared endpoint
- Idempotent server-side dedupe by `message_id` (returns 409 on replay)

## What this example does NOT do

- Cross-device dismiss / archive / mute (Phase 3 features; the host handles them with no plugin code).
- Live cards. This is a script-renderer event card. For a template-renderer + live-card example, see `docs/ROADMAP.md` V1.5 DX #8.
- Production-grade auth on `/api/ack`. The endpoint trusts whatever the host posts; a real backend should HMAC the payload (`docs/integration-guide.md §7`).
- HTTPS. Localhost-only for LAN dev. Release builds of the Syncler Android app refuse cleartext outright.

## Connecting your Android device

Android `localhost` resolves to the *phone*, not your dev machine. Pick one:

| Setup | `SYNCLER_LAN_HOST` | Notes |
|---|---|---|
| Android emulator | `10.0.2.2` (default) | Emulator's loopback to the host machine |
| Physical device + same Wi-Fi | `<dev-machine-LAN-IP>` (e.g. `192.168.1.42`) | Bot's `0.0.0.0:8001` listener must be reachable across your LAN; firewall may need an exception |
| Physical device + USB | `localhost`, after `adb reverse tcp:8001 tcp:8001` | Tunnels phone's port 8001 back to the dev machine |

`bot.py` derives `SYNCLER_ACK_URL` and `SYNCLER_BUNDLE_URL` from `SYNCLER_LAN_HOST` automatically. The sender embeds `ack_url` into each report payload, so you do *not* need to rebuild/republish the plugin when switching connectivity modes — just restart `bot.py` with a different `SYNCLER_LAN_HOST`.

Release builds of the Syncler Android app refuse cleartext HTTP. The above works in debug builds only; production plugins should declare an HTTPS endpoint and the sender should target it.

## Environment variables

- `SYNCLER_BASE_URL` (default `http://localhost:8000`) — Syncler server URL.
- `SYNCLER_TICK_SECONDS` (default `1800`) — `loop` interval.
- `SYNCLER_ACK_PORT` (default `8001`) — ack server port.
- `SYNCLER_LAN_HOST` (default `10.0.2.2`) — host the Android *device* uses to reach the bot. See "Connecting your Android device" above.
- `SYNCLER_ACK_URL` (default `http://${SYNCLER_LAN_HOST}:${SYNCLER_ACK_PORT}/api/ack`) — URL the bot embeds in each payload as `ack_url`. Override to bypass the host derivation.
- `SYNCLER_BUNDLE_URL` (default `http://${SYNCLER_LAN_HOST}:${SYNCLER_ACK_PORT}/plugin.bundle.js`) — URL the publish step records as `signed_bundle_url`. Override when hosting on a CDN or different machine.
