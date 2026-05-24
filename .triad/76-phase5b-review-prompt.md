# Consultation 76 — Phase 5b review

Phase 5b — full round-trip example — is implemented per the agreement
at `.triad/70-phase5-agreement.md`. This consultation is a single
review pass (vs the 3-consultation cycle Phase 5a-2 needed) since
the substantive design is locked in the agreement and this is
mostly mechanical wiring.

## What landed

### `examples/trading-bot/plugin/` (NEW)
- `src/plugin.ts` — TradingPlugin extending BasePlugin, declares
  Capability.NETWORK, declaredEndpoints for both LAN
  (`http://localhost:8001/api/*`) and prod (`https://*.example.com/api/*`),
  renders the report with an "Acknowledge" button that POSTs the
  message_id back via platform.network.fetch.
- `manifest.json` — unsigned manifest with UNSIGNED-PLACEHOLDER
  values for bundleHash/signature (per Phase 4.1 convention; the
  SDK validator rejects these, forcing the signer step).
- `build.sh` — esbuild wrapper, IIFE format, --alias to the
  monorepo `sdk-plugin/src/index.ts` so the example builds
  without `npm install @syncler/plugin-sdk`.

### `examples/trading-bot/bot.py`
- New `publish-plugin` subcommand: reads `plugin/dist/plugin.bundle.js`
  + `plugin/manifest.signed.json`, recomputes canonical manifest
  hash, calls `client.publish_plugin(...)`, persists `plugin_row_id`
  in state.json. Gracefully errors when bundle/signed-manifest
  are missing with the exact command to fix.
- New `ack-server` subcommand: stdlib `http.server` on `:8001`
  serving BOTH `GET /plugin.bundle.js` (so the Syncler Android
  app can fetch + verify it) AND `POST /api/ack` (the plugin's
  action callback). No FastAPI / Flask dependency.
- `/api/ack` handler: parses JSON body, validates `message_id`,
  mutates `state.json` with `ack_count` increment + `ack_history`
  (FIFO-capped 100, idempotent dedupe by `message_id` returns 409
  per `docs/integration-guide.md §7`).
- `synthetic_report()` now includes a sender-generated
  `message_id` (correlation id the plugin echoes back) and a
  `hostPreview` block (title/subtitle/summary).

### `examples/trading-bot/README.md`
- Rewritten as an end-to-end walkthrough: prereqs → register →
  pair → build+sign → publish → ack-server + loop → "tap
  Acknowledge in the Syncler app and watch state.json change".
- "What this example demonstrates" and "What this example does
  NOT do" sections.
- Environment-variable reference for ports/URLs.

## Buildability check

- `python -c "import bot"` imports cleanly (smoke test).
- Subcommands surfaced via argparse: `register`, `pair`,
  `set-pairing`, `publish-plugin`, `ack-server`, `loop`.
- The bundle isn't built in this commit — author must run
  `cd plugin && ./build.sh` followed by the signer. Same shape
  as `sdk-plugin/examples/minimal/`.

## What I need from each reviewer

1. **Per-area verdict** on:
   - `plugin/src/plugin.ts` — manifest shape, render correctness,
     `platform.network.fetch` usage.
   - `plugin/manifest.json` / `build.sh` — matches the
     `sdk-plugin/examples/minimal` convention?
   - `bot.py publish-plugin` — manifest hash computation matches
     what the server validates? (Spec §5.)
   - `bot.py ack-server` — `/api/ack` shape, idempotent dedupe by
     message_id, 409 on replay matches the docs?
   - `README.md` — buildable from this walkthrough alone?
2. **Anything missing** the agreement called for that's not in.
3. **Anything new** (security, footgun, doc accuracy) introduced.
4. **Overall**: ready to commit / specific blockers / hold.

## Output

Per reviewer:
1. Per-area: GREEN / YELLOW / RED.
2. Blockers if any.
3. Commit-readiness vote.

If both reviewers GREEN, I commit Phase 5b and move to Phase 5c
(`npm create @syncler/plugin` scaffold).
