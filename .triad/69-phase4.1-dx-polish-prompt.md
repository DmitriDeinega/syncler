# Consultation 69 — Phase 4.1 DX polish, post-application review

Consultation 68 returned dual-GREEN with two specific pushbacks:
- **#1 stricter**: bundleHash must be exactly 64 hex (SHA-256, 32
  bytes), signature exactly 128 hex (Ed25519, 64 bytes). Not "64
  or 128" — strictly 64 for the hash, 128 for the signature.
- **#4 not deferred wholesale**: `sdk-plugin/examples/minimal/`
  already exists; Phase 4.1 should make it discoverable + replace
  the `"00"` placeholders.

All Phase 4.1 items have been applied. This is the post-application
review pass.

## Phase 4.1 changes applied

### #1 — Manifest validator + placeholder cleanup
- `sdk-plugin/src/manifest.ts:126-150`: validator now rejects
  `bundleHash` that isn't exactly 64 lowercase hex chars and
  `signature` that isn't exactly 128 hex chars. Error messages
  reference `sign-bundle.ts` so authors know where to look.
- `sdk-plugin/examples/minimal/src/plugin.ts`,
  `sdk-plugin/examples/minimal/manifest.json`: replaced `"00"`
  placeholders with `UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE`
  — loud failure mode that's unambiguous when an author forgets
  to sign. Inline comments in `plugin.ts` explain the build →
  sign → publish cycle and why the placeholders are deliberately
  invalid.
- `sdk-plugin/README.md`: rewrote the §"Minimal Plugin" section
  to walk through build → sign → publish with the actual command
  invocations. Pointer to the new minimal example.
- `sdk-plugin/test/{manifest,base-plugin,network}.test.ts`:
  updated to use `'a'.repeat(64)` / `'b'.repeat(128)` for hash and
  signature respectively. New tests in `manifest.test.ts` assert
  the length checks and the UNSIGNED placeholder rejection.
- SDK tests: 29/29 passing.

### #4 — Surface the existing minimal example
- `sdk-plugin/examples/minimal/README.md` (NEW): documents what
  this example is, what files it ships, how to build → sign →
  publish, and what it does NOT do (no backend round-trip yet —
  that's V1.5 #7).
- `examples/README.md` (NEW): top-level index pointing to
  `trading-bot/` (sender side) and `sdk-plugin/examples/minimal/`
  (plugin side). Notes that a combined round-trip example is
  tracked under V1.5 DX (item #7 in ROADMAP).

### #2 — Pairing wording cleanup
- `sdk-python/README.md`: removed the "M7 wires this; for now in
  dev, use client.set_pairing(...)" TODO-style language. The new
  Quickstart shows the V1 manual flow (user copies `user_id` +
  `pairing_key_hex` off the device confirmation screen, types
  them into the sender's backend) as the **canonical** V1 path,
  with a forward-pointer to the V1.5 DX roadmap item for the
  automated bootstrap exchange.

### #3 — Wildcard glob grammar documented
- `docs/integration-guide.md` §3.1 (NEW): documents that `*`
  matches exactly one host or path segment, never multiple, with
  the boundary rule (`[^./]*` in host, `[^/]*` in path) and
  examples of common mistakes (`/api/*` not matching `/api/v1/users`,
  `*.example.com` not matching `a.b.example.com`). Notes that the
  same matcher gates template action endpoints at publish time.
- This is the rule implemented by `sdk-plugin/src/network.ts:48-69`
  and `android/.../EndpointMatcher.kt`; the doc surfaces it.

### #5 — `platform.network.fetch` contract documented
- `docs/integration-guide.md` §3.2 (NEW): full contract — returns
  `Response` for any HTTP status; throws on `endpoint_not_declared`
  / `cleartext_in_release` / network failures; OkHttp default
  timeouts; cookies disabled at the bridge; no streaming bodies;
  no `AbortController`. Example code block showing success +
  failure handling.

### #6 — ROADMAP V1.5 Developer Experience section
- `docs/ROADMAP.md`: new V1.5 — Developer experience subsection
  with four items: automated pairing handshake (#6), full
  round-trip example plugin (#7), `npm create @syncler/plugin`
  scaffold (#8), validator polish backlog (#9). Renumbered V2/V3/V4
  items so the list is monotonic.

### Structural — §9 split
- `docs/integration-guide.md`: §9 renamed to "Common server
  errors" (only items the server returned 4xx/5xx for); new §9.1
  "Common pitfalls" lists the silent-failure modes a plugin
  author actually hits: unsigned placeholders, single-segment
  wildcard surprises, `card_key` ↔ `card_key_path` mismatch,
  template/script field mismatches, missing `registerPlugin()`,
  hostPreview fallbacks, action-POST body shape surprises.

## Files modified

```
M docs/integration-guide.md      (§3.1 wildcard, §3.2 fetch, §9 split + §9.1 new)
M docs/ROADMAP.md                 (V1.5 DX section + V2/V3/V4 renumbering)
M sdk-plugin/README.md            (rewrite minimal-plugin section)
M sdk-plugin/src/manifest.ts      (strict hex length validator)
M sdk-plugin/examples/minimal/manifest.json    (UNSIGNED placeholders)
M sdk-plugin/examples/minimal/src/plugin.ts    (UNSIGNED + explanation)
M sdk-plugin/test/manifest.test.ts             (new length tests + 64/128 fixtures)
M sdk-plugin/test/base-plugin.test.ts          (64/128 fixtures)
M sdk-plugin/test/network.test.ts              (64/128 fixtures)
M sdk-python/README.md            (V1 pairing flow as canonical, V1.5 forward-pointer)
A sdk-plugin/examples/minimal/README.md
A examples/README.md
```

## Buildability check

- `sdk-plugin`: `npx tsc --noEmit` ✅, `npm run build` ✅,
  `npm test` ✅ (29/29).
- `android`: `./gradlew :feature:inbox:compileDebugKotlin` ✅.
- Server modules untouched in this commit.

## What I need from each reviewer

1. **Confirm all 6 lottery-claude findings + the structural fix
   are addressed.** Cite file:line on both the doc side and the
   code side where applicable.
2. **Validator strictness check**: does the new
   `bundleHash`/`signature` length check have any false-positive
   risk? E.g. is there a path where the server stores hashes in a
   different encoding the SDK validator would reject?
3. **Placeholder string check**: I picked
   `UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE` as the loud
   form. Is there any chance this collides with a legit value or
   gets misinterpreted by anything downstream?
4. **Wildcard doc accuracy**: §3.1 claims `*` in host = `[^./]*`,
   `*` in path = `[^/]*`. Verify against
   `sdk-plugin/src/network.ts:48-69` AND
   `android/.../EndpointMatcher.kt`.
5. **Fetch contract accuracy**: §3.2 claims OkHttp default
   timeouts, `CookieJar.NO_COOKIES`, no AbortController. Verify
   against `android/.../NetworkBridge.kt` + the TS bridge runtime.
6. **ROADMAP renumbering**: V2 starts at 10 now (was 6). All
   internal cross-refs in the codebase that reference roadmap item
   numbers — do any need updating? (I scanned and didn't see any
   numbered cross-refs; confirm.)
7. **Anything new** the §9.1 pitfalls section should add that a
   plugin author would hit.

## Output

Per reviewer:
1. Per-finding: closed / partially closed / still open.
2. Verification: per-doc-claim, cite the code that matches or
   contradicts.
3. New concerns: anything Phase 4.1 introduced.
4. Overall: ready to commit / blockers / hold.

If both reviewers GREEN, Phase 4.1 commits as a single
"phase 4.1: external DX review fix-ups" commit.
