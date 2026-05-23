# Consultation 47 — synced pairing + lost-device threat model

After working through consultation 46's "per-plugin connected devices"
idea (Idea 3) with the user, we concluded:

- **Idea 3 (server-side per-plugin device authorization matrix) is
  dropped.** It conflated routing with access control. Routing
  denial doesn't actually deny a lost device that already has the
  pairing keys, so it wasn't real device-level access control.

- **Replacing it with synced pairing** — the M7 encrypted user state
  blob (which already syncs read marks / archives / deletes) gets a
  new `pairedSenders` field. One QR scan on any of the user's
  devices propagates the pairing to all of them via the existing
  state CAS endpoint.

- **Threat model accepted:** a lost device has cached keys
  locally; the server can revoke its access (already works), but
  the cached pairing keys themselves can only be invalidated by
  rotating them. So the V1.5 user-facing story is:
  - **Immediate:** revoke the device on Settings → server stops
    delivering new state/inbox/FCM to it.
  - **Full forward-secrecy:** re-pair each sensitive sender
    manually on a trusted device, which rotates that sender's
    pairing key. Past cached data on the lost device is
    unrecoverable.
  - **V2 (deferred):** a guided "lost-device" flow that bundles
    server revocation + master-key rotation + per-sender re-pair
    walks into one operation.

User has approved the direction in principle. Before we write
code, pressure-test the V1.5 design and recommend the minimum
implementation. Two-pass: (a) what gets built, (b) what to look
out for.

## V1.5 proposed implementation

### Data model

`EncryptedUserState` (Kotlin, [android/core/storage/.../EncryptedUserState.kt](android/core/storage/src/main/kotlin/app/syncler/core/storage/EncryptedUserState.kt))
currently at `SCHEMA_V3`. Add:

```kotlin
data class PairedSenderEntry(
    val pairingId: String,        // stable per-pairing UUID
    val senderId: String,
    val senderName: String,
    val senderPublicKey: String,  // base64
    val fingerprint: String,
    val nameHash: String,         // base64
    val pairingKey: String,       // base64, 32-byte AES-256
    val firstPairedAt: String,    // ISO-8601 UTC
    val removedAt: String? = null, // tombstone — null = active
)

// added to EncryptedUserState:
val pairedSenders: List<PairedSenderEntry> = emptyList()
```

Bump `SCHEMA_CURRENT` to `SCHEMA_V4`. Forward-migration:
`SCHEMA_V0..V3 -> SCHEMA_CURRENT`.

### Merger semantics

`StateMerger.kt` — same pattern as deletedMessages. Per pairingId:

- `removedAt = null` means active.
- `removedAt != null` means tombstoned (the user revoked the pairing
  on some device).
- Merge picks the entry with the **later `removedAt`** if any side
  has tombstoned; otherwise picks the entry with the earlier
  `firstPairedAt` (oldest pairing wins on add conflict — should
  basically never happen since pairingId is fresh-UUID per pairing
  attempt).

### Local <-> synced reconciliation

`PairedSenderStore` ([android/core/storage/.../PairedSenderStore.kt](android/core/storage/src/main/kotlin/app/syncler/core/storage/PairedSenderStore.kt))
keeps its EncryptedSharedPreferences local cache. New role: it's a
projection of the synced `pairedSenders` field, NOT the source of
truth.

- `UserStateRepository.state` becomes the source of truth.
- `PairedSenderStore` observes that flow:
  - When a new active pairing appears in synced state and isn't
    in local prefs → write to local prefs.
  - When a pairing is tombstoned in synced state but still in
    local prefs → remove from local prefs (also re-erase the
    pairing key bytes).
- `PairedSenderStore.add(...)` (called from `PairingRepository`
  after a successful QR scan) writes BOTH to local prefs AND pushes
  through `UserStateRepository.mutateLocal { ... }` → state blob
  CAS push. Same `markDirtyAndPush` pattern as read marks.
- `PairedSenderStore.remove(...)` tombstones in the synced state
  AND wipes locally.

### Login flow on a new device

`AuthRepository.login` already:
1. Authenticates the password.
2. Unwraps the master key.
3. Enrolls the device.

After step 3 add:
4. Pull the state blob (already happens on InboxRepository's first
   refresh). The pull decrypts with the master key, finds the
   `pairedSenders` list, and the `PairedSenderStore` observer
   populates local prefs.

No extra step needed — the existing state-pull machinery does it.

### Revocation flow (immediate)

Already exists at [SettingsScreen.kt](android/app/src/main/kotlin/app/syncler/android/ui/SettingsScreen.kt)
+ [AuthRepository.revokeDevice](android/core/auth/src/main/kotlin/app/syncler/core/auth/AuthRepository.kt).
No changes required.

### Manual rotation flow (V1.5 cop-out)

For V1.5 we don't ship a one-tap rotate. The user-facing text in
the integration guide and in-app "device lost" copy will say:

> If you've lost a device:
> 1. Revoke it from Settings → Devices.
> 2. For each sender that handled sensitive data, re-pair it from
>    the Senders tab. This rotates the encryption key for that
>    sender — the lost device's cached key can no longer decrypt
>    new messages from it.

The pairing flow today supports re-pairing (it generates a new
pairingId + pairingKey each time), so this works without code
changes IF the PairedSenderStore correctly tombstones the old
pairing when the user revokes the old one before re-pairing.

## What to pressure-test

1. **Schema migration safety.** Existing devices today have local
   PairedSender records that are NOT in any state blob. When this
   change lands and the user opens the app:
   - Should we push the existing local pairings UP into the synced
     state on first run after the schema bump? Or wait for the
     next pairing event to trigger the push?
   - If we push them, what's the conflict story with a different
     device that pushes a different list at the same time?

2. **Tombstone GC.** Tombstones grow forever. When can a
   `removedAt` entry be GC'd from the blob? (Reference: how
   deletedMessages handles this today.)

3. **PairingRepository semantics.** Today it's a synchronous local
   operation. Now it has to push to server CAS. Failure modes:
   - User scans QR successfully but the state push fails (network
     blip, 409 conflict). What does the user see? Is the local
     pairing rolled back?
   - User scans QR offline. Does the pairing work locally and
     sync later? (Same model as read marks today — yes via
     `flushPendingPush`.)

4. **Sender behavior on re-pair.** Today the sender holds one
   pairing per user. If the user re-pairs (rotate), the sender
   needs to know to discard the old key and use the new one.
   - V1 the user pastes the new pairing_key_hex into the sender's
     CLI — manual. Fine for V1.5.
   - V1.5 documentation should be explicit: "re-pair = sender
     updates its pairing key for you."

5. **Threat model holes.** Are there cases I've missed where
   the current "revoke + manual re-pair" doesn't actually contain
   a lost device?
   - Specifically: what if the lost device is still online and
     pulling state when the user does the rotation? Does it get
     the tombstone + new pairing for the rotated sender? (It
     shouldn't — server should have revoked it by then. Verify.)

6. **Idea 3's residual mute-per-device.** Should the local
   "mute Lottery on this device" toggle ship in the same V1.5
   chunk as synced pairing, or land separately? It's small but
   the UX rationale only makes sense once pairing is synced.

7. **Anything else.** Where does this design fall over?

## Output

Short. For each pressure-test point, give a concrete answer
(do this / don't do this / open question). Then a one-paragraph
overall recommendation: ship this V1.5 plan as written, or
specific adjustments before code is touched. Both reviewers,
please answer independently.
