# Consultation 71 — Phase 5 agreement review

Consultation 70 returned:
- **Gemini**: all-GREEN, ready to start.
- **Codex**: YELLOW on Phase 5a — wanted spec/vectors before
  implementation, pushed back on Ed25519→X25519 conversion,
  required comprehensive AAD binding, and asked for the 5d batch
  to be pre-defined.

I drafted `.triad/70-phase5-agreement.md` synthesizing both. This
consultation is a confirmation pass on the agreement document
BEFORE any Phase 5 code lands. Mirror of how `.triad/50-agreement-and-plan.md`
gated the V1 milestone.

## Disagreement resolutions in the agreement

| Topic | Codex | Gemini | Adopted |
|---|---|---|---|
| Encryption primitive | Separate X25519 key | Ed25519→X25519 conversion | **Codex** (library friction + audit clarity) |
| Spec before code | YES, split 5a into 5a-1 + 5a-2 | Not raised | **Codex** (matches V1 phase pattern) |
| Broker URL location | `pairing/initiate` (signed) | `pairing/preview` (echoed) | **Both** — sender supplies in initiate; server echoes in preview |
| Polling | 1s/120s with jitter | Not raised | **Codex** — 1s/120s with ±20% jitter |
| Replay token | AAD binding only | (silent) | **Codex** — Syncler is off the bootstrap trust path; no server-issued HMAC token |

## Key design decisions to validate

1. **Two-key model**: sender registers Ed25519 signing key
   (V1) + NEW X25519 bootstrap key (V1.5). Bootstrap key
   signed under the Ed25519 key at registration time.
2. **HPKE-style envelope**: ephemeral X25519 keypair on device,
   ECDH against sender's bootstrap_pub, HKDF-SHA256 with
   domain-separated info string (`"syncler-v1-bootstrap-aead"`),
   AES-256-GCM with a 12-byte random nonce.
3. **AAD binds**: `protocol_version=1`, `pairing_id`,
   `sender_id`, `broker_url`, `bootstrap_key_id` (SHA-256 of
   the bootstrap pub, first 16 bytes), `exp` (60s from device,
   5min skew tolerance at broker).
4. **Broker is sender-operated**: syncler is not on the
   bootstrap trust path. Sender stores `pairing_id → broker
   state` after pairing init; CAS-stores
   `(user_id, pairing_key)` on POST.
5. **Manual fallback NEVER removed**: on POST failure, Android
   shows V1 `user_id` + `pairing_key_hex` screen with a banner.
6. **Polling**: SDK `wait_for_pairing(timeout=120, poll_interval=1)`
   defaults; ±20% jitter; SDK polls local broker storage, NOT
   the syncler server.

## Phase 5d first batch (now)

1. `minPlatformVersion` numeric component caps (match `version` regex).
2. Action endpoint URL: HTTPS in release; http only for LAN
   private ranges (10.x, 172.16-31, 192.168.x, localhost) in debug.
3. `template.fields` keys not in layout's allowed set: SDK
   rejects.
4. `cardKeyPath` full `$.field(.subfield)*` grammar.
5. Plugin `id` regex parity with server.

Deferred (file in backlog, not 5d): `card_key`↔`card_key_path`
payload-side check, `template.actions.endpoint` shape beyond HTTPS.

## What I need from each reviewer

1. **Validate the resolution of each disagreement.** Does the
   adopted choice address your concern? If Codex still wants
   something different on encryption, flag it now — once 5a-1
   ships with test vectors, changing the primitive is costly.
2. **Spot-check the HPKE-style construction.** Specifically:
   - HKDF salt = `eph_pub || sender.bootstrap_pub` (32+32=64 bytes)
   - HKDF info = `"syncler-v1-bootstrap-aead"` (literal)
   - HKDF length = 32 (AES-256-GCM key size)
   - AAD as JSON canonical bytes (sort_keys, ensure_ascii,
     compact separators) — same encoder as message AAD in §4.
   - `bootstrap_key_id = SHA-256(bootstrap_pub)[:16]`
   Anything you'd change here?
3. **Validate the AAD field set.** Is anything missing or
   redundant? `protocol_version`, `pairing_id`, `sender_id`,
   `broker_url`, `bootstrap_key_id`, `exp`.
4. **Validate the broker compare-and-set semantics.** First POST
   wins, second POST with same `(user_id, pairing_key)` is
   idempotent 200, second POST with different values is 409.
5. **Validate the `exp` window**: 60s build-time TTL, 5min
   clock-skew tolerance at broker. Tight enough? Too tight?
6. **Test vector requirements**: the agreement says 5a-1 ships
   crypto vectors before any Android/SDK code. Confirm that
   includes:
   - HKDF derivation for a known `(eph_pub, bootstrap_pub)`
   - Full AEAD round-trip for known AAD/plaintext/nonce
   - Ed25519 sig over bootstrap-key registration string
   Anything else needed?
7. **5d batch sizing**: 5 items in the first batch. Push back
   if any should defer to a later batch, or if anything obvious
   is missing.
8. **Overall**: ready to commit `.triad/70-phase5-agreement.md`
   and start Phase 5a-1 implementation? Or still YELLOW/RED
   on specific items?

## Output

Per reviewer:
1. Per-topic verdict: GREEN / YELLOW / RED with cited reasoning
   for any non-GREEN.
2. Specific edits to `.triad/70-phase5-agreement.md` if needed,
   ideally as diff-like prose ("change X to Y, line N").
3. Overall: ready to commit the agreement / blockers / hold.

If both reviewers GREEN, I commit the agreement document and
start Phase 5a-1: extending `docs/crypto-spec.md` with §9
(bootstrap protocol) + landing the test vectors. No Android or
SDK code lands until 5a-1 is dual-green.
