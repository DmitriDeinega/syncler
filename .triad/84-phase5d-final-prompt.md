# Consultation 84 — Phase 5d action endpoint scheme (final)

**Protocol reminder — REVIEW ONLY**. Your job in this consultation
is to produce a verdict (per-area GREEN/YELLOW/RED + commit-readiness
vote). Do NOT run `git commit` / `git push` / `git add` — Gemini in
consultation 83 self-committed and the user had to soft-reset the
repo. Reviewers analyze; the orchestrator (the human + me) commit.

## Iteration history

| Consult | Approach | Verdict | What was caught |
|---|---|---|---|
| 80 | Hand-rolled string slicing | RED (Codex) | HTTPS userinfo accepted; IPv4 octets not range-checked |
| 81 | URL/urlparse + raw host regex | HOLD (Codex) | WHATWG normalizes legacy IPv4 (`0177.0.0.1`, `127.1`, `010.0.0.1`); urlparse doesn't |
| 82 | URL parsing + canonical IPv4 + leading-zero reject | RED (Codex) | WHATWG empty userinfo (`http://@host/x`); WHATWG backslash scheme normalization |
| 83 | Regex-only on both sides | RED (Codex) | Python `re.match` with `$` accepts trailing `\n`; broad host class accepts tabs/IDN/percent-encoded |
| **84** | Regex-only + `re.fullmatch` + tight host class | — (this consult) | — |

## Current implementation

### SDK — `sdk-plugin/src/manifest.ts`

```ts
// Host: ASCII alnum + . - _ only. JS regex `$` is already end-of-string.
const ACTION_ENDPOINT_PATTERN =
  /^(https?):\/\/([A-Za-z0-9._-]+)(?::\d+)?(?:[/?#].*)?$/i;

function isAllowedActionEndpointScheme(url: string): boolean {
  const match = ACTION_ENDPOINT_PATTERN.exec(url);
  if (!match) return false;
  const scheme = match[1]?.toLowerCase();
  const host = match[2]?.toLowerCase();
  if (scheme === undefined || host === undefined) return false;
  if (scheme === 'https') return true;
  if (host === 'localhost') return true;
  return isLanPrivateIpv4(host);
}
```

### Server — `server/app/schemas.py`

```python
_ACTION_ENDPOINT_PATTERN = re.compile(
    r"(https?):\/\/([A-Za-z0-9._-]+)(?::\d+)?(?:[/?#].*)?",
    re.IGNORECASE,
)

def _is_allowed_action_endpoint_scheme(url: str) -> bool:
    # `fullmatch` (not `match`) so Python's `$` semantics line up
    # with JS regex end-of-string — Codex 83 RED.
    match = _ACTION_ENDPOINT_PATTERN.fullmatch(url)
    if not match: return False
    scheme = match.group(1).lower()
    host = match.group(2).lower()
    if scheme == "https": return True
    if host == "localhost": return True
    return _is_lan_private_ipv4(host)
```

`isLanPrivateIpv4` / `_is_lan_private_ipv4` (unchanged from 81):
canonical 4-octet decimal, no leading-zero octets, all octets 0..255.

## Test matrix — every prior-cycle RED + new ones (all both sides)

| URL | Expected | Why |
|---|---|---|
| `https://api.example.com/x` | accept | HTTPS always |
| `http://localhost:8001/x` | accept | localhost |
| `http://127.0.0.1/x` | accept | 127.x loopback |
| `http://10.0.0.1/x` | accept | 10.x LAN |
| `http://172.20.0.5/x` | accept | 172.16-31 LAN |
| `http://192.168.1.5/x` | accept | 192.168.x LAN |
| `http://172.32.0.5/x` | reject | outside 172.16-31 |
| `http://api.example.com/x` | reject | cleartext public |
| `http://10.999.999.999/x` | reject | octet > 255 (80) |
| `http://0177.0.0.1/x` | reject | leading zero (81) |
| `http://127.1/x` | reject | non-canonical 3-octet (81) |
| `http://010.0.0.1/x` | reject | leading zero (81) |
| `http://user:pass@10.0.0.1/x` | reject | userinfo (80) |
| `http://@10.0.0.1/x` | reject | empty userinfo (82) |
| `https://@api.example.com/x` | reject | empty userinfo (82) |
| `https:\api.example.com/x` | reject | backslash scheme (82) |
| `https://api.example.com/x\n` | reject | trailing newline (83) |
| `https://api example.com/x` | reject | space in host (83) |
| `https://api\texample.com/x` | reject | tab in host (83) |
| `https://münich.example/x` | reject | raw IDN (83) |
| `https://%31%32%37.0.0.1/x` | reject | percent-encoded host bytes (83) |
| `https://xn--mnich-kva.example/x` | accept | punycoded IDN (ASCII-only) |
| `ftp://api.example.com/x` | reject | non-http(s) scheme |

## Test counts (local)

- `sdk-plugin/`: 56/56 passing (32 in manifest.test.ts)
- `server/tests/test_phase5d_validators.py`: 15/15 passing (direct
  Python runner; conftest blocks pytest because it needs Postgres
  for an autouse fixture — these tests are schema-level only)

## Other Phase 5d items (status unchanged from 81)

| # | Item | Status |
|---|------|--------|
| 1 | `minPlatformVersion` 6-digit cap (SDK) | GREEN |
| 3 | `template.fields` keys for layout (SDK) | GREEN |
| 4 | `cardKeyPath` strict `$.field(.subfield)*` grammar (SDK + server) | GREEN |
| 5 | Plugin id regex parity (server fix) | GREEN |

## What I need

Per reviewer:
1. **Per-item verdict** GREEN / YELLOW / RED on the action endpoint
   check. Are there any remaining SDK/server divergence cases? Any
   over-restrictive cases (legitimate hostnames that now get
   rejected)?
2. Anything new (security, footgun, doc accuracy).
3. Commit-readiness vote.

If both GREEN, the orchestrator commits cleanly (Phase 5d files
only; not the cosmetic linter-touched sdk-plugin internals that
were swept into the rogue 83 commit).
