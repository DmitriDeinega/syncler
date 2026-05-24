package app.syncler.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 3c: stores and calculates effective mute status for senders.
 *
 * Effective status follows these priority rules:
 *  1. Local override (this device only) wins if set.
 *  2. Synced state (all devices) otherwise.
 *
 * Muted senders are filtered out of the inbox view but still processed.
 */
@Singleton
class MuteStore @Inject constructor(
    @ApplicationContext context: Context,
    private val mutator: UserStateMutator,
) {
    private val appContext = context.applicationContext
    private val localPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        appContext,
        "syncler.mute_overrides.enc",
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _localOverrides = MutableStateFlow(loadLocalOverrides())
    
    /**
     * Combined set of sender IDs that should be hidden from the inbox.
     * Calculated as: (synced + local_mutes) - local_unmutes.
     */
    val mutedSenderIds: StateFlow<Set<String>> = combine(
        mutator.state,
        _localOverrides
    ) { syncedState, overrides ->
        val synced = syncedState.mutedSenders
        val localMutes = overrides.filter { it.value }.keys
        val localUnmutes = overrides.filter { !it.value }.keys
        ((synced + localMutes) - localUnmutes).toSet()
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptySet()
    )

    suspend fun muteEverywhere(senderId: String) {
        mutator.mutateAndPush { state ->
            state.copy(mutedSenders = state.mutedSenders + senderId)
        }
    }

    suspend fun unmuteEverywhere(senderId: String) {
        mutator.mutateAndPush { state ->
            state.copy(mutedSenders = state.mutedSenders - senderId)
        }
    }

    suspend fun setLocalOverride(senderId: String, muted: Boolean?) = mutex.withLock {
        if (muted == null) {
            localPrefs.edit().remove(senderId).apply()
        } else {
            localPrefs.edit().putBoolean(senderId, muted).apply()
        }
        _localOverrides.value = loadLocalOverrides()
    }
    
    fun getLocalOverride(senderId: String): Boolean? {
        if (!localPrefs.contains(senderId)) return null
        return localPrefs.getBoolean(senderId, false)
    }
    
    fun isSyncedMuted(senderId: String): Boolean = 
        mutator.state.value.mutedSenders.contains(senderId)

    fun isMuted(senderId: String): Boolean = mutedSenderIds.value.contains(senderId)

    private fun loadLocalOverrides(): Map<String, Boolean> {
        @Suppress("UNCHECKED_CAST")
        return localPrefs.all as Map<String, Boolean>
    }
}
