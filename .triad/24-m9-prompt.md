# M9 — Trading bot integration + Python server SDK + Lottery integration spec doc

You completed M1–M8. M9 produces three artifacts:
1. The Python **server SDK** package that senders use to send messages
2. A **working trading bot** that uses the SDK to push reports to the user's phone
3. A **6-page spec doc** for the lottery integration that will be handed (in M10) to a separate Claude Code instance

Workspace-write granted. Touch `sdk-python/`, `examples/`, and `docs/`.

## Part 1 — Python Server SDK (`sdk-python/`)

### Layout
```
sdk-python/
  pyproject.toml
  syncler/
    __init__.py
    client.py
    crypto.py            # mirrors server/app/crypto for client-side encryption + signing
    pairing.py           # QR generation + pairing helpers
    types.py             # dataclasses for manifests, envelopes, payloads
    errors.py
  tests/
    test_client.py
    test_crypto.py
    test_pairing.py
  README.md
  examples/
    minimal_send.py
```

### API
```python
from syncler import Client

client = Client(
    sender_id="com.example.app",
    private_key_path="~/.syncler/keys/sender_ed25519.pem",
    server_base_url="https://api.syncler.app",   # optional, defaults to public server
)

# Register sender (one-time, server-side)
client.register(name="Example App", contact="me@example.com")

# Generate pairing QR
qr_path, pairing_token = client.create_pairing_qr(ttl_seconds=300)

# Wait for user to pair (poll, or webhook in V1.5)
pairing = client.wait_for_pairing(pairing_token, timeout_seconds=300)

# Send a message to the paired user
message = client.send_to(
    user_uuid=pairing.user_id,
    plugin_id="com.example.app",
    payload={"text": "hello"},
    min_plugin_version="1.0.0",
)

# Optional: block until user actions on the message
response = client.wait_for_action(message.id, timeout_seconds=600)
```

### Crypto
- All encryption client-side using `cryptography` package
- Implements the crypto spec from `docs/crypto-spec.md`:
  - Ed25519 signing of envelopes
  - AES-256-GCM with random 12-byte nonce per message
  - HKDF-SHA256 for pairing key derivation (sender uses its own master_key analog — the pairing exchange returns the per-pairing shared secret)
- AAD assembled per the spec; the SDK is the canonical reference implementation

### Constraints
- Python 3.11+
- No async (synchronous SDK for V1 — sender backends can wrap in their own async)
- Tests use `pytest`
- The SDK uses `requests` for HTTP (sync) + `pyca cryptography` for crypto + `qrcode` for QR

## Part 2 — Trading bot example (`examples/trading-bot/`)

A complete, runnable trading bot example that:
- Lives at `examples/trading-bot/`
- Has its own `pyproject.toml` depending on the local `sdk-python` (path dep)
- Demonstrates the full sender lifecycle:
  1. On first run: register sender, generate pairing QR, print path
  2. On subsequent runs: load existing sender_id + paired user from a local `state.json`
  3. Polls a fake/mock trading API every 30 minutes
  4. Sends a "trading report" card with a Plotly chart embedded as HTML (rendered inline by the trading plugin in the WebView)
  5. Has a corresponding plugin (`trading-plugin/`) — the JS plugin code the user installs

### Files
- `examples/trading-bot/pyproject.toml`
- `examples/trading-bot/bot.py` — main loop
- `examples/trading-bot/mock_market.py` — generates synthetic data so the example runs offline
- `examples/trading-bot/state.json.example`
- `examples/trading-bot/README.md` — quickstart

### Plugin side (`examples/trading-plugin/`)
- `package.json` + TypeScript source + signed bundle
- `TradingPlugin extends BasePlugin`:
  - `render()` returns HTML with embedded Plotly (Plotly CDN URL? no — plotly bundled into the plugin js); the chart's data is in the message payload
  - `onMessage()` shows a notification with the report headline
  - No actions for V1 (one-way reports)

## Part 3 — Lottery integration spec doc (`docs/lottery-integration-spec.md`)

The deliverable that gets handed (in M10) to a separate Claude Code instance running on the lottery app. Target: 6 pages max. Anything longer is a DX failure.

### Required content
1. **What you're building** (1 paragraph) — a plugin that receives a list of lottery number sets from your backend, displays them, and reports back when the user has played them
2. **The protocol** (1 page)
   - JSON payload shape: `{ batch_id, draw_date, numbers: number[][] }`
   - Action: `played` — when user taps "Played" button, you receive a POST to your declared endpoint with `{ batch_id, played_at, device_id }`
3. **Plugin code** (1 page)
   - A complete `LotteryPlugin` extending `BasePlugin` that lottery's Claude Code can copy or adapt
   - The `manifest.json` template they need to fill in (id, endpoints, etc.)
4. **Build + sign** (½ page)
   - How to install `@syncler/plugin-sdk`
   - How to bundle + sign via the SDK's tools
5. **Sending from your backend** (1 page)
   - Python: install `syncler` SDK, follow the pairing flow, call `client.send_to(...)` with the lottery payload
   - curl example for non-Python backends
6. **Handling the action callback** (½ page)
   - HTTP endpoint at `https://lottery.app/api/played` that receives the POST
   - Idempotency: dedupe by `batch_id + device_id`
7. **Testing** (½ page)
   - How to verify with the developer mode install (paste URL into Syncler Android app)
8. **Common errors + troubleshooting** (½ page)
   - 410 Gone (user uninstalled plugin)
   - 429 Rate Limited
   - Signature verification failure

### Constraint
- Exactly 6 pages when printed (count by ~500 words/page). If it overflows, trim ruthlessly.
- No fluff. Direct, command-by-command.
- Cross-references to `docs/crypto-spec.md` for crypto details (not duplicated).
- The doc itself is the DX test — if it's confusing, the test fails.

## Print summary
- Files created across the three deliverables
- Word count of `docs/lottery-integration-spec.md`
- The exact `pip install` command for using the SDK
- The exact CLI commands for building + signing the trading plugin
