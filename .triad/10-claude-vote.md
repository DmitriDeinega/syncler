# Round 10 — Claude's blind vote on build-phase practical questions

Cast in chat at the user's instruction, before reading Codex/Gemini responses.

## Q-build: who literally writes the files
**Vote: (a) Codex CLI authors directly.**

Reason: the protocol vote (Round 9) said "lead-builds-others-review" with Codex as lead. (a) is the literal implementation. (b) inserts me as a translator between Codex's intent and disk — friction for no gain since I can review the diff post-commit. Workspace-write to be scoped to subdirs Codex creates for V1 code (e.g., `server/`, `android/`, `sdk-plugin/`, `sdk-python/`), not the workspace root, so `.triad/` and user files stay protected.

## Q-git: initialize as git repo now
**Vote: (a) git init now.**

Reason: build phase produces many commits across milestones; rollback / bisect requires git. Initializing later after files exist is messier. Include `.triad/` in the repo (or .gitignore if user prefers design history separate) so context travels with the code.
