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

    // Initialize synchronously from the mutator's current state so the very
    // first refresh after construction sees the right pairings — without
    // this, an async `onEach { activeFlow.value = ... }` would leave the
    // flow empty briefly and InboxRepository.refresh could advance lastSince
    // past undecryptable messages (Codex consultation 53 RED #1).
    private val activeFlow: MutableStateFlow<List<PairedSender>> =
        MutableStateFlow(projectActive(mutator.state.value))
    override val pairedSenders: StateFlow<List<PairedSender>> = activeFlow.asStateFlow()

    init {
        // Subscribe to subsequent state changes (the initial value is already
        // captured above).
        mutator.state
            .onEach { state -> activeFlow.value = projectActive(state) }
            .launchIn(scope)

        // First-launch migration is session-gated. We wait for isUnlocked
        // before reading legacy entries — without the gate, the @Singleton
        // init block fires before login and we could push one user's legacy
        // pairings into the next-logged-in user's account on a multi-user
        // device (Codex consultation 53 RED #2). The synced
        // `phase1MigrationDoneAt` is per-user, so it correctly prevents
        // re-migration for the same user across sessions.
        mutator.isUnlocked
            .onEach { unlocked ->
                if (unlocked) migrateLegacyEntriesIfNeeded()
            }
            .launchIn(scope)
    }

    private fun projectActive(state: EncryptedUserState): List<PairedSender> =
        state.pairedSenders
            .filter { it.removedAt == null }
            .map { it.toPairedSender() }
            // Use Instant comparison rather than lexical (consultation 53):
            // variable-fraction-second ISO-8601 strings don't sort correctly
            // as raw text.
            .sortedWith { a, b -> compareIsoTimestamps(a.firstPairedAt, b.firstPairedAt) }

    override suspend fun add(pairedSender: PairedSender): Unit = writeMutex.withLock {
        val newEntry = pairedSender.toEntry(firstPairedAtOverride = pairedSender.firstPairedAt, source = "manual")
        mutator.mutateAndPush { state ->
            val now = Instant.now(clock).toString()
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
            // Idempotent replace-or-append. Filter out any existing ACTIVE
            // entry with the same pairingId (a retry of the same add); do
            // NOT filter tombstoned entries with the same pairingId, even
            // defensively — tombstones must stay monotone (Codex
            // consultation 53 YELLOW #5). UUID collisions on tombstones
            // are astronomically improbable but the merger relies on
            // tombstones never disappearing, so we honor that here.
            val withoutActiveDup = rotated.filterNot {
                it.pairingId == pairedSender.pairingId && it.removedAt == null
            }
            state.copy(pairedSenders = withoutActiveDup + newEntry)
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
        // Pick the newest pairing for the sender. Use Instant comparison
        // rather than lexical (consultation 53): variable-fraction-second
        // ISO-8601 strings don't sort correctly as raw text.
        activeFlow.value.filter { it.senderId == senderId }
            .maxWithOrNull { a, b -> compareIsoTimestamps(a.firstPairedAt, b.firstPairedAt) }

    override suspend fun activePairingsForSender(senderId: String): List<PairedSender> =
        activeFlow.value.filter { it.senderId == senderId }

    /**
     * Session+per-user-gated bootstrap. The first time a user unlocks the
     * session after Phase 1 lands, we read this device's legacy local
     * pairings (the pre-Phase-1 [EncryptedPairedSenderStore] file) and
     * push them into the synced state with `source = "migration"`. The
     * synced [EncryptedUserState.phase1MigrationDoneAt] field is the
     * per-user flag — once any device sets it, the merger's
     * sticky-once-set semantics keep it set across sessions, so no later
     * login for the same user will re-migrate.
     *
     * Multi-user safety (Codex consultation 53 RED #2): the per-user flag
     * lives in the user-scoped synced state, not in the device-scoped
     * legacy prefs. Two users on the same device get their own flags;
     * neither's migration affects the other's.
     *
     * Cross-sender safety (Codex consultation 53 YELLOW #4): we exclude
     * legacy entries whose `senderId` already has a non-tombstoned synced
     * pairing. Without this filter, importing a stale legacy entry could
     * re-add a sender the user has since re-paired or revoked elsewhere.
     */
    private suspend fun migrateLegacyEntriesIfNeeded() {
        writeMutex.withLock {
            val state = mutator.state.value
            // Per-user gate: skip if THIS user has already migrated.
            if (state.phase1MigrationDoneAt != null) return@withLock

            val legacy = loadLegacy()
            // Even with no legacy entries, set the flag so we don't keep
            // checking on every isUnlocked re-emission.
            val activeSendersInState = state.pairedSenders
                .filter { it.removedAt == null }
                .map { it.senderId }
                .toSet()
            val existingIdsInState = state.pairedSenders.map { it.pairingId }.toSet()
            val toAdd = legacy
                .filter { it.pairingId !in existingIdsInState }
                .filter { it.senderId !in activeSendersInState }
                .map { it.toEntry(firstPairedAtOverride = it.firstPairedAt, source = "migration") }

            mutator.mutateAndPush { current ->
                val now = Instant.now(clock).toString()
                current.copy(
                    pairedSenders = if (toAdd.isEmpty()) current.pairedSenders else current.pairedSenders + toAdd,
                    phase1MigrationDoneAt = current.phase1MigrationDoneAt ?: now,
                )
            }
        }
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
