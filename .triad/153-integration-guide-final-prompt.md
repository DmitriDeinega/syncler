=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 153 — integration guide final confirm

Codex flagged four §12 issues at consultation 152 (push
endpoint body mismatch, missing server-public-key
discovery story, webhook body shape mismatch, no usable
plugin-side snippet). I applied all four at b1e5728.

This is the final-pass check before declaring
`docs/integration-guide.md` shippable as the single
file we hand to partner projects.

## What I want from you

1. Re-read §12 only (lines roughly 750–900) and confirm
   each of the 152 FIXes is now accurate vs the shipped
   code in:
   - `server/app/routers/live.py` (LivePushRequest, push handler)
   - `server/app/live/webhook.py` (body shape, signatures)
   - `sdk-plugin/src/index.ts` (platform.live.connect/send/close, onLiveMessage hook)

2. Skim §11, §13, §14, §15 for any net-new accuracy
   regressions introduced since 152.

3. **Ship verdict** — yes / no. If no, the exact remaining
   blockers with line numbers.

Cap your response at 250 words. We're at the polish line.
