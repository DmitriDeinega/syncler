# Consultation 45 — M11 alignment audit + integration-guide rewrite

Two asks in one pass. They share context.

## Ask 1 — alignment audit

Review consultation 35 (UX direction), consultation 36 (hostPreview
contract), and every phase review (37–44). Then walk the M11.0
through M11.7 commit ladder and confirm each delivered piece matches
the original agreement, OR call out where it drifted.

The relevant commits:

```
M11    base — lottery dogfood end-to-end (the pre-UX work)
M11.1  hostPreview contract + inbox preview/detail split  + review 37
M11.2  read state + M7 CAS cross-device sync              + review 38
M11.3  bottom nav + date buckets + archive                + review 39
M11.4  bundle-by-hash + revocation classification         + review 40
M11.5  host-side metadata search                          + review 41
M11.6  relative timestamps + delete + hide revoked devs   + review 43
M11.7  multi-select + group-by-sender                     + review 44
```

The original UX direction came out of consultation 35 with this
agreed shape:

- inbox-first app that hosts micro-apps in detail surfaces
- preview/detail master/detail pattern; native host rows, plugin
  owns detail render
- read state local-first, synced via M7 CAS blob
- date buckets, no plugin grouping by default in V1 (consultation
  35 specifically argued against plugin grouping as primary nav —
  later phases added it as an OPTIONAL toggle)
- host-owned metadata search, plugin-scoped content search V1.5
- bottom nav (Inbox / Senders / Settings)
- archive vs delete vs dismiss as distinct concepts
- bundle-by-hash retention so render history survives plugin updates
- revocation classification (superseded / compromised / sender_disabled)
- 4 V1.5 deferrals: plugin dashboards, plugin-scoped search,
  cross-plugin search aggregation, plugin-owned chrome

Specifically pressure-test:

1. **Group-by-sender ended up shipped (M11.7) even though consultation
   35 argued against it.** Reconcile: did we drift, or was the
   "user requested it as a toggle" exception acceptable?

2. **Bundle-by-hash retention.** Consultation 35 said old messages
   must render against the bundle they were validated for. M11.4
   added bundle-by-hash caching but acknowledged in-memory only;
   M11.4 fix-ups switched to `/by-id` lookup. Is the V1 state
   (in-memory cache, no disk persistence, restart loses the cache
   but `/by-id` re-resolves to the original row) compatible with
   the original spec, or did we underdeliver?

3. **`hostPreview` schema vs consultation 36.** Final contract has
   title (required) + subtitle + summary + searchText. No priority,
   category, timestamp, or accessibilityLabel — all explicitly cut
   in consultation 36. Confirm we shipped exactly that contract,
   not a drift.

4. **Read-mark trigger.** Consultations 35/38 said "open detail
   view only, NOT scroll-into-view." M11.2 wired it that way.
   Confirm.

5. **Archive durability.** M11.3 archive kdoc was rewritten to
   honestly say "hide-from-active while server still has the
   message" rather than the original consultation-35 promise of
   "keep past server expiry." M11.4 onwards did NOT add local body
   storage. Is the V1 framing acceptable, or did the original
   spec really require local persistence?

6. **Delete vs dismiss vs archive.** Three distinct concepts in
   consultation 35. We shipped:
   - Archive (local + synced, hide from inbox + visible in archive)
   - Delete (local + synced, hide from inbox AND archive)
   - Dismiss (server endpoint exists, NOT wired from client UI)
   Did we honor the trinary?

7. **The four V1.5 deferrals** named in consultation 35 — were
   they all preserved (i.e. we didn't quietly ship one), or were
   any of them partially built and now constitute "half a feature"?

8. **Any commits whose diff doesn't match what their review
   asked for.** Sanity check that each "address review N" commit
   actually contains the fix it claims.

Output: green/yellow/red per phase against its original consultation
intent, with one-line justifications. Where it's yellow or red,
suggest the minimum delta to make it green.

## Ask 2 — integration-guide rewrite

I started a rewrite of `docs/integration-guide.md` to give to
lottery-Claude (the sender-side dev). The user's instruction was:

> the md shouldn't be saying "we changed this to that" it should be
> as currently describe the thing without before and after

Current state of the file: HEAD checkout of `docs/integration-
guide.md`. I've added a "What the host does for you" subsection to
§1 that lists native row rendering / read sync / date buckets /
group toggle / search / archive / delete / multi-select / bottom
nav / revocation UX, all framed as current behavior.

What I haven't done yet:

- A new **§5.5 Revoking a plugin version** describing the
  `client.revoke_plugin(plugin_row_id, reason=...)` method I just
  added to sdk-python, the four reason enums, and the host's UX
  per reason.
- A new **§10 Versioning + history** describing the bundle-by-hash
  guarantee (so senders know they can ship a v2 without breaking
  v1 messages already in users' inboxes).
- Scrub of any remaining "now" / "is no longer" / "we changed"
  phrasing that frames things as diffs from a previous state.

Review the file as it stands AND the planned additions above.
Identify:

- Any current-state claim that's actually wrong (mismatched against
  the M11.x code).
- Any sender-facing API or behavior that's NOT documented but
  should be.
- Any wording that smuggles changelog framing.
- Any field cap, error message, or example payload that doesn't
  match the actual implementation.

Output the specific delta you'd apply (file edits, not free-form
prose) so the user can hand the result to lottery-Claude with
confidence.
