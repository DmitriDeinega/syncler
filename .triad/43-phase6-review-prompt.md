# Review 43 — Phase 6: relative timestamps + delete + hide revoked devices

Shipped commit `M11.6` (head). User feedback after dogfooding the bottom-
nav APK from M11.5:

> 1) time shown as big ugly thing.
> 2) select to delete would be nice, also delete in the message itself.
> 3) in inbox group by types is nice too. if many types I want to see
>    specific one I collapse the others.
> 4) also why I have so many devices? in the settings tab?

This phase addresses (1), (4), and the second half of (2) (per-message
delete in detail). Multi-select for the list and the group-by-sender
toggle from (3) are explicitly deferred to Phase 7 — pressure-test
this commit on its own.

## What to pressure-test

### Time format

1. **`TimestampFormat.relative`** ([feature/inbox/TimestampFormat.kt](android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/TimestampFormat.kt)).
   Rules: `<60s` → `"just now"`; `<60m` → `"{n}m"`; same local day →
   `HH:mm`; yesterday → `"Yesterday"`; same year → `"MMM d"`; older →
   `"MMM d, yyyy"`. Unparseable ISO → raw string. Edge cases:
   - Future timestamp (sender clock skew, server normalization quirk):
     `delta.isNegative` is true, so we fall straight through to the
     same-local-day branch. Is that the right behavior, or should
     "soon" / "in 5m" show?
   - Crossing midnight while the user has the inbox open: `LocalDate.now()`
     re-evaluates on every recomposition, which fires on the 15s
     poll, so it'll flip from "23:55" to "Yesterday" within ~15s of
     the day boundary. Acceptable.
   - DST transitions: `ZoneId.systemDefault()` handles it correctly.
   - i18n: `Locale.ROOT` on the formatters means English month names
     ("May" not "Май"). Acceptable for V1; localized labels are V1.5.

2. **No unit test in this commit.** The formatter has clear deterministic
   behavior but isn't covered. Should I add a test class with the
   edge cases above before this lands, or is the production usage
   self-validating enough?

### Delete

3. **Schema addition is additive** — `EncryptedUserState.deletedMessages`
   defaults to `emptyList()`, blob version stays at SCHEMA_V2 (no
   bump). Pre-M11.6 blobs forward-migrate cleanly via `optJSONArray`
   returning null → `.orEmpty()`. Verify: a V2 device that doesn't
   know about `deleted_messages` would still parse the blob, drop
   the field on write-back, and we'd lose deletion state. (Same class
   of issue as M11.2/M11.4 with newer fields and older clients. We
   own all clients in V1 so this is observable-but-not-actively-bad.)
   Worth bumping to SCHEMA_V3 to make the version skew visible, or
   keep additive?

4. **Monotone semantics.** No undelete in V1 — `markDeleted` adds an
   entry; `StateMerger` does union-by-message_id with max(deleted_at).
   Once deleted, the entry can only be overwritten by a same-id
   entry with a later timestamp (which still keeps it deleted). Is
   the no-undelete commitment right for V1 (the alternative needs
   tombstones), or does the user want an "Undo delete" within a
   short window?

5. **Delete bypasses the server.** The /v1/messages/{id}/dismiss
   endpoint exists; we don't call it. Archive doesn't either. Both
   are pure-client filters via the M7 CAS blob. The server still
   has the bytes until expiry. Is that the right separation, or
   should Delete push to the server's dismiss too?

6. **Confirmation dialog.** Single tap on Delete → AlertDialog with
   "Delete / Cancel". Copy says "It'll disappear from the inbox and
   archive on all your devices. This can't be undone." Should there
   be an undo snackbar instead of (or in addition to) the dialog?
   Material 3 pattern is usually snackbar; the dialog here is a
   pragmatic V1 because we don't have an undo mechanism.

### Hide revoked devices

7. **Filter in UI only.** `SettingsScreen` filters `revokedAt == null`
   before rendering. Server still has the rows. Three concerns:
   - Re-revocation: the Revoke button is unconditional now (no
     `enabled = device.revokedAt == null` guard). Fine since we
     don't show revoked rows, but worth a glance.
   - "No active devices" empty state: not currently rendered if the
     filtered list is empty. Edge case — user signs in then revokes
     their only device. Minor.
   - Server-side dedupe by device public key would be the actual
     fix (prevent enrollment from creating a new row for the same
     key). That's V1.5 work — confirm V1 scope is acceptable.

### Tests

8. **No tests added in this commit.** The `StateMerger` change for
   `deletedMessages` mirrors the existing `archivedMessages` merge
   semantics that already have coverage, but the new field's
   round-trip-through-fromJson + delete-state union test would be
   reasonable defense. Worth adding before greenlight?

## Output

Standard format. If you'd hold for any specific fix, name it. The
next phase is multi-select + group-by-sender per the deferred list
from M11.6 — that's a substantially bigger UI build and gets its
own review cycle.
