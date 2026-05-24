# Consultation 68 — External DX review from a real plugin author

After Phase 4 landed (commits `09c377a` docs + `f5858ce` triad
archive), the user handed `docs/integration-guide.md` to a separate
Claude instance ("lottery-claude") that is building a real test
plugin in another project. That instance came back with a tight,
specific DX critique. The user wants the triad to validate the
findings, prescribe fixes, and decide what's in scope for a
Phase 4.1 DX polish commit vs what belongs on the roadmap.

This is the first external review the V1 surface has received.
Treat it as authoritative on developer experience — the issues are
things a plugin author actually hit, not a static-review nitpick.

## Verbatim external review

> ### What's solid
>
> - `docs/crypto-spec.md` — rigorous and complete. AAD shapes,
>   envelope canonical bytes, KDF flow, Python reference snippets,
>   even live-card AAD. Nothing to fix here.
> - Server endpoint surface — no "secret" endpoints; everything
>   maps cleanly to the integration guide's workflow. No
>   undocumented escape hatches.
> - Manifest validation source-of-truth
>   (`sdk-plugin/src/manifest.ts:111-204`) — strictly enforces the
>   renderer ↔ template ↔ cardType ↔ cardKeyPath matrix the guide
>   claims. The guide isn't lying.
>
> ### What needs work (priority order)
>
> **1. The bundleHash/signature footgun is unowned.**
> `manifest.ts:127` only checks "is this a hex string." `"00"`
> passes. The server only catches it at publish time with a 422.
> Your own SDK README (line 34) tells authors to write
> `bundleHash: '00'` with no "for development only" annotation. We
> hit this and chased the wrong cause for ages. Fix: either (a)
> make the SDK validator require 64/128 hex chars, or (b) document
> the `"00"` means I haven't signed yet pattern explicitly.
>
> **2. Pairing is half-built and the docs admit it.**
> `sdk-python/README.md:29-37` has a comment "(M7 wires this; for
> now in dev, use `client.set_pairing(...)`)" — this reads like a
> TODO leaking into shipped docs. The integration guide §8 fills
> the gap with manual "copy `user_id` and `pairing_key_hex` from
> app UI, paste into Python" — fine for dogfood, bad for a
> first-impressions guide. The plugin SDK README doesn't cover
> pairing at all and the python SDK README is mid-refactor. Pick
> one canonical home for the pairing dance and finish it.
>
> **3. The wildcard rule is buried in code.**
> `sdk-plugin/src/network.ts:48-62` is the only canonical
> reference. The SDK README mentions "single path or host segment
> wildcard" in one line; the integration guide is silent. As I
> noted in my first pass: this cost us a session. One sentence in
> the guide near `declaredEndpoints` would prevent every author
> from hitting it.
>
> **4. No example plugin exists.**
> `examples/` has one directory (`trading-bot`) and its README
> literally says "This example doesn't ship a plugin; it just
> exercises the sender side." So the only place authors can see an
> actual `render()` function is the inline snippets in the
> integration guide. There's no `examples/minimal-plugin/`, no
> `npm create @syncler/plugin` scaffold, no copy-and-modify base.
> This is the single biggest "scale to 10 plugin authors" risk —
> every author starts from a blank canvas.
>
> **5. `platform.network.fetch` error contract is undocumented.**
> The integration guide §3 mentions it works. Nothing tells you
> what it throws on offline, what it returns on 5xx, or whether
> there's a timeout. Authors building offline-aware cards (like
> ours) have to discover this by trial and error. Document the
> contract.
>
> **6. ROADMAP.md is feature-focused, ignores DX.**
> The roadmap covers nonce-replay durability, multi-process plugin
> host, per-device encryption — all platform internals. None of
> the DX issues we hit (empty-hash footgun, no example plugin,
> half-built pairing, undocumented wildcard, no fetch error
> contract) appear anywhere on the roadmap. That's a signal the
> platform team isn't tracking developer-facing friction as work.
>
> ### One structural suggestion
>
> The integration guide is 11 sections of prose and the "Common
> errors" §9 is a flat list. As the surface grows you'll want a
> "Common pitfalls" section separate from server error codes —
> pitfalls is "things that pass validation but bite you later"
> (empty hash, single-segment wildcard, missing `registerPlugin`,
> `cardKeyPath` ↔ `card_key` mismatch), errors is "the server said
> no, here's what to do." Today they're conflated and that hides
> the real footguns.
>
> ### Bottom line
>
> Crypto and protocol are solid. The DX layer is roughly where we
> found it in our session: the platform works, but the on-ramp
> expects an author who'll read code when docs disagree. Fixing #1,
> #2, #3, and #4 would meaningfully change first-impression
> quality. #5 and #6 are smaller but cheap wins.

## What the triad needs to decide

For each finding (1–6 + structural):

1. **Validate the claim.** Cross-check against the actual code at
   HEAD (`f5858ce`). Cite file:line on both sides. Is the
   complaint factually correct, partially correct, or wrong?

2. **Classify the fix.** One of:
   - **Phase 4.1 (commit now)**: small doc/SDK-validator change,
     no architectural decision needed.
   - **Phase 4.5 (medium effort)**: needs design (e.g. scaffold
     command, example plugin), but doable as a follow-up commit
     in the V1 milestone before broader rollout.
   - **V1.5+ roadmap**: needs deferred design or platform work.

3. **Concrete prescription.** Specific files to change with line
   refs. For doc additions, suggest the exact section + 1–3
   sentence framing. For SDK changes, suggest the validator
   signature or scaffold command shape.

4. **Cross-finding interactions.** E.g. #4 (example plugin) and #3
   (wildcard documentation) probably want to land together so the
   example uses a wildcard endpoint and the docs explain why; #1
   (bundleHash footgun) likely needs both a validator change AND
   an example-plugin demonstration to be fully fixed.

## My read (for the triad to confirm or push back on)

The bullets I'd want to land in a Phase 4.1 DX polish commit:

- **#1**: validator-level fix in `manifest.ts` — require hex string
  length ∈ {64, 128} (32-byte SHA-256 = 64 hex; some bundle hashes
  may be 64-byte = 128 hex if anyone's using SHA-512). Plus a
  README annotation that the build tool (`sign-bundle.ts`) replaces
  the placeholder.
- **#3**: one paragraph in `docs/integration-guide.md §2` next to
  the `declaredEndpoints` reference + a code-comment in
  `sdk-plugin/src/network.ts` linking back to the guide section.
- **#5**: a new subsection in `docs/integration-guide.md §3` (or a
  new §3.1) documenting what `platform.network.fetch` throws and
  returns. Pull the actual error shapes from
  `android/.../capabilities/NetworkBridge.kt` and
  `sdk-plugin/src/runtime.ts`.
- **Structural**: rename §9 to "Common server errors" and add a
  new §9.1 "Common pitfalls" — empty hash footgun, single-segment
  wildcard, missing `registerPlugin()`, `cardKeyPath` ↔ `card_key`
  mismatch, host-renderer-only fields on template manifests.
- **#6**: add a "Developer experience" V1.5 section to ROADMAP.md
  listing #2 (pairing flow), #4 (example plugin + scaffold), and a
  named owner if the user wants one.

The bullets I'd want to defer:

- **#2 pairing dance**: lottery-claude is right it's half-built,
  but landing the canonical pairing flow needs UX work in the
  Android app (auto-issue a pairing-key copy screen post-confirm)
  AND server-side support for "did the user complete this token"
  polling. Document the current dogfood path more clearly and put
  the canonical flow on the V1.5 roadmap.
- **#4 example plugin**: agreed it's the highest-leverage thing
  for adoption, but writing a real `examples/minimal-plugin/` that
  actually round-trips needs ~half a day of work (write a tiny
  backend, sign a bundle, document the run loop). Roadmap it as
  V1.5 #16 (DX) and ship Phase 4.1 without it.

Triad: push back on this split if you disagree. Is anything I'm
deferring actually a 30-minute fix I'm overestimating?

## Output

Per reviewer:
1. Per-finding (1–6 + structural): validate (correct / partial /
   wrong), classify (4.1 / 4.5 / roadmap), prescribe.
2. Push back on my proposed split if you disagree.
3. Overall: ready to execute / specific blockers.

If both reviewers GREEN on the split, I'll execute Phase 4.1, fire
one more triad on the result, and ship.
