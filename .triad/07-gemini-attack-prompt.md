# Round 7 — Gemini: devil's advocate attack on Claude's plan draft

You volunteered for this role. The triad voted you in as attacker on Round 6. Codex leads this phase and will arbitrate your attack against Claude's draft.

## Your job

Attack the plan below. Find what is wrong, weak, miscalibrated, hand-waved, or missing. You're not being polite. Specifically pressure-test:

1. **Hour estimates** — especially M4 (28h plugin SDK + WebView host) and M5 (18h push + background). Are they realistic on Windows-dev / Android-target?
2. **Cut order** — if budget breaks at 100h, is Claude cutting the right things, in the right order?
3. **FCM data messages + Foreground Service for encrypted dynamic notifications** — does this actually work on Android 12/13/14 with battery restrictions? Or is M5 fundamentally at risk?
4. **WebView per-plugin isolation** — is Claude's approach (separate WebView instance + distinct storage path) actually sufficient on Android? What attacks does it not stop?
5. **Key hierarchy** — any cryptographic mistake or hand-wave? Argon2id parameters, HKDF salts, AES-GCM nonce reuse risk?
6. **Supabase + Fly.io for V1 vs user's "AWS free tier" answer** — Claude is proposing to override the user's stated preference. Defensible or arrogant?
7. **Spec-doc page cap at 6 pages** — arbitrary. Is it too tight? Too loose? How would we know?
8. **Plugin bundling format** — signed single .js bundle. Issues with code-signing on Android WebView load?
9. **The Gap 2 punt to plugins** — user agreed platform doesn't enforce action idempotency. Does this create real foot-guns for plugin authors?
10. **The "dogfood DX test"** — if it fails because the spec doc is bad, what does that prove? Is the test load-bearing or theatrical?

## What's NOT in scope for your attack

- The product model itself (plugin-first, multi-device, zero-knowledge) — the user committed to those. Pointing out the model is hard is not useful; pointing out where the *plan* is wrong about implementing the model is useful.
- The scope decision (V1 = self-use + 2 senders + dogfood). Settled.

## Output format

```
=== ATTACK SUMMARY ===
<2-3 sentences: where's the plan strongest, where's it weakest>

=== SPECIFIC ATTACKS ===
1. <claim about what's wrong>
   evidence: <what supports your attack>
   severity: <critical | high | medium | low>
   proposal: <what should change>

2. ...
[Generate 8-15 specific attacks. Don't pad. Don't be diplomatic.]

=== MISSED ENTIRELY ===
<things Claude's plan doesn't mention but should — bulleted list>

=== STAYS IN PLAN ===
<things you tried to attack but couldn't break — bulleted list, max 5>
```

## The plan you're attacking

[FULL PLAN CONTENT FOLLOWS BELOW]

