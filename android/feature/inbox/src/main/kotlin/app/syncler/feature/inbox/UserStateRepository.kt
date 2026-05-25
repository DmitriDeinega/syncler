package app.syncler.feature.inbox

import app.syncler.core.auth.Session
import app.syncler.core.crypto.Aead
import java.util.Base64
import app.syncler.core.network.StatePutRequestDto
import app.syncler.core.network.SynclerApi
import app.syncler.core.storage.ArchivedMessageEntry
import app.syncler.core.storage.DeletedMessageEntry
import app.syncler.core.storage.EncryptedUserState
import app.syncler.core.storage.ReadMessageEntry
import app.syncler.core.storage.StateMerger
import app.syncler.core.storage.UserStateMutator
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import timber.log.Timber

/**
 * Owns the user's [EncryptedUserState] across the app lifecycle.
 *
 *  - **Local-first:** writes go to disk immediately (encrypted at rest via
 *    EncryptedSharedPreferences); UI reactions don't wait for the network.
 *  - **M7 CAS sync:** [pull] fetches the server blob, decrypts with the
 *    user's master key, merges with local via [StateMerger], persists, and
 *    rewrites the local state. [push] encrypts the local state, PUTs to
 *    `/v1/state` with the last-known version, and on 409 conflict pulls
 *    the freshest blob, re-merges, and retries once.
 *  - **Master-key dependence:** sync no-ops when the session is locked
 *    (no master key in memory). Local writes still proceed — the app
 *    works offline; pushes happen when the user next unlocks.
 *
 * Read state is the V1 primary use case. Archived state is wired through
 * the same channel for Phase 3.
 */
@Singleton
class UserStateRepository @Inject constructor(
    private val prefs: UserStatePrefs,
    private val api: SynclerApi,
    private val session: Session,
    private val keyGenerationStore: app.syncler.core.auth.KeyGenerationStore,
    private val clock: Clock,
) : UserStateMutator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Auto-clear local state when the session locks (logout, JWT expiry,
        // process restart without password). Without this, a subsequent
        // login on the same device would inherit the previous user's read
        // marks AND push them up to the new user's server account.
        //
        // We drop(1) so the initial Locked-at-startup emission (before any
        // login) doesn't trigger a redundant clear of the disk state we
        // just loaded — clear only runs on a *transition* away from
        // unlocked.
        session.sessionState
            .map { it.isUnlocked }
            .distinctUntilChanged()
            .drop(1)
            .onEach { unlocked -> if (!unlocked) clearInternal() }
            .launchIn(scope)
    }

    private val _state = MutableStateFlow(loadFromDisk())
    override val state: StateFlow<EncryptedUserState> = _state.asStateFlow()

    /**
     * Mirrors [Session.sessionState.isUnlocked] as a StateFlow so cross-module
     * consumers (e.g. SyncedPairedSenderStore's first-launch migration) can
     * gate work on a logged-in session.
     */
    override val isUnlocked: StateFlow<Boolean> = session.sessionState
        .map { it.isUnlocked }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = session.sessionState.value.isUnlocked,
        )

    /**
     * [UserStateMutator] implementation. Cross-module callers (e.g. the
     * upcoming `PairedSenderStore` integration for synced pairing) use this
     * to add their own fields to the synced state blob without depending on
     * `:feature:inbox`.
     */
    override suspend fun mutateAndPush(transform: (EncryptedUserState) -> EncryptedUserState) {
        mutateLocal(transform)
        markDirtyAndPush()
    }

    private val syncMutex = Mutex()

    /**
     * Marks the message as read locally (immediate) and schedules a push.
     * Idempotent — re-marking an already-read message refreshes the
     * timestamp, which is harmless and lets future "most-recent device"
     * conflict resolution pick the right winner.
     *
     * The local mutation is atomic via [MutableStateFlow.update]: two
     * concurrent calls cannot lose each other's entries. The disk write
     * happens inside the same critical section as the in-memory update so
     * a process kill mid-write doesn't expose an inconsistent state.
     */
    suspend fun markRead(messageId: String) {
        val now = Instant.now(clock).toString()
        mutateLocal { current ->
            val others = current.readMessages.filterNot { it.messageId == messageId }
            current.copy(readMessages = others + ReadMessageEntry(messageId, now))
        }
        markDirtyAndPush()
    }

    /** Archives a message locally; pushes asynchronously. */
    suspend fun markArchived(messageId: String) {
        val now = Instant.now(clock).toString()
        mutateLocal { current ->
            val others = current.archivedMessages.filterNot { it.messageId == messageId }
            current.copy(archivedMessages = others + ArchivedMessageEntry(messageId, now))
        }
        markDirtyAndPush()
    }

    /** Bulk archive for multi-select. Single mutation + single push. */
    suspend fun markManyArchived(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        val now = Instant.now(clock).toString()
        val ids = messageIds.toSet()
        mutateLocal { current ->
            val keep = current.archivedMessages.filterNot { it.messageId in ids }
            val newEntries = ids.map { ArchivedMessageEntry(it, now) }
            current.copy(archivedMessages = keep + newEntries)
        }
        markDirtyAndPush()
    }

    /**
     * Removes the message from the archive set, returning it to the active
     * inbox. Note: this is a local "forget the archive mark" operation, not
     * a tombstone — if another device has a still-current archive entry for
     * the same message and we re-pull before pushing, the merge's
     * max(archivedAt)-wins rule will resurrect the archive locally. V1
     * accepts that tiny race in exchange for not encoding tombstones
     * (which would need a separate tombstones list and explicit GC).
     */
    suspend fun markUnarchived(messageId: String) {
        mutateLocal { current ->
            current.copy(
                archivedMessages = current.archivedMessages.filterNot { it.messageId == messageId },
            )
        }
        markDirtyAndPush()
    }

    /**
     * M11.6: marks a message deleted. Distinct from archive — deleted
     * messages are hidden from BOTH the inbox and the archive screen.
     * Merge semantics are monotone (no undelete in V1) so this entry
     * permanently wins across devices.
     */
    suspend fun markDeleted(messageId: String) {
        val now = Instant.now(clock).toString()
        mutateLocal { current ->
            val others = current.deletedMessages.filterNot { it.messageId == messageId }
            current.copy(deletedMessages = others + DeletedMessageEntry(messageId, now))
        }
        markDirtyAndPush()
    }

    suspend fun markManyDeleted(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        val now = Instant.now(clock).toString()
        val ids = messageIds.toSet()
        mutateLocal { current ->
            val keep = current.deletedMessages.filterNot { it.messageId in ids }
            val newEntries = ids.map { DeletedMessageEntry(it, now) }
            current.copy(deletedMessages = keep + newEntries)
        }
        markDirtyAndPush()
    }

    /**
     * Atomic local-state mutation. Holds [syncMutex] so a concurrent [pull]
     * cannot interleave its merge between the read and the write here, and
     * so disk persistence is also serialized.
     */
    private suspend fun mutateLocal(transform: (EncryptedUserState) -> EncryptedUserState) {
        syncMutex.withLock {
            val next = transform(_state.value)
            _state.value = next
            persist(next)
        }
    }

    private fun markDirty() = prefs.putBoolean(KEY_DIRTY, true)

    private fun clearDirty() = prefs.putBoolean(KEY_DIRTY, false)

    private fun isDirty(): Boolean = prefs.getBoolean(KEY_DIRTY, false)

    private suspend fun markDirtyAndPush() {
        markDirty()
        runCatching { push() }.onFailure {
            // Local change still on disk + flagged dirty; flushPendingPush on
            // next foreground/refresh will retry.
            Timber.tag(TAG).w(it, "push failed; flagged dirty for retry")
        }
    }

    /**
     * Attempts to push the local state if dirty. Called by [InboxViewModel]
     * on init and on refresh, so a push that 409'd or hit network failure
     * gets retried without requiring another user action.
     */
    suspend fun flushPendingPush(): Result<Unit> {
        if (!isDirty()) return Result.success(Unit)
        return push()
    }

    /**
     * Fetches the remote state, merges with local, persists. Safe to call
     * on app foreground / inbox refresh. No-op when the session is locked.
     *
     * The entire fetch-merge-persist runs inside [syncMutex] so concurrent
     * [markRead] / [markArchived] cannot interleave between the merge
     * computation and the disk write.
     */
    suspend fun pull(): Result<Unit> = syncMutex.withLock {
        val masterKey = session.sessionState.value.masterKey ?: return@withLock Result.success(Unit)
        val userId = session.currentUserId() ?: return@withLock Result.success(Unit)
        runCatching {
            val response = api.getUserState()
            // Phase 8 §10.10 — refuse to operate on a stale snapshot
            // (catches a malicious server returning a pre-rotation
            // blob after the client has already rotated up).
            verifyResponseKeyGeneration(response.keyGeneration, source = "state.get")
            // Phase 8d §10.9 — decrypt with the row's (key_generation,
            // state_version, user_id) AAD.
            val remoteState = decodeRemote(
                blobB64 = response.encryptedBlob,
                masterKey = masterKey,
                stateVersion = response.stateVersion,
                keyGeneration = response.keyGeneration,
                userId = userId,
            )
            val merged = StateMerger.merge(local = _state.value, remote = remoteState)
            _state.value = merged
            persist(merged, remoteVersion = response.stateVersion)
        }.onFailure { Timber.tag(TAG).w(it, "pull failed") }
    }

    /**
     * §10.10 enforcement entry-point for non-login responses. Reads
     * the active session's userId; skips the check when no session is
     * unlocked (defensive: an interleaved logout shouldn't crash a
     * pending pull). The user is the one we already validated at
     * login, so the high-water mark scope is correct.
     */
    private fun verifyResponseKeyGeneration(observed: Int, source: String) {
        val userId = session.currentUserId() ?: return
        keyGenerationStore.verifyAndBump(
            userId = userId,
            observed = observed,
            source = source,
        )
    }

    /**
     * Encrypts the local state and PUTs to the server with the last-known
     * CAS version. On 409 conflict: pull-merge-retry once. No-op when the
     * session is locked. Successful push clears the dirty flag.
     */
    suspend fun push(): Result<Unit> = syncMutex.withLock {
        val masterKey = session.sessionState.value.masterKey ?: return@withLock Result.success(Unit)
        // Defensive: a future-schema blob (downloaded by a newer client and
        // somehow persisted on this older one) should NOT be pushed back —
        // we'd write known fields under the future schema_version, losing
        // the V3+ fields the newer client wrote. Refuse-to-push surfaces the
        // version skew as a push failure rather than corrupting state.
        if (_state.value.schemaVersion > EncryptedUserState.SCHEMA_CURRENT) {
            Timber.tag(TAG).w(
                "refusing to push schema_version %d (client supports %d)",
                _state.value.schemaVersion, EncryptedUserState.SCHEMA_CURRENT,
            )
            return@withLock Result.failure(IllegalStateException("future schema; refusing to push"))
        }
        runCatching {
            attemptPush(masterKey)
            clearDirty()
        }.onFailure { Timber.tag(TAG).w(it, "push failed (after retry)") }
    }

    /**
     * One initial PUT, one conflict-driven retry, no more. Linear control
     * flow — no try/catch that loops, no recursion (Codex consultation 51
     * YELLOW #5: the previous try/catch could re-trigger
     * handleConflictAndRetry when the retry itself threw HttpException
     * with code 409, effectively "retry twice").
     *
     * On permanent failure the outer push()'s runCatching catches the
     * thrown exception and skips clearDirty() — the dirty flag stays
     * true so the next foreground tick retries (consultation 47).
     */
    private suspend fun attemptPush(masterKey: ByteArray) {
        val first = doPut(expectedVersion = lastKnownRemoteVersion(), masterKey = masterKey)
        if (first.isSuccessful) {
            first.body()?.let { body ->
                // §10.10 — every successful response carrying a
                // key_generation MUST be checked against the local
                // high-water mark.
                verifyResponseKeyGeneration(body.keyGeneration, source = "state.put")
                setLastKnownRemoteVersion(body.newStateVersion)
            }
            return
        }
        if (first.code() != 409) {
            throw HttpException(first)
        }

        // 409: pull the newer blob, merge, re-PUT exactly once.
        val pull = api.getUserState()
        // Same §10.10 check on this GET — defense in depth before
        // we even merge.
        verifyResponseKeyGeneration(pull.keyGeneration, source = "state.get")
        val pullUserId = session.currentUserId()
            ?: error("Session lost between PUT and re-pull — refusing to merge")
        val remoteState = decodeRemote(
            blobB64 = pull.encryptedBlob,
            masterKey = masterKey,
            stateVersion = pull.stateVersion,
            keyGeneration = pull.keyGeneration,
            userId = pullUserId,
        )
        _state.value = StateMerger.merge(local = _state.value, remote = remoteState)
        persist(_state.value, remoteVersion = pull.stateVersion)

        // Re-check the future-schema refusal AFTER the merge. The merger
        // takes maxOf(local.schema, remote.schema), so a remote blob from
        // a newer client could pull this older client's local state up to
        // a schema this client doesn't fully understand. Pushing it back
        // would clobber fields this client can't see. Surface as a
        // permanent failure for this push attempt; the dirty flag survives
        // (Codex consultation 52 YELLOW).
        if (_state.value.schemaVersion > EncryptedUserState.SCHEMA_CURRENT) {
            Timber.tag(TAG).w(
                "post-merge schema_version %d > client SCHEMA_CURRENT %d; refusing retry",
                _state.value.schemaVersion, EncryptedUserState.SCHEMA_CURRENT,
            )
            throw IllegalStateException("future schema after conflict merge; refusing to push")
        }

        val retry = doPut(expectedVersion = pull.stateVersion, masterKey = masterKey)
        if (retry.isSuccessful) {
            retry.body()?.let { body ->
                verifyResponseKeyGeneration(body.keyGeneration, source = "state.put")
                setLastKnownRemoteVersion(body.newStateVersion)
            }
            return
        }
        Timber.tag(TAG).w(
            "CAS retry failed (HTTP %d); dirty flag preserved for next retry",
            retry.code(),
        )
        throw HttpException(retry)
    }

    private suspend fun doPut(
        expectedVersion: Int,
        masterKey: ByteArray,
    ): retrofit2.Response<app.syncler.core.network.StatePutResponseDto> {
        val userId = session.currentUserId()
            ?: error("cannot PUT /v1/state without an unlocked session user_id")
        // Phase 8d §10.9 — AAD binds (key_generation, state_version,
        // user_id). ``state_version`` is the POST-write value per the
        // §10.4 lockstep: the row will carry expectedVersion + 1 after
        // the server's CAS succeeds. For an initial-insert
        // (expectedVersion == 0) the server stamps state_version = 1
        // (see services/state.py).
        val postWriteVersion = if (expectedVersion == 0) 1 else expectedVersion + 1
        val keyGen = session.currentKeyGeneration()
        val blob = encodeLocal(
            state = _state.value,
            masterKey = masterKey,
            stateVersion = postWriteVersion,
            keyGeneration = keyGen,
            userId = userId,
        )
        return api.putUserState(
            StatePutRequestDto(
                expectedStateVersion = expectedVersion,
                newEncryptedBlob = blob,
                // Phase 8 §10.5 — bind the AAD to the locked-server
                // generation. The Session's value is set at login from
                // LoginResponse.key_generation and bumped on every
                // successful rotation.
                keyGenerationObserved = keyGen,
            ),
        )
    }

    private fun persist(state: EncryptedUserState, remoteVersion: Int? = null) {
        prefs.putString(KEY_STATE_JSON, state.toJson())
        if (remoteVersion != null) prefs.putInt(KEY_REMOTE_VERSION, remoteVersion)
    }

    /**
     * Wipes all persisted state and resets to empty. Invoked automatically
     * on session lock (logout, JWT expiry) — see the init block. Exposed
     * for tests; production code should rely on the session-observer.
     */
    suspend fun clear() = clearInternal()

    private suspend fun clearInternal() = syncMutex.withLock {
        _state.value = EncryptedUserState()
        prefs.clear()
    }

    private fun loadFromDisk(): EncryptedUserState =
        prefs.getString(KEY_STATE_JSON)?.let {
            runCatching { EncryptedUserState.fromJson(it) }.getOrNull()
        } ?: EncryptedUserState()

    private fun lastKnownRemoteVersion(): Int = prefs.getInt(KEY_REMOTE_VERSION, 0)
    private fun setLastKnownRemoteVersion(v: Int) = prefs.putInt(KEY_REMOTE_VERSION, v)

    /**
     * Phase 8d §10.9 — AES-GCM encrypt the local state JSON with AAD
     * `{key_generation, state_version, user_id}`. ``stateVersion``
     * is the POST-write value (the version the row will carry after
     * the server's CAS), per the §10.4 lockstep contract.
     */
    private fun encodeLocal(
        state: EncryptedUserState,
        masterKey: ByteArray,
        stateVersion: Int,
        keyGeneration: Int,
        userId: String,
    ): String {
        val plaintext = state.toJson().toByteArray(Charsets.UTF_8)
        val aad = app.syncler.core.crypto.RotationAad.userState(
            userId = userId,
            keyGeneration = keyGeneration,
            stateVersion = stateVersion,
        )
        val wire = Aead.encrypt(masterKey, plaintext, aad = aad)
        // java.util.Base64 (not android.util.Base64) — same wire format
        // (RFC 4648 with padding) and works in pure-JVM unit tests too.
        return Base64.getEncoder().encodeToString(wire)
    }

    /**
     * Phase 8d §10.9 — decrypt with the EXACT AAD the wrapper used.
     * ``stateVersion`` + ``keyGeneration`` come from the row the
     * server returned alongside the blob.
     */
    private fun decodeRemote(
        blobB64: String,
        masterKey: ByteArray,
        stateVersion: Int,
        keyGeneration: Int,
        userId: String,
    ): EncryptedUserState {
        if (blobB64.isBlank()) return EncryptedUserState()
        val wire = Base64.getDecoder().decode(blobB64)
        val aad = app.syncler.core.crypto.RotationAad.userState(
            userId = userId,
            keyGeneration = keyGeneration,
            stateVersion = stateVersion,
        )
        val plaintext = Aead.decrypt(masterKey, wire, aad = aad)
        // Codex 109 hygiene: wipe the decrypted bytes after JSON
        // parse. The String the JVM internalizes is still in heap
        // memory but at least the original ByteArray is cleared.
        return try {
            EncryptedUserState.fromJson(plaintext.toString(Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    private companion object {
        const val TAG = "UserStateRepo"
        const val KEY_STATE_JSON = "state_json"
        const val KEY_REMOTE_VERSION = "remote_version"
        const val KEY_DIRTY = "dirty"
    }
}
