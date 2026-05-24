# Consultation 85 — Phase 5d action endpoint scheme (cycle 6)

**Protocol reminder — REVIEW ONLY.** Gemini in consultation 83 self-
committed against the protocol; the user soft-reset the repo. Your
job is verdicts only — no `git commit` / `git push` / `git add`.

## Iteration history

| Consult | What was caught |
|---|---|
| 80 | HTTPS userinfo; IPv4 octet range |
| 81 | WHATWG IPv4 normalization vs urlparse |
| 82 | WHATWG empty userinfo + backslash scheme |
| 83 | Python `re.match` trailing-`\n`; broad host class |
| 84 | Python `re.IGNORECASE` Unicode case folding; Unicode `\d`; Python `.` matching `\r`; IPv6 brackets |
| **85** | this consultation |

## Fixes since 84

### Scheme is now lowercase-only (behavior change, intentional)

`re.IGNORECASE | re.ASCII` does NOT restrict character-class case
folding — `re.ASCII` only affects `\w`, `\d`, `\s`, `\b` shorthands.
With `re.IGNORECASE`, `K` (U+212A) still folds into `[A-Z]` and `ſ`
(U+017F) folds into `s`. The only clean fix is to drop case-insensitive
matching entirely and require lowercase scheme + host:

```
^(https?):\/\/([a-z0-9._-]+)(?::[0-9]+)?(?:[/?#][\x21-\x7e]*)?$
```

Both `HTTPS://...` and `Https://...` will now be rejected. Plugin
manifests are by convention lowercase, and the trading-bot example
+ the generated scaffold already use lowercase. **Confirm this is
acceptable, or flag if any tool/example wrote uppercase schemes.**

### Path/query/fragment restricted to printable ASCII

`.*` replaced with `[\x21-\x7e]*` on both sides. This rejects
whitespace, `\r`, `\n`, control chars in the path — Codex 84 caught
Python `.` matching `\r` while JS doesn't.

### Port digits explicit ASCII

`\d+` replaced with `[0-9]+` on both sides. Codex 84 caught Python
`\d` matching `١٢` (Arabic-Indic digits) while JS doesn't.

### IPv6 brackets — V1 OUT OF SCOPE

`https://[::1]/x` is rejected (host class doesn't allow `[`). The
agreement and existing tests don't reference IPv6. Documented in
the doc-strings. **Confirm IPv6 is V1-deferred, or flag if any
intended use case requires it.**

## Final regex (both sides, identical)

```
^(https?):\/\/([a-z0-9._-]+)(?::[0-9]+)?(?:[/?#][\x21-\x7e]*)?$
```

Python uses `re.fullmatch` (no `$` newline footgun). No flags.
JS uses `^...$` anchors. No `/i`. Both reject all prior-cycle RED
test vectors.

## Test counts

- `sdk-plugin/`: 59/59 passing (35 in manifest.test.ts)
- `server/tests/test_phase5d_validators.py`: 18/18 passing

## What I need

Per reviewer:
1. Per-area: GREEN/YELLOW/RED.
2. Confirm: lowercase-only scheme is acceptable (no behavior gap
   you can spot).
3. Confirm: IPv6 brackets V1-deferred is acceptable.
4. Anything still divergent between SDK and server regex semantics?
5. Commit-readiness vote.

If both GREEN, orchestrator commits cleanly.
