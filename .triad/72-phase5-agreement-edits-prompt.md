# Consultation 72 — Phase 5 agreement, post-edit confirmation

Consultation 71 returned:
- **Gemini**: all-GREEN, ready to commit.
- **Codex**: YELLOW pending 5 specific edits — none asked for a
  primitive change, all wording/security-clarity fixes.

The 5 Codex edits have been applied to
`.triad/70-phase5-agreement.md`. This is a tight confirmation
pass before committing the agreement and starting Phase 5a-1.

## Applied edits

### Edit 1 — AAD reconstruction sources `broker_url` from trusted broker state

The "AAD fields are echoed in the envelope" paragraph has been
rewritten. Key change: `broker_url` MUST come from the sender's
stored pairing state for `pairing_id`, NOT from any client-
supplied envelope field. The broker stored `broker_url` when the
sender called `pairing/initiate`; reconstructing AAD from that
source closes the syncler-server substitution attack.

Plus a new "Canonicalization rules" paragraph: UUID fields are
lowercase canonical, `broker_url` is the exact signed/echoed
byte string with no normalization, byte values use standard
padded base64, AAD JSON encoder is `sort_keys=True,
ensure_ascii=True, separators=(",", ":")`.

### Edit 2 — `exp` window wording

Step 2 of the broker handler now says: "Reject if `exp` is more
than 5 minutes in the past OR more than 5 minutes in the future
relative to broker time." (Was: "Reject if `exp` is in the past
or > 5 minutes future" — contradicted the 5-min skew
tolerance.) Parenthetical adds that CAS at step 10 is the real
replay stop, not `exp`.

### Edit 3 — Replay protection summary aligned

The replay-protection summary now reads "`exp` is set by the
device to 60 seconds from build time as a nominal TTL. The
broker accepts envelopes whose `exp` is within ±5 minutes of
broker wall time." Previous text said "caps lifetime at 60
seconds" which Codex flagged as misleading given the 5-min
broker tolerance.

### Edit 4 — Canonical AAD byte vector added to test-vector list

Test-vector list now includes: "Exact canonical bootstrap AAD
JSON bytes for a known tuple — assert the byte string so SDK
and host can't diverge silently. The vector MUST source
`broker_url` from the sender's stored pairing state per the
protocol rule above."

### Edit 5 — Ed25519 vector input clarified

The Ed25519 bootstrap-key signature vector entry now says: "the
input is the literal ASCII byte string `"syncler-v1-bootstrap-key:"`
(24 bytes) concatenated with the **raw 32-byte X25519 public
key** (NOT its base64 text)." Removes the "base64 vs raw bytes"
ambiguity that would have caused SDK/host divergence at the
first vector failure.

## What I need from each reviewer

Tight confirmation pass:

1. **Each of the 5 edits**: do they correctly address the
   consultation 71 concerns? GREEN / YELLOW / RED per edit.
2. **Any text the edits accidentally broke** — did the
   rewrite of the AAD reconstruction paragraph leave any
   stale prose referencing the old wording elsewhere in the
   document?
3. **Anything you'd still want changed** before starting
   Phase 5a-1 code (spec + vectors land in
   `docs/crypto-spec.md` §9 next, then Android/SDK
   implementation follows).

## Output

Per reviewer:
1. Per-edit verdict: GREEN / YELLOW / RED.
2. Document-level: ready to commit the agreement / specific
   remaining edits.
3. Overall: dual-green sign-off to commit and start 5a-1.

If both GREEN, I commit `.triad/70-phase5-agreement.md` and
move to Phase 5a-1.
