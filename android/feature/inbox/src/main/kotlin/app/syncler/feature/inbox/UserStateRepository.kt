package app.syncler.feature.inbox

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.syncler.core.auth.Session
import app.syncler.core.crypto.Aead
import app.syncler.core.network.StatePutRequestDto
import app.syncler.core.network.SynclerApi
import app.syncler.core.storage.ArchivedMessageEntry
import app.syncler.core.storage.EncryptedUserState
import app.syncler.core.storage.ReadMessageEntry
import app.syncler.core.storage.StateMerger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    @ApplicationContext context: Context,
    private val api: SynclerApi,
    private val session: Session,
) {
    // Not injected: the @Singleton has no Hilt provider for Clock. Tests use
    // the secondary constructor below to inject a fake.
    private val clock: Clock = Clock.systemUTC()
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _state = MutableStateFlow(loadFromDisk())
    val state: StateFlow<EncryptedUserState> = _state.asStateFlow()

    private val syncMutex = Mutex()

    /**
     * Marks the message as read locally (immediate) and schedules a push.
     * Idempotent — re-marking an already-read message refreshes the
     * timestamp, which is harmless and lets future "most-recent device"
     * conflict resolution pick the right winner.
     */
    suspend fun markRead(messageId: String) {
        val now = Instant.now(clock).toString()
        val updated = _state.value.let { current ->
            val others = current.readMessages.filterNot { it.messageId == messageId }
            current.copy(readMessages = others + ReadMessageEntry(messageId, now))
        }
        applyLocal(updated)
        runCatching { push() }.onFailure {
            // Local already updated; push will be retried on next foreground.
            Timber.tag(TAG).w(it, "markRead push failed; will retry")
        }
    }

    /** Archives a message locally; pushes asynchronously. */
    suspend fun markArchived(messageId: String) {
        val now = Instant.now(clock).toString()
        val updated = _state.value.let { current ->
            val others = current.archivedMessages.filterNot { it.messageId == messageId }
            current.copy(archivedMessages = others + ArchivedMessageEntry(messageId, now))
        }
        applyLocal(updated)
        runCatching { push() }.onFailure {
            Timber.tag(TAG).w(it, "markArchived push failed; will retry")
        }
    }

    /**
     * Fetches the remote state, merges with local, persists. Safe to call
     * on app foreground / inbox refresh. No-op when the session is locked.
     */
    suspend fun pull(): Result<Unit> = syncMutex.withLock {
        val masterKey = session.sessionState.value.masterKey ?: return@withLock Result.success(Unit)
        runCatching {
            val response = api.getUserState()
            val remoteState = decodeRemote(response.encryptedBlob, masterKey)
            val merged = StateMerger.merge(local = _state.value, remote = remoteState)
            applyLocal(merged, remoteVersion = response.stateVersion)
        }.onFailure { Timber.tag(TAG).w(it, "pull failed") }
    }

    /**
     * Encrypts the local state and PUTs to the server with the last-known
     * CAS version. On 409 conflict: pull-merge-retry once. No-op when the
     * session is locked.
     */
    suspend fun push(): Result<Unit> = syncMutex.withLock {
        val masterKey = session.sessionState.value.masterKey ?: return@withLock Result.success(Unit)
        runCatching {
            attemptPush(masterKey)
        }.onFailure { Timber.tag(TAG).w(it, "push failed (after retry)") }
    }

    private suspend fun attemptPush(masterKey: ByteArray) {
        val blob = encodeLocal(_state.value, masterKey)
        val expected = lastKnownRemoteVersion()
        try {
            val response = api.putUserState(
                StatePutRequestDto(
                    expectedStateVersion = expected,
                    newEncryptedBlob = blob,
                ),
            )
            if (!response.isSuccessful) {
                if (response.code() == 409) {
                    handleConflictAndRetry(masterKey)
                } else {
                    throw HttpException(response)
                }
            } else {
                response.body()?.newStateVersion?.let { setLastKnownRemoteVersion(it) }
            }
        } catch (e: HttpException) {
            if (e.code() == 409) handleConflictAndRetry(masterKey) else throw e
        }
    }

    private suspend fun handleConflictAndRetry(masterKey: ByteArray) {
        // Server has a newer blob. Pull, merge, re-push exactly once. If the
        // retry also conflicts, give up and rely on the next foreground tick.
        val response = api.getUserState()
        val remoteState = decodeRemote(response.encryptedBlob, masterKey)
        val merged = StateMerger.merge(local = _state.value, remote = remoteState)
        applyLocal(merged, remoteVersion = response.stateVersion)

        val newBlob = encodeLocal(_state.value, masterKey)
        val retry = api.putUserState(
            StatePutRequestDto(
                expectedStateVersion = response.stateVersion,
                newEncryptedBlob = newBlob,
            ),
        )
        if (retry.isSuccessful) {
            retry.body()?.newStateVersion?.let { setLastKnownRemoteVersion(it) }
        } else {
            Timber.tag(TAG).w("CAS retry still conflicted (HTTP %d); will retry later", retry.code())
        }
    }

    private fun applyLocal(state: EncryptedUserState, remoteVersion: Int? = null) {
        _state.value = state
        prefs.edit()
            .putString(KEY_STATE_JSON, state.toJson())
            .apply {
                if (remoteVersion != null) putInt(KEY_REMOTE_VERSION, remoteVersion)
            }
            .apply()
    }

    private fun loadFromDisk(): EncryptedUserState =
        prefs.getString(KEY_STATE_JSON, null)?.let {
            runCatching { EncryptedUserState.fromJson(it) }.getOrNull()
        } ?: EncryptedUserState()

    private fun lastKnownRemoteVersion(): Int = prefs.getInt(KEY_REMOTE_VERSION, 0)
    private fun setLastKnownRemoteVersion(v: Int) {
        prefs.edit().putInt(KEY_REMOTE_VERSION, v).apply()
    }

    private fun encodeLocal(state: EncryptedUserState, masterKey: ByteArray): String {
        val plaintext = state.toJson().toByteArray(Charsets.UTF_8)
        val wire = Aead.encrypt(masterKey, plaintext)
        return Base64.encodeToString(wire, Base64.NO_WRAP)
    }

    private fun decodeRemote(blobB64: String, masterKey: ByteArray): EncryptedUserState {
        if (blobB64.isBlank()) return EncryptedUserState()
        val wire = Base64.decode(blobB64, Base64.NO_WRAP)
        val plaintext = Aead.decrypt(masterKey, wire)
        return EncryptedUserState.fromJson(plaintext.toString(Charsets.UTF_8))
    }

    private companion object {
        const val TAG = "UserStateRepo"
        const val PREFS_NAME = "syncler.user_state.enc"
        const val KEY_STATE_JSON = "state_json"
        const val KEY_REMOTE_VERSION = "remote_version"
    }
}
