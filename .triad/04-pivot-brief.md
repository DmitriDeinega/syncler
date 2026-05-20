# Round 4 — Major pivot brief + Batch 3 contribution

## What changed since Round 3

Lead (Claude) and both of you (Codex, Gemini) had a wrong mental model of the product. Reading Q9 + Q11 from the user revealed it. Re-stating the corrected model below — this is what we're actually building.

## Corrected product model

**Platform ships NO card types.** It does NOT define schemas, lifecycles, renderers, or "card components."

**The platform IS:**
- Encrypted message bus (server is a dumb relay; cannot read content)
- Plugin runtime on the user's phone (Android-only V1, WebView-based)
- Pairing system (sender ↔ user, once)
- Push notification wake-up
- Two SDKs:
  - **Plugin SDK** (JS, inherited by plugin authors, runs in WebView on phone)
  - **Server SDK** (lives on sender's backend, calls `client.send_to(user_id, encrypted_blob)`)
- Marketplace with platform-side code review (developer uploads → we approve → user installs)

**Each sender writes its own plugin:**
- Inherits `BasePlugin`
- Defines its own data shape
- Renders its own UI
- Declares its own actions (e.g., "button calls https://lottery.app/api/played with payload X")
- Manages its own storage / lifecycle / dedupe / retries on the sender's backend
- The platform has no opinion on any of this

**Concrete lottery flow:**
1. We write a spec doc (the SDK contract).
2. Lottery's Claude Code reads it, writes `LotteryPlugin`, uploads to marketplace, we approve.
3. User installs `LotteryPlugin` on their phone once.
4. Lottery backend → `client.send_to(user_id, encrypted_blob)` → our server → push → phone wakes → `LotteryPlugin.render()` shows numbers + button → user taps → plugin POSTs to lottery's declared endpoint → done.
5. Server never saw any of it.

## User's confirmed answers

- **Encryption:** E2E. Server blind. (Q6)
- **Auth:** Signed messages — option (c). Combined with E2E. (Q8)
- **Mobile:** Android-only V1. iOS deferred until traction. (Q15-context)
- **Runtime:** WebView. (Q15)
- **Distribution:** Marketplace with platform review. We test/approve before plugins go live. (Q14)
- **Budget framing:** Plugins are not in the 80-hour platform budget. Only the platform itself counts.

## User's open clarifications (just sent to user, pending answer)

- Per-plugin permission prompts at install vs trust-the-marketplace-only
- Server SDK language scope: Python only / Python + JS / docs+curl
- Notifications: static text declared once vs dynamic text generated per-message by plugin code

## Implications worth thinking about

⚠️ Marketplace with code review is a real product surface. Submission portal, review queue, approve/reject workflow, versioning, browse UI. Probably 30–50 hours on its own = ~half the platform budget. Open question: ship marketplace in V1 or stub it as "URL install" with marketplace at V1.5?

⚠️ Dynamic notifications on Android require the plugin's JS to run during push receipt (background WebView or service-worker-style). Architecture is doable but adds complexity. Static notifications are dead simple.

⚠️ The 80-hour platform scope under this model is roughly:
- Server (HTTP API, push fan-out, message storage, pairing): 15–20h
- Plugin SDK + WebView host on Android: 25–35h
- Server SDK (Python): 5–8h
- Marketplace + review tooling: 20–40h (huge variance)
- Pairing UX (QR? URL?): 5h
- Push notification plumbing (FCM, encrypted payload, plugin decrypt-and-show): 10–15h
- Crypto (E2E key exchange, signing): 8–12h
- TOTAL: 88h–140h. The 80h target is tight even before testing.

⚠️ Open: Android native (Kotlin) vs Capacitor/Ionic vs React Native vs Flutter. Not yet asked.

⚠️ Open: where does the platform server run? Hosting choice.

⚠️ Open: pairing ceremony in detail (QR? user issues per-sender token? both?).

⚠️ Open: marketplace review automation — who reviews? Manual? Static scan + manual? What gets blocked?

⚠️ Open: plugin update mechanism — how does a user get v1.1 of LotteryPlugin?

⚠️ Open: plugin sandbox — what API surface does the plugin actually get? Network calls only to declared endpoints? Storage? Camera? Photo picker?

## Your task this round (solo-parallel still)

### Part A — React (3 sentences max)

Anything in the corrected model that bothers you? Anything the lead missed in the implications? Disagree with anything the user committed to?

### Part B — Generate Batch 3 questions (6–8 questions)

Focus on the open items above. Push particularly on:
- **Marketplace V1 vs V1.5 cost tradeoff.** This is the single biggest scope choice left.
- **Android stack choice.** Shapes everything downstream.
- **Plugin sandbox capabilities.** What can a plugin actually do? Network only? Storage? Camera? This is the API contract.
- **Pairing ceremony** in detail now that we know it's E2E + signed.
- **Plugin update mechanism.**
- **Server hosting.**

Format:
```
=== REACTION ===
<3 sentences>

=== BATCH 3 QUESTIONS ===
<numbered list, 6-8 sharp questions>
```

Be sharp. The user moves fast; vague questions waste their time.
