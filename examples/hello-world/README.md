# Hello World — Syncler minimal integration

The smallest runnable example: a Python sender that publishes a template-rendered plugin, prints a pairing QR, and then pushes a card every 30 seconds. Use it as the starting point for any new plugin integration.

## Prerequisites

- Python 3.11+
- Node 20+
- `Syncler` Android app installed on a phone (the test APK or a Google Play release)
- A reachable Syncler server URL (the test instance lives at https://63-181-3-204.sslip.io/)

## Three steps

### 1. Install dependencies

```bash
# Python sender
cd examples/hello-world/sender
python -m venv .venv && source .venv/bin/activate  # Windows: .venv\Scripts\activate
pip install syncler                                  # or `pip install -e ../../../sdk-python`

# TS plugin
cd ../plugin
npm install
```

### 2. Build + sign the plugin bundle

```bash
cd examples/hello-world/plugin
bash build.sh
# Produces dist/plugin.bundle.js + dist/manifest.signed.json + dist/bundle.sha256
```

The `build.sh` script bundles `src/plugin.ts` via esbuild and runs `sign-bundle.ts` to produce a manifest with bundle hash + Ed25519 signature. Examine the script — it's the same toolchain you'll use for your real plugin.

### 3. Run the sender

```bash
cd examples/hello-world/sender
cp .env.example .env
# Edit .env: set SYNCLER_BASE_URL to your Syncler test instance
python bot.py
```

`bot.py` will:

1. Register the sender if it isn't already (`client.register_if_needed`)
2. Publish the plugin (skips if the same version row exists)
3. Bring up the bootstrap broker on `localhost:8088` (in-process)
4. Print a pairing QR to the terminal + `pair.png`
5. Block until your phone scans the QR and confirms the fingerprint
6. Loop: send one "hello, world" card every 30 seconds, named with the current count

You'll see cards land in your Syncler inbox in real time. Tap one to see the detail view rendered by the template.

## What this example demonstrates

- Sender registration + Ed25519 key generation
- Automated pairing (V1.5) with the in-process broker
- Plugin publishing with `renderer="template"` (no JS)
- Event sends (`client.send_to(...)`) with a `hostPreview` block
- The full template-card display flow: row from `hostPreview`, detail view from template `fields`

## What it deliberately doesn't show

- The `live` card type (use `examples/trading-bot/` if you want score-card-style upsertable cards)
- Action callbacks (covered in `docs/integration-guide.md` §7)
- The V3 live channel (`platform.live.connect`, `client.live_push`)

Both extensions are documented in the integration guide once you have the basics working.

## Troubleshooting

- **`from syncler import Client` crashes on import** — you're hitting the dormant HPKE bug from before commit `357f930`. Update your local `sdk-python` checkout / reinstall from current `master`.
- **Pairing QR shown but `wait_for_pairing` times out** — the phone may be POSTing to a URL it can't reach. The example's broker binds to `localhost:8088`; if your phone isn't on the same machine, change `sender_broker_url` to your machine's LAN IP or expose via ngrok.
- **`410 plugin missing` on `send_to`** — your `plugin_row_id` is stale. The example caches the last-known row in `.bot.state`; delete that file to force a republish.
