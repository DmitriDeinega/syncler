# Consultation 49 — second pass, resolving disagreements

Consultation 48 hit strong convergence except on two points where you
disagreed. Both reviewers' arguments are below. Either rebut the other
side's argument or concede with a specific safeguard. The goal here is
full agreement, not victory — if both positions have merit, name the
hybrid that captures both.

There's also a small set of tie-broken decisions to ratify (or reject).

## Disagreement 1 — Migrating existing local pairings into the synced state

### The setup

When the synced-pairing schema lands, every existing install has local
`PairedSenderStore` entries that were created pre-sync. These need to
end up in the M7 state blob somehow.

### Gemini's position

> **Don't push.** Wait for a new explicit pairing event before adding
> anything to the synced state. Risk if we push: user intentionally
> deleted a pairing on Device B pre-sync; Device A still has it
> locally; pushing local-up resurrects the deleted pairing.

### Codex's position (both reviewers aligned)

> **Push on first unlocked launch.** Without migration, new devices
> the user logs into post-sync have no pairings until they re-scan
> every QR — terrible UX. The resurrection risk Gemini names is
> real but bounded: it only affects pairings that existed pre-sync
> and were intentionally removed on one device but not another.
> One-time concern, mitigated by a migration flag.

### Questions

1. Gemini — given Codex's "new devices have nothing without
   migration" argument, do you want to refine your position to a
   hybrid (e.g., push but with a user-visible post-migration notice
   to review active pairings)? Or do you maintain "don't push"?
2. Codex — given the resurrection risk Gemini flagged, what's the
   smallest safeguard? A one-time prompt? A "needs-review" flag on
   migrated pairings that the user has to acknowledge? Or is the
   bounded one-time risk acceptable without a safeguard?
3. Both — propose the minimum-correct implementation if you converge.

## Disagreement 2 — Should "mute on this device" sync across devices?

### The setup

The host-owned settings sheet has a "Mute this sender" toggle.
Question: does this preference live in synced state, or stay
device-local?

### Gemini's position

> **Sync via M7 state blob.** If a sender is spamming and the user
> mutes it on their phone, they expect their tablet to also be
> muted. Local-only violates the "one unified state" principle of
> the app.

### Codex's position (Reviewer A)

> **Local only.** Mute is a per-device preference (the bedside-tablet
> case: "I want Lottery on my phone but not pinging my bedside
> tablet at 11pm"). Revoke is the cross-device, security-significant
> action. Different domains. Don't conflate.

### Questions

1. Gemini — given the bedside-tablet use case, do you want a
   per-device override on top of synced default? Or is sync still
   the right primary?
2. Codex — given the spamming-sender case (mute should follow the
   user, not the device), can you accept a synced primary with an
   optional local override? Or maintain local-only?
3. Both — is there a clean middle-ground? E.g., two separate
   settings: "mute everywhere" (synced) and "mute on this device"
   (local)? Or is that over-engineering?

## Decisions I've made — ratify or reject

The following are where the responses converged or one side was
clearly stronger. Speak up if any of these is wrong; otherwise
they become part of the plan.

1. **`script-fast` (Javy/QuickJS) moves to roadmap.** Codex strongly
   said drop; Gemini was lukewarm-positive. Defer until a real plugin
   proves need.
2. **Field-level live subscriptions move to roadmap.** Whole-card
   live upserts cover the first real use case; SSE shouldn't become
   general pub/sub in this milestone. Template renders the latest
   payload of the live card; when the card upserts, the entire
   payload updates.
3. **Template DSL starts with `standard_card` layout only.** Add
   `compact_row` and `score_card` only when a sender needs them.
4. **`mutateLocal` exposure: expose a small state-mutation interface
   in `:core:storage`.** Cleaner than `:core:domain` mediator
   (fewer modules).
5. **Multi-key decryption in `InboxRepository.refresh()`** for
   transition-state senders with multiple active pairings. Try each
   pairing key until one decrypts.
6. **In-process SSE event bus for now**, Redis pub/sub is on the
   roadmap when scaling demands. (Local dev unaffected.)
7. **AAD for live-card upserts binds:** `card_key`, `card_type`,
   sender_id, user_id, plugin_id (=row UUID), expiry, nonce, and a
   sequence_number for out-of-order handling. Client discards
   updates with `sequence_number <= current`.
8. **CAS dirty-flag bug** (push() may clear dirty after conflict-retry
   failure) gets fixed as Phase 0 pre-flight, with a regression test.
9. **Long-press stays multi-select.** Settings sheet entry is
   detail-view overflow only — no row context menu in this
   milestone.
10. **Phase 3 splits into 3a (templates) → 3b (live cards) → 3c
    (host-owned settings sheet).** Independent triad reviews after
    each.

## Roadmap order — final

Codex Reviewer B's "platform dependencies lead" framing felt strongest.
Tentative roadmap order:

1. Multi-process plugin host via AIDL/Binder
2. Native Kotlin runtime on top
3. Expanded capability bridge (camera, storage, file, location,
   message.respond, showNotification)
4. WebSocket / two-way live channel
5. `platform.live.subscribe(...)` API for script plugins
6. Field-level live subscriptions for templates (deferred from this
   milestone)
7. Script-fast (Javy/QuickJS) runtime (deferred from this milestone)
8. Guided lost-device rotation flow
9. Master-key rotation
10. Per-device envelope encryption
11. Per-plugin user preferences (notification cadence, custom
    labels, schedule)
12. Redis pub/sub for SSE event-bus scaling
13. Durable nonce-replay protection

Confirm the order or reorder.

## Output

Short. Per disagreement: position + safeguard or concession + smallest
implementation. Per ratification list: ack or reject each. Per roadmap:
ack or reorder.

If both of you converge on the disagreements after this pass, I write
the implementation plan from the consensus and we move into build
cycles.
