# Round 2 — Solo-parallel question generation

## Round 1 results (for your context)
- **Lead:** Claude (unanimous vote)
- **Protocol:** solo-parallel (2-1 vote; Gemini dissented in favor of devils-advocate — noted for planning phase)
- **Path:** questions-with-flagged-concerns (unanimous)

The triad is flagging these three concerns to the user up front, before any questions:
1. **Scope** — "V1 ready for everything" is the central risk; every technical choice is downstream of this.
2. **Timeline** — "2 weeks with Claude" will collide with reality.
3. **Audience** — "Starts with me, ships to everyone" is two different products with different V1 decisions.

## Your task this round

Generate **12–15 sharp questions for the user**. You are working **solo-parallel**: you cannot see the other non-lead's questions. Claude (the lead) will merge, dedupe, prioritize, and send the user the first batch.

These are questions **FOR THE USER**, not for the other models. The user explicitly wants real, hard questions — they said they want a "real friend giving honest feedback, not an agreeable assistant." Press where it hurts.

## Coverage — make sure your set touches at least these areas (more is fine)

- **V1 scope boundary** — what's in, what's out, and the principle for deciding
- **Success criteria** — testable, observable, what makes V1 a win vs. a failure
- **Timeline reality** — calendar, working hours, what they'd cut if forced
- **First-user workflow** — the user's own daily-use case for the first 30 days, before any third party integrates
- **Pending card lifecycle** — sender crashes, user offline for a week, double-submit, expiry, cancellation, resumability
- **Identity & pairing** — how senders find the user, how the user trusts a new sender, how strangers (services) pair
- **Security & compliance posture** — encryption (E2E or transit?), KYC implications, data retention, who's liable if a card leaks
- **Declarative components vs plugins** — what *exactly* can't be done with declarative-only?
- **Two-sided market** — does the user have a plan for this, or are we ducking it for V1?
- **Monetization** — if any, when, what model
- **Stack / hosting / deployment preferences** — opinions or open?
- **Naming** — "card" is a placeholder; what's the product called?
- **Failure modes** — what would make the user abandon this in month 3?

## Output format

Numbered list. Each question 1-2 sentences. No preamble, no commentary, no section headers — just the questions.

Example of the tone we want:
> 1. You said "V1 perfect, ready for everything." Name three features that are NOT in V1 — if you can't, you haven't scoped it.
> 2. Pick one: 30 days from launch, V1 succeeded if (a) you used it daily, (b) 100 strangers signed up, (c) one third-party service integrated. Which one?

Sharp. Specific. Forcing-function style. Avoid generic "have you thought about X?" questions — those let the user off the hook.

Output just the numbered list. Nothing else.
