package app.syncler.core.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Locally-stored paired-sender record. Locks the identity tuple at pairing
 * time so future incoming messages can be verified against the same
 * fingerprint + name hash + public key (I4 anti-spoofing layers 2/3/4).
 */
data class PairedSender(
    val pairingId: String,
    val senderId: String,
    val senderName: String,
    val senderPublicKey: ByteArray,
    val fingerprint: String,
    val nameHash: ByteArray,
    val firstPairedAt: String,
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
}

@Singleton
class EncryptedPairedSenderStore @Inject constructor(
    @ApplicationContext context: Context,
) : PairedSenderStore {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val cache = MutableStateFlow<List<PairedSender>>(loadAll())
    override val pairedSenders: StateFlow<List<PairedSender>> = cache.asStateFlow()

    @Synchronized
    override suspend fun add(pairedSender: PairedSender) {
        prefs.edit()
            .putString(recordKey(pairedSender.pairingId), encodeRecord(pairedSender))
            .apply()
        updateIndex { ids -> ids + pairedSender.pairingId }
        cache.value = loadAll()
    }

    @Synchronized
    override suspend fun remove(pairingId: String) {
        prefs.edit().remove(recordKey(pairingId)).apply()
        updateIndex { ids -> ids - pairingId }
        cache.value = loadAll()
    }

    override suspend fun byPairingId(pairingId: String): PairedSender? =
        cache.value.firstOrNull { it.pairingId == pairingId }

    override suspend fun bySenderId(senderId: String): PairedSender? =
        cache.value.firstOrNull { it.senderId == senderId }

    private fun updateIndex(transform: (Set<String>) -> Set<String>) {
        val current = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_IDS, transform(current)).apply()
    }

    private fun recordKey(id: String) = "record:$id"

    private fun loadAll(): List<PairedSender> {
        val ids = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        return ids
            .mapNotNull { id -> prefs.getString(recordKey(id), null)?.let(::decodeRecord) }
            .sortedBy { it.firstPairedAt }
    }

    private fun encodeRecord(r: PairedSender): String {
        // JSON-encoded: variable-length text fields (sender name, etc.)
        // cannot break parsing — replaces the M6 `|` delimiter scheme.
        return JSONObject().apply {
            put("pairing_id", r.pairingId)
            put("sender_id", r.senderId)
            put("sender_name", r.senderName)
            put("sender_public_key", Base64.encodeToString(r.senderPublicKey, Base64.NO_WRAP))
            put("fingerprint", r.fingerprint)
            put("name_hash", Base64.encodeToString(r.nameHash, Base64.NO_WRAP))
            put("first_paired_at", r.firstPairedAt)
        }.toString()
    }

    private fun decodeRecord(json: String): PairedSender? = try {
        val obj = JSONObject(json)
        PairedSender(
            pairingId = obj.getString("pairing_id"),
            senderId = obj.getString("sender_id"),
            senderName = obj.getString("sender_name"),
            senderPublicKey = Base64.decode(obj.getString("sender_public_key"), Base64.NO_WRAP),
            fingerprint = obj.getString("fingerprint"),
            nameHash = Base64.decode(obj.getString("name_hash"), Base64.NO_WRAP),
            firstPairedAt = obj.getString("first_paired_at"),
        )
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val PREFS_NAME = "syncler.paired_senders.enc"
        const val KEY_IDS = "paired_ids"
    }
}
