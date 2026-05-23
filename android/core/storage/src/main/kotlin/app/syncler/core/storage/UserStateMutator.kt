package app.syncler.core.storage

import kotlinx.coroutines.flow.StateFlow

/**
 * Cross-module write surface for the encrypted user state blob.
 *
 * Implemented by `:feature:inbox.UserStateRepository` (which owns the blob,
 * the master-key access, the CAS sync, and the disk persistence). Exposed
 * via an interface in `:core:storage` so other `:core:storage` components
 * — notably `PairedSenderStore` in the synced-pairing phase — can mutate
 * the synced state without depending on the inbox feature module.
 *
 * Implementations must:
 *  - apply [mutateAndPush]'s transform atomically against the current state
 *  - persist the new state locally before scheduling the push
 *  - schedule a CAS-backed push so other devices receive the change
 *  - silently accept calls while the session is locked (the dirty flag
 *    survives, and the push happens on next unlock)
 *
 * The `state` flow emits the latest local state on every change.
 */
interface UserStateMutator {
    val state: StateFlow<EncryptedUserState>

    /**
     * Atomically applies [transform] to the current state, persists the
     * result locally, marks the state dirty, and schedules a push to the
     * server. Safe to call concurrently — the implementation serializes
     * mutations.
     */
    suspend fun mutateAndPush(transform: (EncryptedUserState) -> EncryptedUserState)
}
