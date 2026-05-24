# Consultation 70 — Phase 5 / V1.5 DX track plan

This is the design discussion for the V1.5 Developer-Experience
items added to `docs/ROADMAP.md` (V1.5 DX section, items 6–9).
The pattern mirrors consultation 50 for V1: triad-agree the plan
first, then implement each phase with code → triad-review →
commit cycles.

## Why a discussion pass before code

External plugin-author review (lottery-claude, consultation 68)
identified four DX items that meaningfully hurt first-impression
quality:
1. Automated pairing (no more manual copy-paste of `user_id` +
   `pairing_key_hex` from device to backend).
2. Full round-trip example (a plugin and sender in the same
   directory that exercise the whole protocol).
3. `npm create @syncler/plugin` scaffold.
4. Validator polish backlog (move "passes SDK, fails server"
   footguns into the SDK validator).

Of those, **#1 is a protocol change with security implications**
— the bootstrap-key payload has to land in the sender's backend
without the server seeing it in plain. The other three are
mostly mechanical. Hence: design pass with the triad before
writing code, especially for #1.

## Proposed phase split (Phase 5a → 5e)

### Phase 5a — Automated pairing handshake (protocol change)

**Goal**: replace today's manual flow ("user types `user_id` +
`pairing_key_hex` from the device screen into the sender's
backend") with an automated bootstrap. The user scans a QR, taps
confirm in the Android app, and the sender's backend gets the
pairing tuple within seconds, without any out-of-band copy/paste.

**Threat model**: the pairing key is a 32-byte AES-256 shared
secret. If the syncler server, or any network attacker between
device and broker URL, learns it, they can decrypt every
subsequent message that sender → user.

**Proposed flow**:

1. Sender includes a public broker URL in the pairing initiate
   request (or the existing QR token already implies it — see
   open question below). Broker URL is HTTPS-only in release.
2. User scans QR. The Android app shows the sender's fingerprint
   (same as today). User confirms.
3. Post-confirm, the Android app builds a bootstrap payload:
   `{ user_id, pairing_key }` JSON, encrypts it under the
   **sender's Ed25519 public key** (the same key the sender
   signed `pairing/initiate` with), using a hybrid scheme
   (Ed25519 public key → X25519 conversion → ECDH against a
   fresh ephemeral key → HKDF → AES-256-GCM nonce + ciphertext).
4. Android POSTs the encrypted bootstrap to the sender's broker
   URL. Server-side, the broker is the sender's own backend — we
   don't run a broker. So the syncler server NEVER sees the
   plaintext pairing key.
5. The sender's backend decrypts with its Ed25519 private key
   (converted to X25519), gets `(user_id, pairing_key)`,
   completes pairing locally via `client.set_pairing(...)`.
6. SDK's `wait_for_pairing(...)` polls the sender's own broker
   storage (in-memory dict, Redis, whatever the sender chose)
   for completion. SDK doesn't talk to the syncler server for
   this step — the syncler server isn't on the bootstrap path
   at all.

**Backwards compatibility**: the manual flow stays. Senders who
don't supply a broker URL fall back to today's "user types hex"
UX. The QR can carry both: scan-and-confirm always works, the
broker URL is optional metadata.

**Open questions for the triad**:

- **Where does the broker URL live in the QR?** Today the QR is
  a pairing token + the syncler broker URL. Do we add the
  sender's broker URL as a separate field in the QR payload, or
  do we add it server-side (sender registers a broker URL with
  syncler, syncler embeds it in `pairing/initiate` response)?
  The latter avoids QR-size blowup and lets us validate the URL
  shape at sender registration time.
- **Hybrid encryption scheme**: convert Ed25519 → X25519 + ECDH
  is the standard approach (libsodium provides
  `crypto_sign_ed25519_pk_to_curve25519`). Same for the device
  side. Confirm this is what we want or push back on a different
  primitive.
- **Replay protection**: the bootstrap payload must include a
  nonce or a token-binding so a captured POST can't be replayed
  to register a different user under the same pairing. Proposed:
  bind to the `pairing_id` server-issued at `pairing/initiate`,
  and the server includes a one-shot bootstrap token in the
  response that the device proves possession of (HMAC over the
  bootstrap payload using a key derived from the bootstrap
  token).
- **Network failure handling**: device fails to POST to broker
  URL → fall back to showing `user_id` + `pairing_key_hex` on
  screen (the V1 manual flow)? Or fail outright and require the
  user to re-scan?
- **Polling interval / timeout**: SDK's `wait_for_pairing(...)`
  polls how often, for how long?

**Files this would touch**:

- `server/app/schemas.py`, `server/app/routers/pairing.py`,
  possibly `server/app/services/senders.py` — broker URL field
  on sender registration + pairing initiate
- `android/feature/pairing/...` — post-confirm flow that
  encrypts + POSTs
- `android/core/crypto/...` — Ed25519 → X25519 + AES-GCM helper
- `sdk-python/syncler/client.py` — `wait_for_pairing(...)`
  implementation, decrypt helper, broker URL setup
- `sdk-python/syncler/crypto.py` — Ed25519 → X25519 + AES-GCM
  decrypt helper
- `docs/integration-guide.md`, `docs/crypto-spec.md` — new §
  documenting the bootstrap protocol
- New tests: server, Android, SDK
- New alembic migration if we add `broker_url` to the `senders`
  table

### Phase 5b — Full round-trip example

**Goal**: `examples/trading-bot/` becomes a directory that has
both a sender (Python backend) AND a plugin (TS bundle), wired
to talk to each other. After Phase 5a lands, the example also
exercises the automated pairing path.

**Proposed structure**:

```
examples/trading-bot/
  bot.py                 # sender side (already exists)
  state.json             # shared state (already exists)
  README.md              # rewritten to cover both sides
  plugin/
    src/plugin.ts        # plugin source
    manifest.json        # unsigned manifest
    build.sh             # esbuild
    dist/                # build output (gitignored or tracked)
    manifest.signed.json # post-sign artifact (gitignored)
```

The plugin renders a "P&L card" — title from `payload.pnl`,
subtitle from `payload.acct`, action button "Acknowledge"
posting back to a `/api/ack` endpoint the bot also serves.

`bot.py` gains:
- HTTP server for `/api/ack` + the broker URL endpoint from 5a
- `bot.py publish-plugin` subcommand that builds + signs + posts
  the plugin via `client.publish_plugin(...)`
- `bot.py loop` already exists; updated to use the published
  `plugin_row_id` automatically

**Open questions**:

- **Build dependency**: the example needs both Python and Node
  installed. README needs to be honest about that.
- **Real action handling**: the `/api/ack` endpoint should
  actually do something visible (log to a file, increment a
  counter in `state.json`) so authors can see the round-trip
  work without setting up a database.

### Phase 5c — `npm create @syncler/plugin` scaffold

**Goal**: `npm create @syncler/plugin <name>` generates a
starter directory with everything wired up.

**Proposed scaffold output**:

```
my-plugin/
  src/plugin.ts          # BasePlugin subclass, sensible defaults
  manifest.json          # UNSIGNED placeholders, fields filled
                         #   from interactive prompts
  build.sh
  package.json           # build / build:sign / publish scripts
  README.md              # step-by-step instructions
  .gitignore             # dist/, manifest.signed.json, *.pem
```

Interactive prompts:
- plugin id (reverse-DNS, validated)
- plugin name (display string)
- sender id (UUID — paste from the syncler register response)
- renderer (script | template, default script)
- card_type (event | live, default event)
- capabilities (network / storage / showNotification, multi-select)

Built as a separate npm package `@syncler/create-plugin` that
`npm create` invokes.

**Open questions**:

- **Where does the scaffold live?** A new top-level
  `create-plugin/` directory in the syncler monorepo? A new
  `sdk-plugin/create/` subdirectory? Or its own repo?
- **Templating engine**: handlebars? plain string substitution?
  The scaffold is small enough that plain template literals
  inside the Node script are probably enough.

### Phase 5d — Validator polish backlog

**Goal**: items that "pass SDK, fail server" land in the SDK
validator one by one, so the failure surfaces at `npm build`
time rather than `client.publish_plugin(...)` time.

**Known starting items**:

1. `card_key` ↔ `card_key_path` mismatch — can't be fully
   checked at manifest validation time (it requires a payload),
   but we can at least flag manifests where `card_key_path` is
   declared but the plugin's example/test code doesn't reference
   it. **Defer** — probably not validator territory.
2. Plugin `id` charset enforcement — only `[a-zA-Z0-9.\-]`,
   reject Unicode / spaces / empty segments. Currently the
   regex is `[a-zA-Z][a-zA-Z0-9-]*(\.[a-zA-Z][a-zA-Z0-9-]*)+`
   which is fine; verify it matches what the server enforces.
3. `minPlatformVersion` numeric component caps — same 6-digit
   rule as `version`. Currently the regex is permissive.
4. Action endpoint URL validity — fully-qualified HTTPS only
   (debug allows http for LAN); reject schemes the host can't
   resolve.
5. `template.fields` keys that aren't in the layout's allowed
   set — catch at SDK time instead of forwarding to the server.
6. `cardKeyPath` regex tightening — V1 server accepts
   `startsWith("$")`; SDK could enforce the full
   `$.field(.subfield)*` grammar used for template fields
   ahead of the server.

This is a backlog, not a single big change. Phase 5d ships
whichever subset the triad agrees is worth catching now.

### Phase 5e — Docs + roadmap update

**Goal**: integration-guide and ROADMAP updates documenting
what 5a–5d shipped. Move V1.5 DX items 6–9 from "planned" to
"shipped." Add the automated-pairing protocol to crypto-spec.

## What I need from each reviewer

For each phase (5a–5e):

1. **Validate the proposed design.** Is it the right shape?
   Flag missed security concerns, protocol details, alternative
   approaches.
2. **Push back on scope.** Is any phase too big to land in one
   commit? Should we further-split 5a into protocol design + UX
   + SDK? Is 5d too vague to ship?
3. **Answer the open questions** I called out per phase.
4. **Flag anything I missed.** Lottery-claude was one external
   reviewer; you've seen the codebase for longer. What DX
   friction did they not hit that you know about?

For the overall plan:

5. **Phase sequencing.** I propose 5a → 5b → 5c → 5d → 5e.
   Reason: 5a is the protocol foundation, 5b uses it, 5c
   templates from 5b's structure, 5d is incremental, 5e closes.
   Push back on the order if there's a better one.
6. **Anything out of scope.** If a V1.5 DX item I've listed is
   actually V2 (or vice-versa), say so.

## Output

Per reviewer:
1. Per-phase: GREEN (go as proposed), YELLOW (minor refinements),
   RED (substantial rework needed).
2. Per-open-question: concrete answer.
3. Missing items: anything I should add to the plan.
4. Overall: ready to start Phase 5a / specific blockers / hold.

If both reviewers GREEN, I commit a `.triad/70-phase5-agreement.md`
synthesizing the agreement (mirror of
`.triad/50-agreement-and-plan.md`) and start Phase 5a.
