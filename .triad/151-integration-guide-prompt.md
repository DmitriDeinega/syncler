=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 151 — integration guide review

`docs/integration-guide.md` is the single document we hand
to other projects who want to integrate with Syncler.
After V3 + V4 closed, I extended it with sections covering
the live channel (V3 #14), live-card patches (V3 #16),
the V4 #18 guided lost-device flow, and V4 #19 per-plugin
preferences. The "what the host does for you" list at the
top was also updated.

This consultation is to make sure that document is
**self-sufficient, accurate, and polished** — the kind of
file the user said they'd want to share with another
project so that project can build a working integration
end-to-end without needing to read the codebase.

Current state at commit be15113 (most recent edit of the
guide).

## What I'm asking for

Read `docs/integration-guide.md` end-to-end (it's 868 lines
now, about 11 sections). Your job is to find:

1. **Accuracy bugs.** Anything that contradicts the
   shipped code or the related spec docs:
   - `docs/crypto-spec.md` for V2 envelope details
   - `docs/live-channel.md` for V3 #14
   - `docs/live-card-patch.md` for V3 #16
   - `docs/live-backplane.md` for V3 #17
   - `docs/lost-device-flow.md` for V4 #18
   - `docs/plugin-prefs.md` for V4 #19
   - the actual server / SDK / Android code
   Anything that would make a partner project's
   integration not work, or work differently from what we
   ship.

2. **Gaps for self-sufficiency.** A partner project should
   be able to read ONLY this file and ship an integration.
   Are there required steps that are mentioned in the spec
   docs but not in the guide? Common errors that integrators
   actually hit but aren't documented? Operational concerns
   (Redis dependency, dev posture) the integrator needs to
   know but might miss?

3. **Polish.** Inconsistent terminology, contradictions
   across sections, dead links, references to commits or
   triads that don't make sense to an external reader, copy
   that reads as internal jargon. The file should read like
   public-facing documentation, not internal commit context.

4. **Missing surfaces.** Anything shipped that an
   integrator might want but the guide doesn't cover.

## Specific concerns I want flagged

1. **Voice consistency.** Is the addition flowing with the
   existing V1-era prose or does it feel like a bolt-on?

2. **§12 live channel.** Is the wire-shape detail enough
   for an integrator who's NOT going to use the TypeScript
   SDK (e.g. a Python sender)? What's missing?

3. **§13 patches.** I describe the SDK shape + server
   contract + device catch-up. Does an integrator know
   WHEN to use patch_card vs upsert_card? The decision
   criteria should be more explicit.

4. **§14 prefs.** Integrator-side this is a "do nothing"
   feature. Does the document make that clear without
   sounding dismissive?

5. **§11 rewrite.** I removed the manual three-step
   recipe and pointed at the V4 #18 guided flow. Is the
   "your sender does nothing" claim accurate? Anything
   the integrator needs to know about the pairing
   rewrap path during rotation?

6. **Internal references.** I tried to scrub commit
   hashes / triad numbers from the guide (they belong in
   commit messages, not public docs). Any leakage left?

7. **Code samples**. Are the Python + TS samples
   syntactically right? Do they use APIs that exist?

Goal: a clear OK / NIT / FIX / DESIGN list so I can polish
the guide before shipping it as the single artifact a
partner project reads. Cap your response at ~600 words.
