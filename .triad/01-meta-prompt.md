# Round 1 — Lead vote, protocol vote, initial gut take

You are being addressed as part of a **three-model triad** assembled by the user to collaborate on building an app. The three members are:

- **Claude** — Anthropic's Claude Code CLI, running inside the user's VSCode on Windows. Acting as the orchestrator (delivers messages between the three of you, runs tool calls). Also a full voting member, not a neutral chair.
- **Codex** — OpenAI's Codex CLI.
- **Gemini** — Google's Gemini CLI.

This message is being sent **identically and in parallel** to Codex and Gemini. Claude will cast its own vote separately. You are not seeing the other two members' responses yet; this is a blind first round.

## The user's protocol

The user wants the triad to:

1. **Decide together** who leads this phase and what critique protocol the other two follow. Voting options below.
2. Then either: (a) return a unified verdict on why the idea shouldn't be built, OR (b) fire questions at the user, non-stop, until the questions are exhausted or the user says "finish."
3. After Q&A, produce a plan.
4. Then build.
5. **Re-vote on lead and protocol before each phase.**
6. The user is firm that no phase proceeds if any one CLI isn't responding — all three voices must be present.

## What you've been given

The full idea brief is in `d:\Projects\syncler\.triad\01-brief.md`. Read it before voting. It was written by another Claude instance (claude.ai web chat) who has been talking with the user about this idea for ~10 turns. It is the most complete picture available. Note that brief includes the prior Claude's editorial opinions, explicitly flagged — treat those as one perspective, not as fact.

## What to vote on

### Vote 1 — Who should lead this phase (the critique-or-question phase)?
- `claude` — Anthropic's Claude (the orchestrator)
- `codex` — OpenAI's Codex
- `gemini` — Google's Gemini

Pick **one**. Justify in 2-3 sentences. The lead's job: drive the conversation, decide which questions get asked next, synthesize the triad's positions when they diverge.

### Vote 2 — What critique protocol should the other two follow?

Pick **one**:
- `solo-parallel` — Each non-lead independently produces a critique/question set; lead synthesizes.
- `joint` — Non-leads collaborate on a single unified critique/question set.
- `chained` — Non-lead A produces a critique; non-lead B critiques A's critique (meta-critique).
- `devils-advocate` — One non-lead steelmans the idea, the other attacks it.
- `other:<your-proposal>` — Propose your own, one sentence.

Justify in 2-3 sentences.

### Vote 3 — Path: question or reject?

The user offered two paths: (a) unified verdict that the idea shouldn't be built, or (b) fire questions until done. Which does your gut say, after reading the brief?

- `questions` — proceed to Q&A
- `reject` — the idea has a fatal flaw worth saying so now
- `questions-with-flagged-concerns` — Q&A but with a small list of specific reservations the user should hear up front

Justify in 2-3 sentences.

## Initial gut take (1 paragraph, max ~150 words)

What's your honest first read of the idea? Where do you think the prior Claude was right or wrong? What's the single most important thing the user hasn't been pressed on yet?

## Output format

Respond in this exact structure so Claude (the orchestrator) can parse and synthesize:

```
=== VOTES ===
lead: <claude|codex|gemini>
lead_reason: <2-3 sentences>

protocol: <solo-parallel|joint|chained|devils-advocate|other:...>
protocol_reason: <2-3 sentences>

path: <questions|reject|questions-with-flagged-concerns>
path_reason: <2-3 sentences>

=== GUT TAKE ===
<~150 words>

=== NOTES TO ORCHESTRATOR ===
<optional, anything Claude should know about how you want to be brought in for round 2>
```

Be honest, not diplomatic. The user explicitly wants real critique, not agreeable assistance. The prior Claude warned us specifically about flattery groupthink across three models — push against it.
