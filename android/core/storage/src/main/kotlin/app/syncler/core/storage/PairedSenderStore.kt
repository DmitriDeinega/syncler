package app.syncler.core.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Locally-projected paired-sender record. Identical to the V0 schema —
 * the public type does not change so callers stay agnostic to the
 * Phase 1 sync wiring underneath.
 */
data class PairedSender(
    val pairingId: String,
    val senderId: String,
    val senderName: String,
    val senderPublicKey: ByteArray,
    val fingerprint: String,
    val nameHash: ByteArray,
    val firstPairedAt: String,
    /**
     * 32-byte AES-256 key shared with the sender. Encrypted under the
     * user's master key inside the synced state blob — propagates to
     * every device the user enrolls.
     */
    val pairingKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairedSender) return false
        return pairingId == other.pairingId
    }

    override fun hashCode(): Int = pairingId.hashCode()
}

interface PairedSenderStore {
    val pairedSenders: StateFlow<List<PairedSender>>
    suspend fun add(pairedSender: PairedSender)
    suspend fun remove(pairingId: String)
    suspend fun byPairingId(pairingId: String): PairedSender?
    suspend fun bySenderId(senderId: String): PairedSender?

    /**
     * All currently-active pairings for the given sender. Returns more
     * than one entry during the transition window where multiple devices
     * paired separately pre-Phase-1; [InboxRepository] uses this to try
     * each candidate pairing key when decrypting an incoming message.
     */
    suspend fun activePairingsForSender(senderId: String): List<PairedSender>
}

/**
 * Phase 1: synced paired-sender store. Projects the `pairedSenders` field
 * of the encrypted user-state blob ([UserStateMutator.state]) into a
 * `StateFlow<List<PairedSender>>` of *active* (non-tombstoned) entries.
 *
 * Writes go through the mutator's atomic `mutateAndPush` so every change
 * is persisted locally AND scheduled for a CAS-backed push to the
 * server. Other devices the user owns pick up the change on their next
 * /v1/state pull.
 *
 * **Tombstone semantics.** Revoking a pairing does NOT remove the entry
 * from the synced state — it sets `removedAt`. The state merger keeps
 * tombstones; an offline device with a stale active entry cannot
 * resurrect a revoked pairing on its next push (consultation 47).
 *
 * **Re-pair semantics.** Adding a new pairing for a `senderId` that
 * already has an active pairing tombstones the old one in the same
 * atomic write. `bySenderId()` returns the newest active pairing.
 *
 * **First-launch migration.** On first construction after the SCHEMA_V4
 * bump, [migrateLegacyEntries] reads the legacy local prefs (where
 * pre-Phase-1 pairings lived) and pushes them up into the synced state
 * with `source = "migration"`. A one-shot flag in the local prefs
 * prevents re-running. Existing installs keep their pairings; new
 * installs get an empty state.
 */
@Singleton
class SyncedPairedSenderStore @Inject constructor(
    @ApplicationContext context: Context,
    private val mutator: UserStateMutator,
) : PairedSenderStore {

    private val appContext = context.applicationContext
    private val legacyPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        appContext,
        PREFS_NAME,
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val clock: Clock = Clock.systemUTC()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    private val activeFlow = MutableStateFlow<List<PairedSender>>(emptyList())
    override val pairedSenders: StateFlow<List<PairedSender>> = activeFlow.asStateFlow()

    init {
        // Project the synced state's pairedSenders into active-only PairedSender
        // records on every state change. Tombstoned entries are filtered out
        // here — they remain in the synced blob for resurrection protection
        // but the rest of the app sees only active pairings.
        mutator.state
            .onEach { state ->
                activeFlow.value = state.pairedSenders
                    .filter { it.removedAt == null }
                    .map { it.toPairedSender() }
                    .sortedBy { it.firstPairedAt }
            }
            .launchIn(scope)

        // One-shot first-launch migration. Reads legacy local entries and
        // pushes them into the synced state with source="migration" so the
        // user's other devices pick them up on next pull.
        scope.launch { migrateLegacyEntries() }
    }

    override suspend fun add(pairedSender: PairedSender): Unit = writeMutex.withLock {
        val now = Instant.now(clock).toString()
        val newEntry = pairedSender.toEntry(firstPairedAtOverride = pairedSender.firstPairedAt, source = "manual")
        mutator.mutateAndPush { state ->
            // Re-pair semantics: tombstone any existing active pairing for the
            // same senderId so bySenderId() resolves cleanly and the sender's
            // old pairing key cannot decrypt new messages (forward-secrecy
            // dimension of manual rotation per consultation 47 / 49).
            val rotated = state.pairedSenders.map { existing ->
                if (existing.senderId == pairedSender.senderId &&
                    existing.removedAt == null &&
                    existing.pairingId != pairedSender.pairingId
                ) {
                    existing.copy(removedAt = now)
                } else {
                    existing
                }
            }
            // Replace-or-append for the new entry (idempotent for retries).
            val withoutNew = rotated.filterNot { it.pairingId == pairedSender.pairingId }
            state.copy(pairedSenders = withoutNew + newEntry)
        }
    }

    override suspend fun remove(pairingId: String): Unit = writeMutex.withLock {
        val now = Instant.now(clock).toString()
        mutator.mutateAndPush { state ->
            state.copy(
                pairedSenders = state.pairedSenders.map { entry ->
                    if (entry.pairingId == pairingId && entry.removedAt == null) {
                        entry.copy(removedAt = now)
                    } else {
                        entry
                    }
                },
            )
        }
    }

    override suspend fun byPairingId(pairingId: String): PairedSender? =
        activeFlow.value.firstOrNull { it.pairingId == pairingId }

    override suspend fun bySenderId(senderId: String): PairedSender? =
        // The active list is sorted by firstPairedAt ascending; the LAST entry
        // for a senderId is the newest. Re-pair tombstones the old entries
        // atomically so there's usually exactly one active per sender; during
        // the migration window or a pending CAS push, there might be more.
        activeFlow.value.filter { it.senderId == senderId }.maxByOrNull { it.firstPairedAt }

    override suspend fun activePairingsForSender(senderId: String): List<PairedSender> =
        activeFlow.value.filter { it.senderId == senderId }

    /**
     * One-shot bootstrap: push any legacy local entries into the synced
     * state. Guarded by [MIGRATION_FLAG] so subsequent launches no-op.
     *
     * The push is via the same `mutateAndPush` path everything else uses,
     * so a network failure leaves the dirty flag set and the migration
     * is retried on the next foreground tick. The flag is set
     * **after** the push schedules — at worst we re-push the same set of
     * entries, which the merger deduplicates.
     */
    private suspend fun migrateLegacyEntries() {
        if (legacyPrefs.getBoolean(MIGRATION_FLAG, false)) return
        val legacy = loadLegacy()
        if (legacy.isEmpty()) {
            legacyPrefs.edit().putBoolean(MIGRATION_FLAG, true).apply()
            return
        }
        mutator.mutateAndPush { state ->
            val existingIds = state.pairedSenders.map { it.pairingId }.toSet()
            val toAdd = legacy
                .filter { it.pairingId !in existingIds }
                .map { it.toEntry(firstPairedAtOverride = it.firstPairedAt, source = "migration") }
            if (toAdd.isEmpty()) state
            else state.copy(pairedSenders = state.pairedSenders + toAdd)
        }
        legacyPrefs.edit().putBoolean(MIGRATION_FLAG, true).apply()
    }

    private fun loadLegacy(): List<PairedSender> {
        val ids = legacyPrefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        return ids
            .mapNotNull { id -> legacyPrefs.getString(recordKey(id), null)?.let(::decodeLegacyRecord) }
            .sortedBy { it.firstPairedAt }
    }

    private fun recordKey(id: String) = "record:$id"

    private fun decodeLegacyRecord(json: String): PairedSender? = try {
        val obj = JSONObject(json)
        PairedSender(
            pairingId = obj.getString("pairing_id"),
            senderId = obj.getString("sender_id"),
            senderName = obj.getString("sender_name"),
            senderPublicKey = Base64.decode(obj.getString("sender_public_key"), Base64.NO_WRAP),
            fingerprint = obj.getString("fingerprint"),
            nameHash = Base64.decode(obj.getString("name_hash"), Base64.NO_WRAP),
            firstPairedAt = obj.getString("first_paired_at"),
            pairingKey = obj.optString("pairing_key").takeIf { it.isNotBlank() }
                ?.let { Base64.decode(it, Base64.NO_WRAP) }
                ?: ByteArray(0),
        )
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val PREFS_NAME = "syncler.paired_senders.enc"
        const val KEY_IDS = "paired_ids"
        const val MIGRATION_FLAG = "phase1_migration_done"
    }
}

/**
 * Conversion between the public [PairedSender] (raw bytes for crypto
 * primitives) and the synced [PairedSenderEntry] (base64 strings for
 * JSON serialization).
 */
private fun PairedSenderEntry.toPairedSender(): PairedSender = PairedSender(
    pairingId = pairingId,
    senderId = senderId,
    senderName = senderName,
    senderPublicKey = Base64.decode(senderPublicKey, Base64.NO_WRAP),
    fingerprint = fingerprint,
    nameHash = Base64.decode(nameHash, Base64.NO_WRAP),
    firstPairedAt = firstPairedAt,
    pairingKey = Base64.decode(pairingKey, Base64.NO_WRAP),
)

private fun PairedSender.toEntry(
    firstPairedAtOverride: String,
    source: String,
): PairedSenderEntry = PairedSenderEntry(
    pairingId = pairingId,
    senderId = senderId,
    senderName = senderName,
    senderPublicKey = Base64.encodeToString(senderPublicKey, Base64.NO_WRAP),
    fingerprint = fingerprint,
    nameHash = Base64.encodeToString(nameHash, Base64.NO_WRAP),
    pairingKey = Base64.encodeToString(pairingKey, Base64.NO_WRAP),
    firstPairedAt = firstPairedAtOverride,
    source = source,
)
