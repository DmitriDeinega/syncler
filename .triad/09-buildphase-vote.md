# Round 9 — Re-vote on lead + protocol for the build phase

## Status

Planning phase complete. User has accepted the 216h scope (time doesn't matter to them), confirmed every detail. Final plan = Codex's arbitrated plan (Round 8) + user's final scope confirmations:

- All bridge APIs (camera, gallery, file, location) confirmed in V1
- Single-device-only is non-cuttable
- Cut list deleted — scope is the scope
- I1: `platform.storage` has per-key `scope: device|user`
- I2: plugin sets `group_id`; default `(sender_id, plugin_id)`
- I3: V1 ships without email verification; add pre-ship
- I4: full anti-spoofing posture (fingerprint confirm, locked name+icon hashes, sig verification, paired badge, always-visible pubkey)
- I5: `DELETE /v1/account` in M1
- I6: per-device uninstall; when all devices uninstall, server purges + sender gets `410 Gone`

Ready to start building.

## Your task

Vote for the build phase:

### Lead
- `claude` — orchestrator with full conversation continuity, good at synthesis under uncertainty
- `codex` — operational, produced the arbitrated plan, owns the contract
- `gemini` — sharpest at edge case detection, but build needs execution more than critique

### Protocol
- `solo-parallel` — non-leads work independent build artifacts in parallel; lead assembles
- `joint` — all three collaborate on each piece
- `chained` — one drafts, next reviews, lead arbitrates
- `devils-advocate` — drafter steelmans, attacker tears down, lead arbitrates (good for design-heavy work)
- `lead-builds-others-review` — lead writes the code, others review milestones
- `other:<your-proposal>`

Consider that the build phase will probably take many sub-rounds (M1 then M2 then ...). Vote for a protocol that scales.

## Output format

```
=== VOTE: BUILD PHASE ===
lead: <claude|codex|gemini>
lead_reason: <2 sentences>

protocol: <option>
protocol_reason: <2 sentences>

=== STARTING POINT ===
<which milestone do we start with? what's the first concrete commit / artifact you'd produce?>
```

Sharp. The next message after this triggers actual code being written.
