# Consultation 66 — Phase 4 docs, follow-up after the consultation 65 fixes

Consultation 65 returned:
- **Codex**: RED on `integration-guide.md` + `crypto-spec.md`, YELLOW on
  `ROADMAP.md`. Concrete file:line citations on six issues.
- **Gemini**: all-GREEN, but it ran in yolo mode and applied *some* of
  the fixes itself (sorted AAD JSON keys in crypto-spec, added the SDK
  helpers to `sdk-python/syncler/crypto.py`, extended
  `sdk-plugin/src/manifest.ts` validator), leaving:
  (a) the rate limit + cross-device dismiss + card_key_path JSONPath
      inaccuracies in integration-guide unfixed,
  (b) `encrypt_payload` example still broken (passes the tuple as the
      payload scalar after generating its own redundant nonce),
  (c) a syntax-broken tail on `manifest.ts`
      (`'object' && value !== null && !Array.isArray(value); }`),
  (d) UUID canonicalization missing from the new Python helpers
      (they used `str(value)` instead of `str(uuid.UUID(value))`),
  (e) `manifest.ts` validator added strict required-field checks for
      `renderer` / `cardType` that the server treats as optional with
      defaults — would have rejected legacy manifests.

## Fixes applied since consultation 65

### `docs/integration-guide.md`
- Rewrote the live-card upsert example to call `encrypt_payload` with
  keyword-only args and destructure `(nonce, ciphertext)` from the
  tuple it returns. Added a note that you MUST use the returned
  nonce, not generate your own.
- Added `hostPreview` to the live-card plaintext example so the
  example matches the doc's earlier guidance about always populating
  it.
- Added an explicit "card_key MUST equal what card_key_path resolves
  to" warning, with a parenthetical that the server's V1 validation
  of `card_key_path` is `startswith("$")` rather than the full
  JSONPath grammar used for template fields.
- Rate limit corrected to **60/min per (sender, user, card_key)**
  with a note about the IP-bucketed pre-auth limit of 120/min
  (matches `server/app/middleware/rate_limit_config.py:27-29`).
- Cross-device dismiss bullet rewritten to be accurate: the dismiss
  writes a `DeliveryStatus` row keyed on `(message_id, device_id)`,
  the inbox filter is per-device, the SSE dismiss event fans out so
  other devices can refresh but their feeds keep the row until they
  dismiss themselves. V1 design choice; "dismiss everywhere" is V1.5.

### `docs/crypto-spec.md`
- Python reference helpers now use `_canon_uuid(value)` (which is
  `str(uuid.UUID(str(value)))`) for `sender_id` / `user_id` /
  `plugin_id` in the live-card AAD, upsert envelope, and delete
  envelope builders. Added a small docstring explaining why.
- The AAD canonical JSON example was already in sorted order
  (Gemini applied that fix in consultation 65).

### `docs/ROADMAP.md`
- V1 bullet on the settings sheet now says "sender mute, pairing
  revoke" with a clarifying sentence that the sender-side
  `client.revoke_plugin(...)` is a separate API not exposed in the
  host UI.

### SDK fix-ups
- `sdk-plugin/src/manifest.ts`:
  - Deleted the orphan `'object' && value !== null && !Array.isArray(value); }`
    that Gemini left dangling at the bottom (would have failed `tsc`).
  - Extended `PluginManifest` interface with the four Phase 3 fields
    (`renderer`, `template`, `cardType`, `cardKeyPath`) plus the new
    `TemplateField` / `TemplateAction` / `TemplateBlock` types.
  - Reworked Gemini's validation to match the server's lenient
    semantics: `renderer` / `cardType` are optional with defaults
    (`'script'` / `'event'`); only the pairings are enforced
    strictly (`template` required iff `renderer === 'template'`,
    `cardKeyPath` required iff `cardType === 'live'`). Also added a
    `cardKeyPath.startsWith('$')` check matching the server.
  - `tsc --noEmit` now passes.
- `sdk-python/syncler/crypto.py`:
  - The three live-card helpers now use `_canon_uuid` for the UUID
    fields so an uppercase or braced caller can't produce a sig
    mismatch against the server. Added `import uuid`.
  - Smoke-imported via the project venv; signature still matches
    the helpers the integration-guide example imports.

## What I need from each reviewer

1. **Re-verify the six issues Codex flagged in consultation 65** are
   now resolved against the actual code at HEAD (uncommitted). Cite
   file:line on both sides.
2. **Verify the manifest.ts changes** don't break existing SDK
   consumers — the `PluginManifest` interface gained four optional
   fields, the validator is now lenient on missing renderer/cardType
   but strict on pairings.
3. **Verify the `_canon_uuid` addition** to the Python helpers
   matches what `_canon_uuid` does in `sdk-python/syncler/client.py`
   (both are `str(uuid.UUID(str(value)))`; they should produce the
   same bytes for the same input).
4. **Buildability check.** Could a developer follow §3 →
   `npm install @syncler/plugin-sdk`, copy the `PluginManifest`
   from the integration-guide TS example, and have it pass
   `validatePluginManifest`? Could they follow §5.2's live-card
   example end-to-end and have it round-trip through the server
   without a 401/410/400/409?
5. **Anything else** I missed between consultation 65 findings and
   what's actually in the tree right now.

## Files at HEAD (uncommitted)

```
M docs/integration-guide.md
M docs/crypto-spec.md
M docs/ROADMAP.md
M sdk-plugin/src/manifest.ts
M sdk-python/syncler/crypto.py
```

## Output

Per reviewer:
1. Per-file verdict: GREEN / YELLOW / RED with file:line cites for
   anything non-GREEN.
2. Buildability check pass/fail with reasoning.
3. Overall: ready to commit / specific blockers / hold.
