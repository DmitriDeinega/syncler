# Review of M9 (Python SDK + trading bot + lottery spec doc)

Commit `037cb11`.

## What landed

- `sdk-python/` package: Client (register/pairing/send/publish), crypto module (canonical AAD + envelope matching docs/crypto-spec.md), errors module
- `examples/trading-bot/` runnable example
- `docs/lottery-integration-spec.md` (~3000 words / ~6 pages) — the M10 DX dogfood deliverable

## Pressure-test

1. SDK envelope bytes match what `server/app/routers/messages.py:_build_envelope_bytes` produces — byte-identical? (server uses assemble_envelope; SDK uses local canonical_json with same sort_keys+separators)
2. SDK AAD assembly matches `server/app/crypto/aead.py:assemble_aad` (V1.1 5-field shape) — byte-identical?
3. `wait_for_pairing` raises NotImplementedError pending M11 — flagged as known incomplete
4. Set_pairing(user_id, pairing_key) requires out-of-band sharing — acceptable for V1 dev / dogfood, called out in SDK README + spec doc
5. Plugin publish: SDK signs over same canonical envelope server expects (routers/plugins.py:_publish_envelope) — match?
6. Lottery spec doc page count: 6 target. Word count ~3000. If a Claude Code instance can integrate from this in one shot, the test passes.

## Output

Standard format. Look especially for SDK ↔ server byte-shape mismatches that would silently fail signature verification.
