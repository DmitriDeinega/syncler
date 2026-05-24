# Consultation 65 — Phase 4: docs validation

Phase 3 landed dual-green at commit `df24e17`. Phase 4 is the
public-facing documentation set that plugin authors will build
against. The user explicitly asked for the docs to be **very
descriptive and accurate and validated with the triad** — they
intend to build a test plugin against these docs from another
project, so accuracy is the gate.

## Files to review

1. `docs/integration-guide.md` — the main onboarding document.
   ~470 lines. Plugin authors read this end-to-end.
2. `docs/crypto-spec.md` — the canonical V1 crypto contract.
   ~330 lines. Other implementations (server, Android, SDKs)
   must agree byte-for-byte.
3. `docs/ROADMAP.md` — public roadmap. ~52 lines. Sets
   expectations on what's shipped vs forthcoming.

## What changed in Phase 4

### integration-guide.md
- **§1 "What you're building"** — rewritten to introduce the two
  renderer modes (`script` / `template`) and two card types
  (`event` / `live`) up front, so readers know which combination
  they want before reading further.
- **"What the host does for you"** — added live-card pinning
  rule, cross-device dismiss filter, SSE / FCM hint channel.
- **§5.1 Native Template Renderer** — expanded the stub into a
  full section: a working `publish_plugin(...)` example with
  `renderer="template"`, the JSONPath dialect rules, the
  `standard_card` layout's required-and-allowed fields, the
  action-POST contract (host posts the full decrypted payload,
  no Authorization header), and the publish-time validator rules
  with explicit 422 trigger conditions.
- **§5.2 Live Cards** — expanded the stub: full
  `publish_plugin(..., card_type="live", card_key_path="$.x")`
  example, a complete `upsert_card(...)` example showing where
  the live-card AAD comes from, and an explicit table of the
  4xx gates the server enforces on upsert (pairing, plugin
  active, TTL window, sequence monotonicity, rate limit). The
  `delete_card` example now passes `user_id` (consultation 62
  RED #5 security fix is now exposed in the public API surface).
- **§7 Action callback** — split into a script-renderer path
  (plugin builds the body via `platform.network.fetch`) and a
  template-renderer path (host POSTs the full decrypted payload
  verbatim with no Authorization header — the security-boundary
  invariant from consultation 62 RED #1 is now documented).
- **§8 Testing** — replaced the stale "15s poll" line with the
  SSE flow + FCM background fallback.
- **§9 Common errors** — added Phase 3b card-upsert errors
  (410/400/409 per the consultation 63 router-mapping fixes).

### crypto-spec.md
- **§8 Live Cards** added: the AAD shape (7 fields, integer
  `sequence_number`, `Z` suffix on ISO timestamps), upsert
  envelope canonical JSON, delete envelope canonical JSON. The
  delete envelope explicitly requires `user_id` and documents
  *why* (the cross-user-delete vulnerability from consultation
  62 RED #5).
- Python reference snippets at the bottom now include
  `assemble_live_card_aad`, `assemble_live_card_upsert_envelope`,
  `assemble_live_card_delete_envelope` so other SDK authors can
  copy-paste an authoritative canonical-bytes builder.

### ROADMAP.md
- Replaced the confusing "Phase 4 / 5 / 6 / 7 / 8" labeling
  (which collided with the internal implementation phases used
  in consultations) with **V1 / V1.5 / V2 / V3 / V4** version
  milestones. V1 is the shipped state (matches commit
  `df24e17`); the others are sequenced by platform dependency.
- Top of the file explicitly distinguishes the public
  roadmap-versioning from the internal implementation phases
  tracked in `.triad/50-agreement-and-plan.md`, so future
  readers can map between the two if they need to.

## What I need from each reviewer

1. **Accuracy against the code.** For every API signature,
   error code, JSON shape, or HTTP status documented in the
   guide, confirm the code at `df24e17` matches. Specifically:
   - `client.publish_plugin(...)` keyword arguments + defaults.
   - `client.upsert_card(...)` keyword arguments + defaults
     (including server-enforced 48h cap, 1/sec rate limit, the
     409 sequence-regression status).
   - `client.delete_card(...)` requires `user_id` (Codex 62 RED
     #5 fix).
   - `client.send_to(...)` payload shape + `hostPreview` caps.
   - `client.revoke_plugin(...)` reason enum + UX descriptions.
   - Phase 3b error codes (410 plugin not live / no pairing,
     400 TTL, 409 sequence) are the codes the router actually
     returns after the consultation 63 mapping fix.
   - SSE event types in the §1 "what the host does for you"
     block match what `EventStreamManager` parses.

2. **Crypto canonical bytes.** Confirm the live-card AAD shape
   in `crypto-spec.md §8.1` byte-for-byte matches:
   - `android/core/crypto/.../Aad.kt:62 LiveCardAad`.
   - `sdk-python/syncler/crypto.py` (if it has a live-card
     helper) or the inline construction in the live-card SDK
     methods.
   - The server's `assemble_envelope({...})` invocations in
     `app/routers/cards.py` for upsert and delete envelopes.
   Specifically: `sequence_number` is an integer literal in
   JSON (no quotes); `expires_at` uses `Z` suffix not `+00:00`;
   UUIDs are lowercase no-brace form before signing.

3. **Plugin-author buildability.** Could a developer who has
   never seen this codebase build a working **template-renderer
   live-card** plugin from these docs alone? List the gaps. Be
   ruthless — if a step needs guessing, it's a doc bug.

4. **Security-relevant statements.** Verify the docs correctly
   describe:
   - The host explicitly NOT attaching `Authorization` to
     template-renderer action POSTs (the §1 paragraph + §5.1
     bullet + §7 paragraph all need to agree).
   - The user-id binding in the delete envelope.
   - The server-enforced 48h TTL cap (not caller-controlled).
   - The publish-time validator rules for template manifests.

5. **Anything missing.** Plugin lifecycle gaps, common error
   conditions not covered, security caveats the docs gloss
   over, pricing/quota considerations the platform doesn't yet
   document.

## Output

Per reviewer:
1. Per-file verdict: GREEN / YELLOW / RED with specific
   doc-line references for any non-GREEN.
2. Specific code-vs-doc mismatches with file:line citations on
   both sides.
3. Specific buildability gaps a plugin author would hit.
4. Overall: ready to commit / ready after fixes / hold.

If both reviewers GREEN, Phase 4 commits as-is and the user has
their public-contract documentation set. If either flags
accuracy issues, fix and re-fire.
