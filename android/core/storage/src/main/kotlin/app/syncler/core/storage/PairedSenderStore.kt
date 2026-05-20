package app.syncler.core.storage

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Locally-stored paired-sender record. Locks the identity tuple at pairing
 * time so future incoming messages can be verified against the same
 * fingerprint + name hash (I4 anti-spoofing layers 2/3).
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

    override suspend fun add(pairedSender: PairedSender) {
        val all = (cache.value.filter { it.pairingId != pairedSender.pairingId } + pairedSender)
            .sortedBy { it.firstPairedAt }
        persist(all)
    }

    override suspend fun remove(pairingId: String) {
        val all = cache.value.filter { it.pairingId != pairingId }
        persist(all)
    }

    override suspend fun byPairingId(pairingId: String): PairedSender? =
        cache.value.firstOrNull { it.pairingId == pairingId }

    override suspend fun bySenderId(senderId: String): PairedSender? =
        cache.value.firstOrNull { it.senderId == senderId }

    private fun loadAll(): List<PairedSender> {
        val ids = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        return ids.mapNotNull { id ->
            val record = prefs.getString("record:$id", null) ?: return@mapNotNull null
            decodeRecord(record)
        }
    }

    private fun persist(records: List<PairedSender>) {
        val editor = prefs.edit()
        editor.clear()
        editor.putStringSet(KEY_IDS, records.map { it.pairingId }.toSet())
        for (record in records) {
            editor.putString("record:${record.pairingId}", encodeRecord(record))
        }
        editor.apply()
        cache.value = records
    }

    private fun encodeRecord(r: PairedSender): String {
        val pk = android.util.Base64.encodeToString(r.senderPublicKey, android.util.Base64.NO_WRAP)
        val nh = android.util.Base64.encodeToString(r.nameHash, android.util.Base64.NO_WRAP)
        return "${r.pairingId}|${r.senderId}|${r.senderName}|$pk|${r.fingerprint}|$nh|${r.firstPairedAt}"
    }

    private fun decodeRecord(s: String): PairedSender? {
        val parts = s.split("|")
        if (parts.size != 7) return null
        return PairedSender(
            pairingId = parts[0],
            senderId = parts[1],
            senderName = parts[2],
            senderPublicKey = android.util.Base64.decode(parts[3], android.util.Base64.NO_WRAP),
            fingerprint = parts[4],
            nameHash = android.util.Base64.decode(parts[5], android.util.Base64.NO_WRAP),
            firstPairedAt = parts[6],
        )
    }

    private companion object {
        const val PREFS_NAME = "syncler.paired_senders.enc"
        const val KEY_IDS = "paired_ids"
    }
}

