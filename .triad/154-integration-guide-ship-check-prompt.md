=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 154 — integration guide ship check

Both 152 and 153 FIX rounds applied. Latest commit:
0065257.

Re-scan `docs/integration-guide.md` end-to-end. Look only
for **integration-breaking** drift vs the shipped code in:
- `server/app/routers/live.py` (push endpoint shape)
- `server/app/live/webhook.py` (webhook body + signing)
- `sdk-plugin/src/index.ts` + `bridge.ts` + `base-plugin.ts` (TS SDK surface)
- `sdk-python/syncler/client.py` (Python SDK surface)
- `server/app/routers/rotation.py` (rotation endpoints)

If you find ANY integration-breaking issue, list it with
the line number + the contradicting shipped behavior.

If you find none, say "ship" in one line. Don't review
cosmetics, voice, or scope this time — only contract
accuracy.

Cap response at 200 words.
