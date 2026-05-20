# Round 2 — Merged question bank (lead-curated by Claude)

Source: Codex generated 15, Gemini generated 15, blind to each other. Strong overlap → independent convergence on what matters. Lead's role: dedupe, order by "unblocks the most other questions," batch.

## Tier 0 — Unblockers (Batch 1)
These define everything downstream. Until these are answered, scope, architecture, and timeline are guesses.

- **Q1. Success metric.** Pick exactly ONE V1 success criterion — the one we'll measure against at day 30. Options: (a) you personally use it daily, (b) 10–20 other developers use it weekly, (c) 100 strangers signed up, (d) one third-party service integrated. "All of them" is not an answer because it makes scope-cutting impossible. [Codex#2 ∩ Gemini#2]
- **Q2. First workflow.** Describe the ONE specific workflow you will run through this app every day for the first 30 days, before anyone else is on it. What does Claude Code (or whichever script) post? What does the phone screen show? What do you tap/type/upload? What goes back to the script? Give a concrete scenario, not a category. [Codex#3,#4 ∩ Gemini#4]
- **Q3. Five exclusions.** List five things that are explicitly NOT in V1. If you can't name five, V1 isn't a plan — it's a wishlist. [Codex#1]
- **Q4. 80-hour test.** Assume 80 total coding hours (2 weeks × 5 days × 8 hours). At hour 80 you demo ONE single working feature, end-to-end, to the triad. What is it? [Gemini#3]
- **Q5. The inclusion rule.** What's the rule for "is this in V1?" Pick one: (a) I need it for my daily workflow, (b) any developer would need it, (c) future services will need it, (d) it's foundational infra. This rule decides every "yes/no" from here. [Codex#6]

## Tier 1 — Identity, trust, and architecture
- **Q6. E2E or server-readable?** Are card contents end-to-end encrypted from sender to device, or can the server read them (for search, previews, debugging)? Pick one for V1; you can't defer this. [Codex#11 ∩ Gemini#14]
- **Q7. Plugin proof.** Name three concrete interactions that genuinely cannot be modeled with declarative components + sandboxed HTML escape hatch. If you can't name them, why are renderer plugins in V1? [Codex#13 ∩ Gemini#1,#8]
- **Q8. KYC liability posture.** If a user uploads passport/KYC photos and that data leaks, the V1 posture is: (a) "we don't support regulated data yet," (b) "encrypted, can't see it," or (c) "we accept compliance obligations." Pick one. [Codex#12 ∩ Gemini#7]
- **Q9. Two-sided market.** V1 either ducks the chicken-and-egg by being self-use + agent-developer-use only, OR commits to one side first (build for Alpaca → hope users come, or for users → hope Alpaca comes). Which? [Codex#14 ∩ Gemini#9]

## Tier 2 — State and trust mechanics
- **Q10. Pairing ceremony.** How does a new sender get permission to reach you, V1: (a) manually-issued API key, (b) QR pairing, (c) email invite, (d) public handle, (e) phone number. Pick the V1 default. [Codex#9 ∩ Gemini#6]
- **Q11. Spoofing.** What prevents a fake "Alpaca" sender from successfully requesting KYC photos from a user who trusts the brand name? [Codex#10]
- **Q12. Card lifecycle.** When you answer a "Buy or Sell?" card six hours late: who decides if the answer is still valid (sender, server, user, schema)? And what about: sender crashed mid-card, user submits twice from two devices, phone offline for a week then receives stale prompts? [Codex#7,#8 ∩ Gemini#5]

## Tier 3 — Product surface
- **Q13. Hosting.** Where does V1 run: Raspberry Pi in your closet, VPS, AWS/GCP, Vercel/Cloudflare, somewhere else? Choice eats hours and shapes architecture. [Gemini#11]
- **Q14. Naming.** "Card" is a placeholder. Propose three candidate names for the unit of interaction — and the product itself. [Gemini#12]
- **Q15. Monetization V1.** Does V1 cost you money to run? Self-funded indefinitely, or monetization baked in? [Gemini#10]
- **Q16. Four-week cut.** If forced from "2 weeks fantasy" to "4 weeks part-time," what's the first thing cut: plugins, file previews, push notifications, search/tags, mobile polish, third-party pairing? [Codex#5]
- **Q17. iOS PWA test.** First step before "build the app": one-button PWA that does nothing but receive a push on iPhone. Will you time-box that to Day 1 and pivot to native wrapper if it fails? [Gemini#15]
- **Q18. Abandonment trigger.** Month 3, you haven't shipped, fighting CSS for the tenth time. Which is the most likely reason you quit: not using it yourself, technical complexity, no users, unreliable notifications, security anxiety, boredom with infrastructure? [Codex#15 ∩ Gemini#13]

## Batch plan
- Batch 1 = Tier 0 (Q1–Q5). Send now.
- Batch 2 = Tier 1 (Q6–Q9). After Batch 1 answers in.
- Batch 3 = Tier 2 + Tier 3, possibly trimmed based on what's already resolved.
