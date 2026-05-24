# Phase 5 / V1.5 DX track — full agreement

Synthesis of consultation 70 (both reviewers). Mirror of
`.triad/50-agreement-and-plan.md` for the V1 milestone — this
document anchors every Phase 5 code change. Disagreements
between Codex (YELLOW with concrete pushback) and Gemini (all
GREEN) are resolved here in Codex's direction where they touch
crypto/protocol, since Codex's concerns were security-substantive
and Gemini's were procedural.

## Phase sequencing

```
5a-1  Bootstrap protocol spec + test vectors      (this document + crypto-spec extension; NO Android/SDK code)
5a-2  Automated pairing implementation             (server + Android + SDK + tests)
5b    Full round-trip example                      (examples/trading-bot/ plugin + sender)
5c    npm create @syncler/plugin scaffold          (create-plugin/ package)
5d    Validator polish backlog (defined batch)     (sdk-plugin manifest + tests)
5e    Docs + roadmap update                        (move V1.5 DX 6–9 to "shipped")
```

Each phase runs the same code → triad → commit cycle the V1
milestone used. **No Android/SDK implementation begins until 5a-1
is committed and the spec/vectors are dual-green.**

## Phase 5a-1 — Bootstrap protocol spec

### Goal

Replace today's manual flow ("user types `user_id` +
`pairing_key_hex` from the device screen into the sender's
backend") with an automated POST from the device to a sender-
operated broker URL. The pairing key never reaches the syncler
server in plain.

### Trust model

- **Sender**: trusted (it's the destination). Holds an Ed25519
  signing key (already exists in V1) and a NEW X25519 bootstrap
  public key (added in Phase 5a). The X25519 private key lives
  in the sender's backend, alongside its Ed25519 private key.
- **Syncler server**: treated as active-curious. Could attempt to
  substitute its own broker URL or its own X25519 key into the
  `pairing/preview` response. The protocol MUST detect either
  substitution via the sender's Ed25519 signature over the
  initiate payload.
- **Network between device and broker**: TLS protects in
  transit; HPKE-style envelope encryption protects against TLS
  termination at the syncler-server boundary or at a hostile
  CDN.
- **Device**: trusted endpoint of pairing; sees the pairing key
  in clear after decrypt.

### Key change: separate X25519 bootstrap key (NOT Ed25519→X25519 conversion)

The sender registers **two** public keys with syncler:
- `signing_key` — the existing Ed25519 public key (32 bytes).
  Unchanged from V1.
- `bootstrap_key` — a new X25519 public key (32 bytes). The
  sender generates this once when it adopts the automated flow.

The sender SIGNS its X25519 bootstrap key with its Ed25519
signing key (`Ed25519.sign(signing_priv, "syncler-v1-bootstrap-key:" || bootstrap_pub_x25519)`)
and supplies the signature once at sender registration time (or
via a separate `/v1/senders/me/bootstrap-key` endpoint for
existing senders). The signature binds the bootstrap key to the
sender identity so syncler can't substitute its own key into
`pairing/preview`.

**Why separate keys instead of Ed25519→X25519 conversion**:
- Library support is uneven. Python `cryptography` and Android's
  default crypto providers don't expose Ed25519→X25519 conversion
  as first-class. Pulling in libsodium just for this is more
  surface area than adding a second key field.
- Audit clarity: the bootstrap operation has its own keypair, its
  own purpose, its own rotation lifecycle. Mixing key usage
  (signing AND key-agreement on the same long-term key) is
  conservative-bad-practice even when it's safe with proper
  domain separation.
- Operational flexibility: a sender can rotate the bootstrap key
  without rotating the signing key (or vice versa). V1.5+ will
  add explicit key rotation; making them independent up front
  keeps that option open.

### Wire flow

**Sender registers bootstrap key** (new endpoint, called once or
on rotation):

```
POST /v1/senders/me/bootstrap-key
Headers: signed by Ed25519 signing key (existing pattern)
Body: {
  "bootstrap_key": "<base64 X25519 pub, 32 bytes>",
  "bootstrap_key_signature": "<base64 Ed25519 sig over 'syncler-v1-bootstrap-key:' || bootstrap_key>"
}
```

Server verifies the Ed25519 signature, stores the X25519 pub key
on the `senders` row, and a hash `bootstrap_key_id =
SHA-256(bootstrap_key)[:16]` for stable identification.

**Sender initiates pairing** (extended existing endpoint):

```
POST /v1/pairing/initiate
Body (signed): {
  "sender_id": "...",
  "ttl_seconds": 300,
  "metadata": {...},
  "broker_url": "https://sender.example.com/syncler/bootstrap",  // NEW
}
Returns: {
  "pairing_id": "...",
  "pairing_token": "...",
  "broker_url": "<echoed back, server-validated>",
  "bootstrap_protocol_version": 1,
}
```

The `broker_url` is part of the sender-signed canonical envelope,
so syncler can't substitute its own value. If `broker_url` is
absent, the pairing falls back to the V1 manual flow.

**Server validates `broker_url` shape**:
- Release: `https://` only.
- Debug: `http://` allowed on private/LAN ranges (10.x, 172.16-31,
  192.168.x, localhost).
- No credentials in URL (`user:pass@host` rejected).
- No URL fragment.
- Length ≤ 2048 chars.
- DNS resolvable (deferred; checked best-effort at pairing init).

**Device fetches preview** (existing endpoint, extended response):

```
GET /v1/pairing/preview?token=<pairing_token>
Returns: {
  "sender_id": "...",
  "sender_name": "...",
  "sender_public_key": "<base64 Ed25519 pub>",
  "sender_public_key_fingerprint": "...",
  "sender_name_hash": "...",
  "expires_at": "...",
  "broker_url": "<echoed>",                          // NEW
  "bootstrap_key": "<base64 X25519 pub, 32 bytes>",  // NEW
  "bootstrap_key_signature": "<base64 Ed25519 sig>", // NEW
  "bootstrap_protocol_version": 1,                   // NEW
}
```

Device verifies the bootstrap key signature against the sender's
Ed25519 public key BEFORE encrypting anything. If the verification
fails, the device refuses to proceed (treats it as syncler-side
key substitution).

**User confirms in the Android app.** The app builds the
bootstrap payload:

```
plaintext = JSON: {
  "user_id": "<uuid>",
  "pairing_key": "<base64 32 bytes>"
}
```

**Bootstrap encryption** (HPKE-style):

1. Device generates an ephemeral X25519 keypair `(eph_priv, eph_pub)`.
2. `shared_secret = X25519(eph_priv, sender.bootstrap_key)`.
3. `aead_key = HKDF-SHA256(salt=eph_pub || sender.bootstrap_key, ikm=shared_secret, info="syncler-v1-bootstrap-aead", length=32)`.
4. `nonce = 12 random bytes`.
5. `aad = JSON canonical bytes: {`
   `  "protocol_version": 1,`
   `  "pairing_id": "<uuid>",`
   `  "sender_id": "<uuid>",`
   `  "broker_url": "<echoed>",`
   `  "bootstrap_key_id": "<base64 16 bytes>",`
   `  "exp": "<ISO8601 UTC, 60s from now>"`
   `}`.
6. `ciphertext_with_tag = AES-256-GCM(aead_key, nonce, plaintext, aad)`.
7. Bootstrap envelope on wire (JSON):
   ```
   {
     "protocol_version": 1,
     "pairing_id": "...",
     "sender_id": "...",
     "bootstrap_key_id": "<base64>",
     "exp": "<ISO8601>",
     "ephemeral_pubkey": "<base64 32 bytes>",
     "nonce": "<base64 12 bytes>",
     "ciphertext": "<base64 ciphertext_with_tag>"
   }
   ```

AAD fields are reconstructed from a MIX of envelope fields and
broker-side trusted state, NOT just echoed from the envelope.
`protocol_version`, `pairing_id`, `sender_id`, `bootstrap_key_id`,
and `exp` come from the envelope (the client supplied them, AEAD
tag binds them). `broker_url` MUST come from the sender's stored
pairing state for `pairing_id`, created when the sender called
`pairing/initiate` — never reconstructed from an untrusted source.
The broker MUST reject if the stored `broker_url` for that
`pairing_id` does not match byte-for-byte what the broker expects
to be in AAD. This is what stops a syncler-server substitution
attack: a hostile server could rewrite the AAD's `broker_url` in
the client-constructed AAD input to a value that matches the
wrong broker (note: `broker_url` is bound into AAD but never
emitted in the wire envelope); sourcing from sender-trusted
state on the broker side defeats that.

Canonicalization rules (binding to the spec):
- UUID fields (`pairing_id`, `sender_id`) are lowercase no-brace
  canonical (`str(uuid.UUID(value))` output).
- `broker_url` is the exact byte string signed in `pairing/initiate`
  and echoed in `pairing/preview`. Implementations MUST NOT
  lowercase, normalize, re-encode, or strip it before AAD
  assembly.
- `bootstrap_key_id` and other byte values use standard padded
  base64.
- AAD JSON encoder: `sort_keys=True, ensure_ascii=True,
  separators=(",", ":")` — same as message AAD in §4 of
  `docs/crypto-spec.md`.

**Device POSTs the envelope** to `broker_url`:

```
POST {broker_url}
Content-Type: application/json
Body: <envelope above>
```

No `Authorization` header. The broker is sender-operated; the
sender authenticates by being able to decrypt. (The
`broker_url` itself is a sender-chosen secret if the sender
wants endpoint-based auth — embed a token in the path.)

**Sender's broker handler**:

1. Validate envelope shape, parse JSON.
2. Reject if `exp` is more than 5 minutes in the past OR more
   than 5 minutes in the future relative to broker time. (The
   ±5min window covers clock skew on both sides; the real
   replay guard is the compare-and-set on `pairing_id` at step
   10, not the `exp` field.)
3. Reject if `pairing_id` is unknown (sender's storage maps
   `pairing_id` → broker state created at `pairing/initiate`
   time, including the broker_url the sender supplied for that
   pairing). Important: this is sender-side validation; the
   sender's backend stores `pairing_id` after issuing the
   pairing init.
4. Reject if `bootstrap_key_id` ≠ SHA-256(sender's
   bootstrap_pub)[:16] (key-rotation guard).
5. Reconstruct AAD bytes: take `protocol_version`, `pairing_id`,
   `sender_id`, `bootstrap_key_id`, `exp` from the envelope
   fields, AND take `broker_url` from the sender's STORED
   pairing state for this `pairing_id` (NOT from any envelope
   field — a hostile server could rewrite that). Re-emit the
   AAD via the canonical JSON encoder (sort_keys, ensure_ascii,
   compact separators).
6. `shared_secret = X25519(sender.bootstrap_priv, envelope.ephemeral_pubkey)`.
7. `aead_key = HKDF-SHA256(salt=envelope.ephemeral_pubkey || sender.bootstrap_pub, ikm=shared_secret, info="syncler-v1-bootstrap-aead", length=32)`.
8. `AES-256-GCM-decrypt(aead_key, envelope.nonce, envelope.ciphertext, aad)`. Tag failure → reject 401.
9. Parse plaintext: `{user_id, pairing_key}`.
10. Compare-and-set into broker storage keyed on `pairing_id`:
    - If unset, store `(user_id, pairing_key)`. Return 201.
    - If already set with same `(user_id, pairing_key)`, return
      200 idempotently.
    - If already set with DIFFERENT values, return 409 (replay
      attack or conflicting request).

**SDK polling**:

```python
client.create_pairing_qr(ttl_seconds=300, broker_url="https://...")
pairing = client.wait_for_pairing(
    timeout_seconds=120,      # default
    poll_interval_seconds=1,  # default; jitter ±20%
)
# pairing.user_id and pairing.pairing_key are now populated.
# Equivalent to set_pairing(...) being called automatically.
```

The SDK provides a default in-memory `BrokerStorage` for dev,
plus a `BrokerStorage` protocol so production users can wire it
to Redis / a DB. The SDK's `wait_for_pairing` polls the local
storage; the SDK does NOT call the syncler server during
bootstrap.

### Replay protection summary

- AAD binds `pairing_id`, `sender_id`, `broker_url`,
  `bootstrap_key_id`, `exp`, `protocol_version`.
- `pairing_id` is one-shot at the broker via compare-and-set —
  this is the real replay stop, not the `exp` field.
- `exp` is set by the device to 60 seconds from build time as a
  nominal TTL. The broker accepts envelopes whose `exp` is
  within ±5 minutes of broker wall time (clock-skew tolerance
  on both sides). The CAS at step 10 catches actual replay.
- A captured envelope replayed to the same broker hits the CAS
  guard (409 conflict, or 200 idempotent if the contents match).
- A captured envelope replayed to a DIFFERENT broker fails
  because `broker_url` is in AAD.
- A captured envelope replayed for a DIFFERENT pairing fails
  AAD verification.

### Network failure handling

If the device's POST to `broker_url` fails (timeout / 4xx / 5xx /
DNS / TLS), the Android app shows the V1 manual screen
(`user_id` + `pairing_key_hex`) with a banner: "automatic
pairing failed, see logs / enter manually." User can retry the
POST or copy/paste into the sender's backend. The manual flow
is NEVER removed — automated pairing is a path on top of it, not
a replacement.

### Polling defaults

- `poll_interval_seconds`: 1.0
- `timeout_seconds`: 120
- Jitter: ±20% of `poll_interval_seconds`
- Both configurable on `wait_for_pairing(...)`

### Test vectors

Phase 5a-1 ships crypto test vectors before any Android/SDK
code:

- HKDF context bytes for a known `(eph_pub, bootstrap_pub,
  shared_secret)` triple — assert the derived `aead_key`.
- Full AEAD round-trip: known AAD JSON, known plaintext, known
  nonce, expected ciphertext.
- **Exact canonical bootstrap AAD JSON bytes** for a known
  `(protocol_version, pairing_id, sender_id, broker_url,
  bootstrap_key_id, exp)` tuple — assert the byte string so SDK
  and host can't diverge silently. The vector MUST source
  `broker_url` from the sender's stored pairing state per the
  protocol rule above.
- Ed25519 signature over the bootstrap-key registration input.
  The input is the literal ASCII byte string
  `"syncler-v1-bootstrap-key:"` (24 bytes) concatenated with the
  **raw 32-byte X25519 public key** (NOT its base64 text). The
  vector asserts the signature over a known
  `(ed25519_seed, bootstrap_pub_raw)` pair.

Vectors land in `docs/crypto-spec.md` §9 (new), with `pytest`
assertion in `server/tests/test_crypto.py` and JUnit assertion
in `android/core/crypto/.../SpecVectorsTest.kt`.

## Phase 5a-2 — Implementation

Server, Android, SDK code implementing the spec from 5a-1.
Triad cycle reviews the implementation against the spec.

**Out of scope for 5a-2** (deferred to V2 or later):
- Broker URL discovery from registered sender metadata (V1.5
  uses per-initiate `broker_url`).
- Sender bootstrap-key rotation UX.
- Push-based completion (broker pings SDK instead of polling).

## Phase 5b — Full round-trip example

`examples/trading-bot/` becomes a directory with both sender and
plugin co-located:

```
examples/trading-bot/
  README.md       # rewritten; explicit about Python + Node dependencies
  bot.py          # sender side (existing); gains /api/ack + broker URL endpoint
  state.json      # extended with ack_count + ack_history (visible round-trip)
  plugin/
    src/plugin.ts
    manifest.json # UNSIGNED placeholders
    build.sh
    # dist/ and manifest.signed.json are gitignored
```

`/api/ack` mutates `state.json`:
- increments `ack_count`
- appends `{timestamp, item_id}` to `ack_history` (capped at 100
  entries, FIFO)

Plugin renders `payload.title` + `payload.body` + an "Acknowledge"
button that POSTs to `/api/ack`. After Phase 5a lands, the
sender side uses `wait_for_pairing(...)`.

## Phase 5c — `npm create @syncler/plugin` scaffold

New package `create-plugin/` at the syncler monorepo root.
Published as `@syncler/create-plugin`. `npm create @syncler/plugin <name>`
runs it.

Interactive prompts:
- plugin id (regex-validated reverse-DNS)
- plugin name (human-readable)
- sender id (UUID — paste from `client.register_if_needed()`)
- renderer (`script` | `template`, default `script`)
- card_type (`event` | `live`, default `event`)
- capabilities (multi-select: `network` / `storage` /
  `showNotification`)

Scaffold output:

```
my-plugin/
  src/plugin.ts              # BasePlugin subclass, defaults
  manifest.json              # UNSIGNED placeholders, fields filled
  build.sh
  package.json               # build / build:sign / publish scripts
  README.md                  # step-by-step instructions
  .gitignore                 # dist/, manifest.signed.json, *.pem
```

Plain template literals inside the Node script. No handlebars.
Tiny sender stub or pointer to `examples/trading-bot/`.

## Phase 5d — Validator polish backlog (first batch)

Define what lands now vs later. **First batch** (this commit):

1. `minPlatformVersion` numeric component caps (6 digits each)
   — match the existing `version` regex.
2. Action endpoint URL validation: HTTPS in release; http only
   for LAN private ranges in debug.
3. `template.fields` keys that aren't in the layout's allowed
   set: SDK rejects, instead of forwarding to the server's 422.
4. `cardKeyPath` full `$.field(.subfield)*` grammar
   (currently `startsWith("$")`).
5. Plugin `id` regex parity check: confirm the SDK regex matches
   the server's character class exactly.

**Deferred** (not in 5d, file in V1.5 DX #9 backlog):
- `card_key` ↔ `card_key_path` payload-side mismatch — not pure
  manifest validation; needs payload sample or test convention.
- `template.actions.endpoint` glob-shape validation beyond
  HTTPS — overlaps with #2; revisit if needed.

## Phase 5e — Docs + roadmap update

Move V1.5 DX items 6–9 from "planned" to "shipped" in
`docs/ROADMAP.md`. Add automated pairing protocol spec to
`docs/crypto-spec.md` (already drafted in 5a-1).
`docs/integration-guide.md` gains:
- §1 mention of automated pairing
- §8 (testing) updated walk-through
- Pointer to the full round-trip example
- Pointer to the `npm create` scaffold

## Disagreements resolved

| Topic | Codex | Gemini | Resolution |
|---|---|---|---|
| Encryption primitive | Separate X25519 bootstrap key | Ed25519→X25519 conversion | **Separate X25519 key** (Codex) — library friction + audit clarity outweighs the "standard" argument. |
| Spec/vectors before code | YES, gate 5a-2 on 5a-1 | Not raised explicitly | **YES** (Codex) — matches the V1 pattern where Phase 0/1/2 each had spec agreement before code. |
| Broker URL location | `pairing/initiate` request (signed) | `pairing/preview` response | **Both** — sender puts it in initiate (signed); server echoes in preview. |
| Polling interval | 1s / 120s with jitter | Not raised | **1s / 120s with ±20% jitter** (Codex). |
| Replay token mechanism | AAD binding only; avoid server-issued HMAC token | (Not specified) | **AAD binding** (Codex). The Syncler server is not on the bootstrap trust path; adding a token it knows would weaken that boundary. |

## Out of scope for the whole Phase 5 milestone

- Push-based pairing completion (broker pushes to SDK). Polling
  is fine for V1.5; push is a V2 add.
- Sender key rotation UX (bootstrap key OR Ed25519). Manual
  rotation works; guided UX is V1.5+.
- Marketplace plugin distribution. V1 + V1.5 stay sideload-only.
- Mobile pairing UX redesign (animated progress, etc.). The
  current screen gets a banner + fallback affordance; full UX
  polish is V2.

## Cross-reference

The V1 master plan lives in `.triad/50-agreement-and-plan.md`.
This document is the V1.5 DX equivalent. Public roadmap mirror:
`docs/ROADMAP.md`. Public integration guide:
`docs/integration-guide.md`.
