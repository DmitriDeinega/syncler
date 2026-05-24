# Consultation 80 — Phase 5d review (validator polish batch)

Phase 5d — validator polish batch — is implemented per the agreement
at `.triad/70-phase5-agreement.md` lines 417-431. Single review pass
(vs 3-cycle for substantive design) since the agreement locked the
exact 5 items.

## The 5 items + landing state

### 1. `minPlatformVersion` 6-digit numeric component cap (SDK-only)
- **Before**: SDK `semverPattern` accepted `1.0.1234567` (arbitrary
  length). Server's `_SEMVER` in `services/plugins.py` already caps at
  6 digits per component.
- **After**: SDK pattern now `(0|[1-9]\d{0,5}).(0|[1-9]\d{0,5}).(0|[1-9]\d{0,5})…`
  in [manifest.ts](D:/Projects/syncler/sdk-plugin/src/manifest.ts:94).
  Same component cap as the server. Note SDK is stricter than server
  on leading-zero rule (`(0|[1-9]\d{0,5})` vs server's `\d{1,6}`) —
  acceptable: SDK rejecting first is fine, server still accepts.

### 2. Action endpoint HTTPS-in-release / HTTP-LAN-in-debug (both sides)
- **Before**: Neither SDK nor server validated `template.actions[].endpoint`
  scheme. A plugin could publish `http://attacker.example.com/ack`.
- **After**:
  - SDK: `isAllowedActionEndpointScheme(url)` in [manifest.ts](D:/Projects/syncler/sdk-plugin/src/manifest.ts:415).
    Allow HTTPS; allow HTTP only for `localhost`, `127.x.x.x`, `10.x.x.x`,
    `172.16-31.x.x`, `192.168.x.x`. Reject `user:pass@host`.
  - Server: `_is_allowed_action_endpoint_scheme` in [schemas.py](D:/Projects/syncler/server/app/schemas.py:475)
    mirrors the SDK rule and fires inside `validate_renderer_template_pairing`.
- Android release builds already reject cleartext at the network bridge
  (NSC); this just catches it earlier at publish time.

### 3. `template.fields` keys restricted to layout's allowed set (SDK-only)
- **Before**: Server enforced `_LAYOUT_REQUIRED_FIELDS` ∪
  `_LAYOUT_OPTIONAL_FIELDS` and 422'd. SDK accepted any key.
- **After**: SDK mirror of the server's allowed-key sets in
  [manifest.ts](D:/Projects/syncler/sdk-plugin/src/manifest.ts:99).
  `standard_card`: required `{title}`, optional `{subtitle, body}`.
  `validateTemplateBlock` enforces.

### 4. `cardKeyPath` strict `$.field(.subfield)*` grammar (both sides)
- **Before**: Both SDK and server's services/plugins.py used
  `startsWith("$")` only. So `$.items[0].id` passed publishing.
- **After**:
  - SDK: `jsonPathPattern` mirrors server's existing `_JSONPATH_REGEX`.
    Applied to `cardKeyPath` AND every `template.fields.<k>.path`.
  - Server: new `validate_card_key_path` field validator in [schemas.py](D:/Projects/syncler/server/app/schemas.py:625)
    + same regex applied in services/plugins.py for defense-in-depth.

### 5. Plugin id regex parity check (server fix)
- **Before**: SDK enforced `^[a-zA-Z][a-zA-Z0-9-]*(\.[a-zA-Z][a-zA-Z0-9-]*)+$`.
  Server only had `Field(min_length=1, max_length=200)` — no character
  class check.
- **After**: New `_PLUGIN_IDENTIFIER_REGEX` in [schemas.py](D:/Projects/syncler/server/app/schemas.py:560)
  matches the SDK exactly. `validate_plugin_identifier` field
  validator rejects non-reverse-DNS strings.

## Files touched

- `sdk-plugin/src/manifest.ts`:
  - Capped `semverPattern` components to 6 digits.
  - New `jsonPathPattern`, `layoutRequiredFields`, `layoutOptionalFields`.
  - Replaced `cardKeyPath` startsWith check with regex match.
  - New `validateTemplateBlock(...)` covering layout, allowed keys,
    required keys, action ids unique, action endpoint scheme + glob
    match.
  - Helpers: `isAllowedActionEndpointScheme`, `endpointMatchesAnyGlob`,
    `endpointPatternToRegExp` (copy of network.ts's matcher).
- `sdk-plugin/test/manifest.test.ts`: 11 new tests under
  "Phase 5d polish" block. 44/44 SDK tests passing.
- `server/app/schemas.py`:
  - `_PLUGIN_IDENTIFIER_REGEX`, `_is_allowed_action_endpoint_scheme`,
    `_HOST_PORT_PATTERN`.
  - New `validate_plugin_identifier` + `validate_card_key_path` field
    validators on `PluginPublishRequest`.
  - `validate_renderer_template_pairing` now checks scheme before glob.
- `server/app/services/plugins.py`: defense-in-depth `_JSONPATH_REGEX_SERVICE`
  applied to field path + card_key_path checks.
- `server/tests/test_phase5d_validators.py`: 9 unit tests, all
  passing (`./.venv/Scripts/python.exe` direct, since conftest needs
  a running Postgres — the tests themselves are schema-level only).

## Test counts

- `sdk-plugin/`: 44/44 passing (5 test files).
- `server/tests/test_phase5d_validators.py`: 9/9 passing (direct
  Python runner; conftest blocks pytest because the autouse fixture
  needs Postgres — no DB-touching code in the file).

## What I need from each reviewer

1. **Per-item verdict** on:
   - SDK `semverPattern` cap — anything weird about leading-zero
     asymmetry (SDK stricter than server)?
   - Action endpoint scheme rule — LAN ranges complete? Codex
     consultation 75 RED #1 fixed a similar `startswith`-prefix
     localhost flaw on broker URLs; same mistake reintroduced here?
   - Template `fields` key set — mirror correct, lockstep risk
     documented (both sides referenced)?
   - `cardKeyPath` regex — server already had it for field paths;
     applying it consistently here is right?
   - Plugin id regex parity — byte-for-byte SDK ↔ server now?
2. **Anything new** (security, footgun, doc accuracy).
3. **Overall**: ready to commit / specific blockers / hold.

## Output

Per reviewer:
1. Per-item: GREEN / YELLOW / RED.
2. Blockers if any.
3. Commit-readiness vote.

If both GREEN, commit Phase 5d and move to Phase 5e (docs +
roadmap closeout — the last phase in the V1.5 DX track).
