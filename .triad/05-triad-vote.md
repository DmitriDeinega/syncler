# Round 5 — Triad vote on delegated technical decisions

The user answered Batch 3 and delegated 4 things to triad. Vote needed on each.

## User's confirmed answers
- Q19: V1 = sideload/URL install (user is only user). Marketplace required before public ship → V1.5.
- Q21: "Yes to all" on plugin capabilities (network unrestricted, storage, camera, file picker, location, background exec). Lead reading this as "all those capabilities are AVAILABLE in the sandbox" — still pending user clarification.
- Q24: AWS free tier for V1. Plan transition before ship.

## User clarifications still pending (lead is asking user separately)
- Q16: per-plugin permission prompt at install — yes or no
- Q18: notification = template-based (declarative) or code-based (plugin runs JS at receive time)
- Q21: "all available" vs "every plugin gets all by default"

## Lead's recommendations — vote yes / no / alternative

### Q20 — Android stack: **Native Kotlin host + WebView for plugins**
**Reason:** The outer app's job is hosting other code, push delivery with encrypted payloads, background execution, fine-grained permissions per plugin, plus the WebView that runs plugin JS. Those are deeply native concerns. Capacitor puts the host itself in a WebView, then nests plugin WebViews — bad fit. React Native + Flutter need extensive native bridging for FCM-with-encrypted-payload, background execution policies, and per-plugin WebView sandboxing. The cost of "do it native" is mostly Kotlin learning curve; the cost of every other option is fighting the framework. User said "correctness, not laziness" — native is the correct call.

### Q22 — Pairing ceremony: **Sender-publishes-QR**
**Reason:** Sender publishes a QR encoding `{ plugin_manifest_url, one_time_pairing_token, sender_public_key_fingerprint }`. User scans with their phone app → app fetches the manifest → verifies signature → shows install confirmation with declared capabilities → user taps install → app generates user keypair, exchanges with sender's server via the one-time token → pairing done. Why QR over URL-paste: (a) phone is already in hand, (b) one-time token can be short-lived and embedded, (c) prevents typo-domain phishing. URL-paste (option b from user's choices) is a fine fallback for headless contexts but QR is the V1 default.

### Q17 — Server SDK language: **Python V1, JS/TS V1.1, curl-friendly docs from day 1**
**Reason:** Both of user's concrete senders (trading bot, lottery app) are Python-friendly. The lottery's Claude Code dogfood test happens in Python world. Building two SDKs in parallel doubles the surface to maintain and doesn't unlock anything user has actually committed to. Curl examples cover Node/Go/Rust senders for "make it work" at zero cost. Add JS/TS in V1.1 when there's a JS sender that matters.

### Q23 — Plugin updates: **Prompt-to-update for V1 (URL install era), auto-update with marketplace re-review gate for V1.5**
**Reason:** In V1, plugins are installed by URL — no review gate catches a malicious update. Auto-update would let a compromised sender push code to user's phone silently. Prompt-to-update is one tap; user is the only user; not annoying. When marketplace ships V1.5, re-review on each version becomes the trust anchor, and auto-update is safe again.

## Your task

For each of Q20, Q22, Q17, Q23 — vote:
- `agree` (with lead's recommendation)
- `disagree: <alternative>` (briefly say why and propose)
- `agree-with-caveat: <caveat>` (accept but flag something)

Then: **Is the triad ready to move to planning after this round and the user's pending clarifications?** Or is there a critical question we haven't asked yet?

## Output format

```
=== VOTES ===
Q20: <agree|disagree:...|agree-with-caveat:...>
Q22: <agree|disagree:...|agree-with-caveat:...>
Q17: <agree|disagree:...|agree-with-caveat:...>
Q23: <agree|disagree:...|agree-with-caveat:...>

=== READY FOR PLANNING? ===
<yes / no — if no, what's missing>

=== ANYTHING THE LEAD MISSED ===
<optional, 1-3 sentences max>
```

Sharp votes. Disagree if you actually disagree.
