# Batch 3 — merged from Codex + Gemini reactions to the pivot

## Triad agreement on the pivot
- Both agreed the corrected model is coherent.
- Both flagged: budget exceeds 80h by 8–60h once marketplace is in scope.
- Both recommend deferring full marketplace to V1.5; use URL/sideload install for V1.
- Codex flagged a real tension: "platform has no opinion" vs "we review plugins" — review is itself a policy. We need a rule set.
- Codex flagged: counting plugins outside the budget is misleading if the lottery plugin is part of the acceptance test. (Not re-pressing — user already decided.)

## Pending from last message
- **Q16** — per-plugin permissions at install vs trust marketplace only
- **Q17** — server SDK language scope
- **Q18** — notifications static vs dynamic

## Batch 3 — new questions
- **Q19. Marketplace V1 or V1.5?** Full marketplace (submission portal, review queue, browse UI) is 30–50 hours. V1.5 alternative: V1 lets you install a plugin by pasting a URL or sideloading a file. Both Codex and Gemini recommend deferring full marketplace to V1.5. Pick.
- **Q20. Android stack.** Pick the one you're willing to debug when push, background execution, WebView storage, and crypto get weird:
  - (a) Native Kotlin + WebView (most control, steepest if you don't know Kotlin)
  - (b) Capacitor / Ionic (web app + thin native shell — fastest to ship)
  - (c) React Native (JS, native UI)
  - (d) Flutter (Dart, polished, more learning)
- **Q21. Plugin sandbox API — what can a V1 plugin do?** Every "yes" is part of the permanent contract. Mark each:
  - Render UI in WebView (assumed yes)
  - Network: only to its declared backend endpoint / any URL
  - Local persistent storage on the phone
  - Camera / photo picker
  - File picker
  - Location
  - Background execution (required if Q18 = dynamic notifications)
- **Q22. Pairing ceremony — pick the V1 UX.**
  - (a) Sender shows QR (URL + one-time token). User scans → app fetches plugin metadata → exchanges E2E keys with sender → install.
  - (b) Sender publishes a handle/URL. User pastes into app → fetches → installs.
  - (c) Other (describe).
- **Q23. Plugin update mechanism.** When LotteryPlugin v1.1 publishes:
  - (a) Auto-update silently in the background
  - (b) Prompt user to approve each update
  - (c) Versions pinned per sender (no updates unless user explicitly chooses)
- **Q24. Server hosting for V1.** Choice affects push reliability, secrets, logs, backups, and deploy speed. Options: AWS, Fly.io, Render, Railway, generic VPS, self-host. Preference?

## Held for Batch 4 (lower priority)
- Who actually reviews plugins on day 1 (if marketplace V1) — manual, automated static scan, both
- Marketplace review rule set — what specifically gets rejected (the "no opinion vs review" tension Codex flagged)
- Plugin permission model details (granular URL patterns vs broad categories)
- Spec doc page cap
- Naming the product
- Abandonment risk
