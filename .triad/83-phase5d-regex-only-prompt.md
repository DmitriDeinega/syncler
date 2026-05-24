# Consultation 83 — Phase 5d action endpoint scheme (regex-only)

This is iteration 4 of the action endpoint scheme check. Previous
iterations:
- Consultation 80: hand-rolled string slicing → Codex caught HTTPS
  userinfo + IPv4 octet bugs.
- Consultation 81 fix: switched to `URL` (SDK) / `urlparse` (server).
  Codex caught WHATWG-normalizes-legacy-IPv4 vs urlparse-doesn't
  mismatch.
- Consultation 82 fix: validate raw host string, reject leading-zero
  octets. Codex caught WHATWG-treats-empty-userinfo-as-no-userinfo
  and WHATWG-normalizes-backslash-schemes — both more
  SDK/server divergence.

## Final approach: ditch the URL parser entirely

The parsers themselves are the source of divergence. Both sides
now validate the raw URL string against a strict regex matching
canonical authority syntax:

```
^(https?):\/\/([^\\@/?#:]+)(?::\d+)?(?:[/?#].*)?$
```

The host capture excludes `@` (no userinfo at all), `\` (no
backslash scheme), and other authority-terminating characters.

### SDK — `sdk-plugin/src/manifest.ts`

```ts
const ACTION_ENDPOINT_PATTERN =
  /^(https?):\/\/([^\\@/?#:]+)(?::\d+)?(?:[/?#].*)?$/i;

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
    r"^(https?):\/\/([^\\@/?#:]+)(?::\d+)?(?:[/?#].*)?$",
    re.IGNORECASE,
)

def _is_allowed_action_endpoint_scheme(url: str) -> bool:
    match = _ACTION_ENDPOINT_PATTERN.match(url)
    if not match: return False
    scheme = match.group(1).lower()
    host = match.group(2).lower()
    if scheme == "https": return True
    if host == "localhost": return True
    return _is_lan_private_ipv4(host)
```

`isLanPrivateIpv4` / `_is_lan_private_ipv4` (from consultation 81)
still enforces canonical 4-octet decimal IPv4 with no leading-zero
octets and all octets in 0..255.

## Regression test matrix (all on both sides)

| URL | Expected | Reason |
|---|---|---|
| `https://api.example.com/x` | accept | HTTPS always |
| `http://localhost:8001/x` | accept | localhost |
| `http://127.0.0.1/x` | accept | 127.x loopback |
| `http://10.0.0.1/x` | accept | 10.x LAN |
| `http://172.20.0.5/x` | accept | 172.16-31 LAN |
| `http://192.168.1.5/x` | accept | 192.168.x LAN |
| `http://10.255.255.255/x` | accept | max valid octet |
| `http://172.32.0.5/x` | reject | outside 172.16-31 |
| `http://api.example.com/x` | reject | cleartext public |
| `http://10.999.999.999/x` | reject | octet > 255 (80 RED) |
| `http://0177.0.0.1/x` | reject | leading zero / octal-looking (81 RED) |
| `http://127.1/x` | reject | non-canonical 3-octet (81 RED) |
| `http://010.0.0.1/x` | reject | leading zero (81 RED) |
| `http://user:pass@10.0.0.1/x` | reject | userinfo (80 RED) |
| `https://user:pass@host/x` | reject | userinfo (80 RED) |
| `http://@10.0.0.1/x` | reject | empty userinfo (82 RED) |
| `http://:@10.0.0.1/x` | reject | empty userinfo (82 RED) |
| `https://@api.example.com/x` | reject | empty userinfo (82 RED) |
| `https:\api.example.com/x` | reject | backslash scheme (82 RED) |
| `ftp://api.example.com/x` | reject | non-http(s) scheme |

## Test counts

- `sdk-plugin/`: 54/54 passing (5 test files, 30 in manifest.test.ts)
- `server/tests/test_phase5d_validators.py`: 13/13 passing

## What I need from each reviewer

**Codex**: this is your 4th review of this check. Are there
remaining quirks where WHATWG (SDK runtime) and Python (server)
would disagree, given the raw-regex approach? Specifically: IDN
punycode, percent-encoded host bytes, IPv6 brackets, control
characters in host, anything else.

**Gemini**: full pass on the regex approach. Is the regex's host
character class (`[^\\@/?#:]+`) tight enough to refuse foot-guns
but loose enough not to break legitimate hostnames (DNS labels can
contain `-` and `.`, both allowed; underscore is technically
illegal in DNS but accepted by browsers — should we reject it
explicitly?)?

## Output

Per reviewer:
1. Per-area: GREEN / YELLOW / RED.
2. Blockers if any.
3. Commit-readiness vote.

If both GREEN, commit Phase 5d and move to **Phase 5a-2.1**
(deferred from 5a-2: Android PairingRepository UX, FastAPI broker,
integration-guide automated-pairing walkthrough).
