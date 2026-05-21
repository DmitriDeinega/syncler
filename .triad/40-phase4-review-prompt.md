# Review 40 — Phase 4: bundle-by-hash retention + revocation classification

Shipped commit `M11.4` (head). Two correctness improvements you flagged
across reviews 35/38/39: bundle cached by SHA-256 hash so render history
survives plugin updates, and a classified `revocation_reason` enum on
the revoke endpoint so devices can render differentiated UX (silent /
security-alert / unavailable).

## What to pressure-test

### Bundle-by-hash retention

1. **In-memory cache, no disk persistence yet.** The cache is
   `Map<String, CachedBundle>` keyed by lowercase-hex SHA-256. Lives
   for the process lifetime. On app restart, the cache is empty and
   every previously-seen message re-fetches its bundle. Is the V1
   scope cut OK, or does this leave a real correctness gap? Specifically:
   if a sender publishes v1, sends msg1, then publishes v2, then the
   user restarts the app, then opens msg1 — the device fetches /latest
   (which returns v2's manifest), verifies + caches v2's bundle, and
   renders msg1 with v2's render(). That's the bug bundle-by-hash was
   supposed to fix — and we still hit it on restart.

2. **InboxItem.bundleHash is recorded but never used at render time.**
   The Phase 4 commit message says "UI wire-up to actually use it for
   the detail render path lands when V1.5 adds disk persistence." That
   means in V1 the field is dead weight. Should I either (a) wire it
   now so at least intra-session history is correct, or (b) defer the
   field entirely until disk persistence ships? Argument for (a):
   one-line change to look up by hash in the detail view. Argument
   for (b): half a feature is worse than none.

3. **Hash mismatch detection still relies on /latest's bundle_hash.**
   The fetch path verifies `SHA-256(downloaded) == latest.bundleHash`.
   If a sender's CDN serves a corrupted bundle, fetch fails. But if a
   sender's CDN serves an OLD bundle (replay attack vs the signed-URL
   pattern), the hash matches against /latest's hash and we'd accept
   it. The publish endpoint signs (canonical_manifest_bytes || bundle
   _hash) — that's pinned in the manifest_hash. Worth a closer look.

### Revocation classification

4. **Reason in canonical signed bytes** — when supplied, `reason` is
   part of the JSON the Ed25519 signature is computed over. Backwards
   compat: when omitted, the envelope shape is the old 2-field shape,
   so M8.1 senders that don't know about reason still produce valid
   signatures. Verify there's no shape ambiguity (e.g., a signer that
   includes `"reason": null` would produce a 3-field envelope and
   collide with a no-reason 2-field signer).

5. **Re-revoke promotes reason** ([services/plugins.py:144-155](server/app/services/plugins.py)).
   First revoke with `superseded` sets `revoked_at + reason=superseded`.
   Subsequent revoke from the same sender with `compromised` keeps
   `revoked_at` but updates `reason` to `compromised`. Is that the
   right idempotency model, or should we treat reason as immutable
   once set?

6. **Device discovery of revocation.** `/latest` still 404s for
   revoked plugins (filters `revoked_at IS NULL` via
   `get_latest_for_plugin`). The device sees revocation as a 404 and
   has no way to learn the reason. Phase 4 records the reason but
   doesn't propagate it. Acceptable V1 scope, or worth surfacing now
   via a small contract change (e.g., return revoked rows with a flag)?

7. **`unspecified` legacy default.** The schema validator treats null
   `reason` (legacy or revoke calls that omit it) as missing rather
   than coerced to `unspecified`. The doc says client code should
   interpret null as `unspecified` (most conservative). Is that
   division between server (records as null) and client
   (interprets null as compromised-class) safe, or should the server
   coerce explicitly on write?

### Server tests

8. New tests:
   - `test_revoke_records_reason_when_provided` — happy path including
     the idempotent reason-promote.
   - `test_revoke_rejects_invalid_reason` — Pydantic enum rejects
     unknown values as 400.

   Are these the right two? Anything in the revocation classification
   that you'd want tested that I missed?

### Don't relitigate

- The hostPreview contract (locked in review 36)
- The 3-tab navigation (greenlit in review 39 after fix-ups)
- Read state CAS sync (greenlit in review 38 after fix-ups)

## Output

Standard format. If the V1 scope is fine, say so explicitly so I can
move to Phase 5 (host-side metadata search). If a finding warrants
holding Phase 5 until fix-ups land, name it.
