# Consultation 82 — Phase 5d IPv4 parity fix (post-81)

Consultation 81 voted: **Gemini GREEN, Codex HOLD on a new bug**.
This consultation reviews the fix.

## What Codex 81 caught

> The SDK WHATWG `URL` parser normalizes legacy IPv4 forms before
> `isLanPrivateIpv4`, while Python `urlparse` does not. That creates
> SDK/server mismatch:
> - SDK accepts `http://0177.0.0.1/x` and `http://127.1/x` as
>   loopback after normalization.
> - Server rejects both.
> - Server accepts `http://010.0.0.1/x` as `10.x`; SDK normalizes
>   it to `8.0.0.1` and rejects.

Real bug. WHATWG `URL.hostname` normalizes:
- `0177.0.0.1` → `127.0.0.1` (octal 0177 = decimal 127)
- `127.1` → `127.0.0.1` (compressed form)
- `010.0.0.1` → `8.0.0.1` (octal 010 = decimal 8)

Python `urllib.parse.urlparse` does no normalization at all.

## Fix-up

Both sides now require **canonical 4-octet decimal IPv4** with no
leading-zero octets. WHATWG's normalization quirks become moot:

### SDK — `sdk-plugin/src/manifest.ts`

`isAllowedActionEndpointScheme` still uses `new URL(...)` for
scheme + userinfo extraction (those are robust under WHATWG), but
re-extracts the host from the **raw URL string** via a small regex:

```ts
function extractRawHostLowercase(url: string): string | undefined {
  // scheme://[user[:pass]@]<host>[:port][/...]
  // Stops the host capture at the first :, /, ?, or #.
  const match = /^https?:\/\/(?:[^@/?#]*@)?([^:/?#]+)/i.exec(url);
  if (!match) return undefined;
  const host = match[1];
  return host === undefined ? undefined : host.toLowerCase();
}
```

`isLanPrivateIpv4` now additionally rejects leading-zero octets
(`010`, `0177`) and (still) rejects out-of-range octets.

### Server — `server/app/schemas.py`

`_is_allowed_action_endpoint_scheme` keeps using `urlparse` (no
normalization, so the raw host is `parsed.hostname` directly).
`_is_lan_private_ipv4` adds the same leading-zero rejection.

The regex `^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$` naturally
rejects compressed forms (`127.1`) and IPv6 brackets, so those
need no extra handling beyond what we already had.

### Regression tests

`sdk-plugin/test/manifest.test.ts` (3 new):
- `0177.0.0.1` (octal-looking) — rejected
- `127.1` (compressed) — rejected
- `010.0.0.1` (leading zero) — rejected
26/26 manifest tests passing; 50/50 SDK suite.

`server/tests/test_phase5d_validators.py` (1 new test bundling all
three forms): `0177.0.0.1`, `127.1`, `010.0.0.1` all rejected.
`127.0.0.1` still accepted (sanity). 12/12 phase5d server tests
passing.

## Parity sanity check

| Endpoint URL | SDK | Server | Agreed? |
|---|---|---|---|
| `http://localhost/x` | accept | accept | ✓ |
| `http://127.0.0.1/x` | accept | accept | ✓ |
| `http://10.0.0.1/x` | accept | accept | ✓ |
| `http://192.168.1.5/x` | accept | accept | ✓ |
| `http://172.20.0.5/x` | accept | accept | ✓ |
| `http://0177.0.0.1/x` | reject | reject | ✓ |
| `http://127.1/x` | reject | reject | ✓ |
| `http://010.0.0.1/x` | reject | reject | ✓ |
| `http://10.999.999.999/x` | reject | reject | ✓ |
| `http://api.example.com/x` | reject | reject | ✓ |
| `https://user:pass@api.example.com/x` | reject | reject | ✓ |
| `https://api.example.com/x` | accept | accept | ✓ |

## What I need from each reviewer

**Codex**: re-review of the IPv4 parity fix. Are SDK + server
canonical IPv4 rules now byte-for-byte equivalent? Any other
WHATWG / urlparse quirk left untreated (e.g., trailing dot
`127.0.0.1.`, IDN punycode for non-IP hosts, percent-encoded
hostnames)?

**Gemini**: this is the 3rd revision of the action-endpoint
scheme check. Confirm: (a) leading-zero rejection mirrors the
agreement's "HTTPS-in-release, HTTP-LAN-in-debug" intent
(canonical IPv4 only is a strict superset of safe), (b) tests
cover the parity cases, (c) no regression on items 1, 3, 4, 5.

## Output

Per reviewer:
1. Per-item: GREEN / YELLOW / RED.
2. Blockers if any.
3. Commit-readiness vote.

If both GREEN, commit Phase 5d and move to **Phase 5a-2.1**
(deferred items: Android PairingRepository UX, FastAPI broker
under `syncler[broker]` extra, integration-guide automated-pairing
walkthrough).
