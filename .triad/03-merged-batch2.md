# Batch 2 — merged (Tier 1, adapted to the concrete V1 = trading bot + lottery)

Convergence high. Codex and Gemini independently asked the same questions about server visibility, plugin proof, auth, SDK shape, dedupe, response channel. Unique adds: Codex's per-data-type sensitivity split + concrete schema fields; Gemini's spec-doc page-limit DX bound + per-sender identity in feed.

## Batch 2 — 8 questions

**Q6. Server visibility (encryption).** For V1, can the server read card contents? Answer per data type:
- trading bot reports: server-readable or E2E?
- lottery number batches: server-readable or E2E?
- future photo/KYC uploads: server-readable or E2E?

This is the most foundational call — reversing it later means a rewrite.

**Q7. Plugin proof — concrete.** For your trading bot and lottery app specifically, name ONE interaction that genuinely cannot be done with declarative components + a sandboxed HTML escape hatch. Plotly chart in an iframe is the obvious candidate — if HTML works, plugins are out of the 80 hours.

**Q8. Trading bot auth.** AWS bot identifies itself how, V1:
- (a) single API key in env var (simple, hard to revoke per-card)
- (b) per-sender scoped key (medium effort, manageable)
- (c) signed request per card with HMAC or JWT (strongest, more SDK code)

Pick the simplest you'd actually deploy. Key rotation costs real hours later.

**Q9. Lottery card schema.** Name the exact fields for the lottery card so the SDK and spec doc can stay tight. Candidates: `title`, `number_list` (list of lists?), `draw_date`, `button_label` (`"Played"`), `notes`, `attachments`, `status`. Add or remove anything.

**Q10. "Played" response — shape + channel.**
- Shape: what does the lottery app receive on tap? `{card_id, action: "played", timestamp}` minimal, OR include the numbers, user notes, device info, full state?
- Channel: how does it arrive? (a) webhook the lottery app must host, (b) polling endpoint the lottery app calls, (c) persistent WebSocket from lottery app to platform, (d) "stored on server, sender fetches when they want." Pick one as the V1 default.

**Q11. Lifecycle — three edge cases.**
- (a) You tap "played" while phone is offline → what's on screen, when does the lottery app actually see it?
- (b) Trading bot sends 50 reports while you're on a 3-day trip → 50 separate cards, digest, rate-limited, sender-controlled grouping?
- (c) Trading bot crashes and replays the last 5 reports as duplicates → platform dedupes (via what — `idempotency_key`?), SDK dedupes, or you just see 5 cards?

**Q12. Python SDK shape.** Pick a direction:
- (A) Generic: `client.send_card(card_dict)` + `client.wait_for_response(card_id)`. Flexible, slightly verbose.
- (B) Purpose-built: `client.send_report(title, body, chart=...)`, `client.ask_confirmation(prompt, payload, button_label)`. Easy to write, freezes API shape early.
- (C) Both — generic underneath, helpers on top. Costs ~2x the SDK code.

**Q13. Sender identity + spec-doc bound.**
- Identity: each sender gets a name + icon in your feed. Sender provides on registration, you set manually in the app, or both?
- Spec-doc page limit: the document handed to the lottery app's Claude Code instance — what's the page cap? If "integrate a played button" needs >N pages, DX failed. Pick N.

---

Held back for Batch 3 (lower priority but still in the bank): scheduled sending platform-side?, naming the primitive, hosting choice, iOS PWA Day-1 test, abandonment risk, four-week cut order, KYC liability posture, two-sided market V1 stance.
