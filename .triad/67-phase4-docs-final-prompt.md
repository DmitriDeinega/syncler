# Consultation 67 — Phase 4 docs, final pass after Codex 66 blockers

Consultation 66 returned:
- **Gemini**: all-GREEN, READY TO COMMIT.
- **Codex**: HOLD with two concrete blockers + one optional cleanup.

The two Codex blockers and the optional cleanup are now resolved.
This consultation is a tight confirmation pass — please verify the
specific fixes below land cleanly and the docs are ready to commit.

## Codex 66 blockers fixed

### Blocker 1: missing code-fence close in `docs/integration-guide.md`
The Python publish_plugin example opened ` ```python ` at the start
of §5 but never closed before the `### 5.1 Native Template Renderer`
heading. That would have rendered §5.1 and §5.2 INSIDE the code
block. Closed the fence with a `\`\`\`` line between the `print(...)`
trailing comment and the §5.1 heading.

### Blocker 2: §3 TypeScript example used `'network'` string literal
`PluginManifest.declaredCapabilities` is typed `Capability[]` (an
enum). Runtime validation accepted the string, but a typed TS
project would not compile. Rewrote §3 to:
- `import { Capability, ... }` (the enum is already exported from
  `sdk-plugin/src/index.ts:24`).
- Set `declaredCapabilities: [Capability.NETWORK]`.
- Drop the explicit `template: undefined` / `cardKeyPath: undefined`
  lines from the example since both fields are now optional on the
  `PluginManifest` interface; the validator rejects them when they
  don't belong, so leaving them off entirely is the recommended
  shape for a script-mode event-card plugin.

### Optional cleanup: `manifest.ts` cardKeyPath comment
Codex 66 noted that the JSDoc on `cardKeyPath` said "rejected
otherwise" but the validator at the time didn't reject. The
validator now does reject `cardType === 'event'` plus a non-null
`cardKeyPath` (added by the user/linter post-66), so the comment
and the code are now consistent. No further change needed.

## What's NOT changed since consultation 66

- `docs/crypto-spec.md` — Codex 66 GREEN, no follow-up needed.
- `docs/ROADMAP.md` — Codex 66 GREEN, no follow-up needed.
- `sdk-python/syncler/crypto.py` — Codex 66 GREEN, helpers use
  `_canon_uuid` matching `client.py`.
- `sdk-plugin/src/manifest.ts` — Interface and validator unchanged
  except for the user/linter's added strict event-mode check at
  lines 164-166.

## Files at HEAD (uncommitted)

```
M docs/integration-guide.md   (code-fence + §3 TS example)
M docs/crypto-spec.md          (no change since 66)
M docs/ROADMAP.md              (no change since 66)
M sdk-plugin/src/manifest.ts   (no change since 66 by me)
M sdk-python/syncler/crypto.py (no change since 66)
```

## What I need from each reviewer

1. **Re-verify the two Codex 66 blockers are closed.**
   - Open `docs/integration-guide.md` and confirm there's now a
     `\`\`\`` close fence between the `print(...)` call and the
     `### 5.1` heading.
   - Open `docs/integration-guide.md §3` and confirm
     `declaredCapabilities: [Capability.NETWORK]` plus the
     `Capability` import.
2. **Re-verify §3 still type-checks if a plugin author copy/pastes
   the example into a TS project.** The `template` and
   `cardKeyPath` fields are now omitted from the example object,
   and the `PluginManifest` interface marks them optional (lines
   59-76 of `sdk-plugin/src/manifest.ts`).
3. **Re-confirm everything Codex 66 marked GREEN still holds**
   (crypto-spec, ROADMAP, Python helpers, manifest.ts interface).
   Nothing should have regressed.
4. **Anything else** the integration-guide rewrite touched
   inadvertently.

## Output

Per reviewer:
1. Per-file verdict: GREEN / YELLOW / RED.
2. Overall: ready to commit / blockers / hold.

If both reviewers GREEN, Phase 4 commits as a single
"phase 4: docs + roadmap publish" commit and the docs become
the public contract for the test plugin the user is building
in a separate project.
