# Consultation 81 — Phase 5d fix-ups (post-80)

Consultation 80 voted: **Codex HOLD on item 2** (action endpoint
scheme); items 1, 3, 4, 5 all GREEN. Gemini did not run in 80
(quota exhaustion, now reset). This consultation reviews the
fix-ups and gives Gemini a first look at the entire 5d batch.

## What Codex 80 caught

### RED #2a — HTTPS userinfo accepted

> both helpers allow all `https://...` before checking userinfo,
> so `https://user:pass@host/...` passes the scheme helper despite
> the stated "reject `user:pass@host`" rule.

The previous hand-rolled string-slicing parser had:
```ts
if (url.startsWith('https://')) return true;     // accepts userinfo!
if (!url.startsWith('http://')) return false;
if (rest.includes('@')) return false;            // only HTTP checked
```

### RED #2b — IPv4 octet validation incomplete

> LAN IPv4 validation only checks first/second octets, so strings
> like `http://10.999.999.999/x` pass as "LAN private" even though
> they are not valid IPv4 literals.

The previous check parsed each octet with `Number()` but only
range-validated octets 0 and 1 (the network/sub-network IDs).
Octets 2 and 3 were free to be anything `\d{1,3}` matched.

## Fix-ups applied

### SDK — `sdk-plugin/src/manifest.ts`

Rewrote `isAllowedActionEndpointScheme` to use the WHATWG `URL`
parser instead of string slicing. New shape:

```ts
function isAllowedActionEndpointScheme(url: string): boolean {
  let parsed: URL;
  try { parsed = new URL(url); } catch { return false; }
  if (parsed.username !== '' || parsed.password !== '') return false;  // both schemes
  const host = parsed.hostname.toLowerCase();
  if (parsed.protocol === 'https:') return true;
  if (parsed.protocol !== 'http:') return false;
  if (host === 'localhost') return true;
  return isLanPrivateIpv4(host);
}

function isLanPrivateIpv4(host: string): boolean {
  const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host);
  if (!match || match.length < 5) return false;
  const a = Number(match[1]), b = Number(match[2]),
        c = Number(match[3]), d = Number(match[4]);
  for (const octet of [a, b, c, d]) {
    if (!Number.isInteger(octet) || octet < 0 || octet > 255) return false;
  }
  // ... range checks for 10.x / 172.16-31 / 192.168.x / 127.x
}
```

### Server — `server/app/schemas.py`

Mirror rewrite using `urllib.parse.urlparse`. `parsed.username` and
`parsed.password` rejected for BOTH schemes. New `_is_lan_private_ipv4`
helper validates every octet to 0..255.

### Regression tests

- `sdk-plugin/test/manifest.test.ts`: 3 new tests (HTTPS userinfo,
  HTTP userinfo, invalid IPv4 octets). 23/23 manifest tests pass.
- `server/tests/test_phase5d_validators.py`: 2 new tests
  (HTTPS userinfo, IPv4 octet range). 11/11 phase5d tests pass.

## All Phase 5d items, current state

| # | Item | Codex 80 | After fix |
|---|------|----------|-----------|
| 1 | `minPlatformVersion` 6-digit cap (SDK) | GREEN | GREEN |
| 2 | Action endpoint HTTPS / LAN-HTTP rule (SDK + server) | RED | **rewritten** |
| 3 | `template.fields` keys for layout (SDK) | GREEN | GREEN |
| 4 | `cardKeyPath` strict `$.field(.subfield)*` grammar (SDK + server) | GREEN | GREEN |
| 5 | Plugin id regex parity (server fix) | GREEN | GREEN |

Test counts:
- `sdk-plugin/`: 47/47 passing
- `server/tests/test_phase5d_validators.py`: 11/11 passing

## What I need from each reviewer

**Codex**: this is your re-review of item 2 after the URL-parser
rewrite. Are the new helpers correct? Userinfo rejected for both
schemes? Every IPv4 octet range-checked? Any new bug introduced?

**Gemini**: your first look at the whole Phase 5d batch (you were
quota-blocked for 80). Per-item GREEN/YELLOW/RED, especially:

- SDK `semverPattern` cap — anything weird about leading-zero
  asymmetry (SDK stricter than server)?
- Action endpoint scheme rule — LAN ranges complete? URL parser
  handles all the edge cases (URL with port, with fragment, with
  query, IPv6 literal in brackets)?
- `template.fields` key set — mirror correct, lockstep risk
  documented (both sides referenced)?
- `cardKeyPath` regex — server already had it for field paths;
  applying it consistently here is right?
- Plugin id regex parity — byte-for-byte SDK ↔ server now?

## Output

Per reviewer:
1. Per-item: GREEN / YELLOW / RED.
2. Blockers if any.
3. Commit-readiness vote.

If both GREEN, commit Phase 5d and move to Phase 5a-2.1 (Android
PairingRepository UX + FastAPI broker app + integration-guide
automated-pairing walkthrough — the items deferred from 5a-2 that
must land before 5e closes the V1.5 DX track).
