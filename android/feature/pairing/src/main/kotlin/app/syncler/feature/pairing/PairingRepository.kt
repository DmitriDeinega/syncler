package app.syncler.feature.pairing

import android.util.Base64
import app.syncler.core.network.PairingCompleteRequestDto
import app.syncler.core.network.SynclerApi
import app.syncler.core.storage.PairedSender
import app.syncler.core.storage.PairedSenderStore
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException
import timber.log.Timber

/**
 * Drives the pairing flow:
 *   1. User scans QR or pastes broker URL
 *   2. We extract pairing_token from the URL
 *   3. Show I4 confirmation dialog (fingerprint + sender name)
 *   4. On confirm: POST /v1/pairing/complete, persist PairedSender locally
 *
 * Sender authenticity at runtime (I4 layer 4) is enforced separately by
 * checking message envelope signatures against the locked PairedSender
 * record (handled in M5's foreground service pipeline).
 */
@Singleton
class PairingRepository @Inject constructor(
    private val api: SynclerApi,
    private val pairedSenderStore: PairedSenderStore,
) {
    /**
     * Parse a broker URL into its components without contacting the server.
     *
     * Expected URL shape:
     *   https://api.syncler.app/v1/pairing/complete?token=<base64>
     *
     * We do NOT trust the URL's claimed fingerprint at this point — the
     * fingerprint shown to the user is fetched fresh on completion from
     * the server, and the user manually confirms it matches what the
     * sender printed on their own side.
     */
    fun parseBrokerUrl(url: String): PairingCandidate? {
        if (!url.startsWith("https://")) {
            Timber.tag(TAG).w("non-https broker URL rejected: %s", url.take(40))
            return null
        }
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        val token = uri.getQueryParameter("token") ?: return null
        return PairingCandidate(brokerUrl = url, pairingToken = token)
    }

    suspend fun complete(
        candidate: PairingCandidate,
        encryptedInitialState: ByteArray,
    ): Result<PairedSender> = runCatching {
        val response = api.completePairing(
            PairingCompleteRequestDto(
                pairingToken = candidate.pairingToken,
                encryptedInitialState = Base64.encodeToString(
                    encryptedInitialState,
                    Base64.NO_WRAP,
                ),
            ),
        )
        val record = PairedSender(
            pairingId = response.pairingId,
            senderId = response.senderId,
            senderName = response.senderName,
            senderPublicKey = Base64.decode(response.senderPublicKey, Base64.NO_WRAP),
            fingerprint = response.senderPublicKeyFingerprint,
            nameHash = Base64.decode(response.senderNameHash, Base64.NO_WRAP),
            firstPairedAt = response.pairedAt,
        )
        pairedSenderStore.add(record)
        record
    }.onFailure {
        if (it is HttpException) Timber.tag(TAG).w("complete pairing http %d", it.code())
        else Timber.tag(TAG).e(it, "complete pairing error")
    }

    suspend fun revoke(pairingId: String): Result<Unit> = runCatching {
        val response = api.revokePairing(pairingId)
        if (!response.isSuccessful) {
            throw HttpException(response)
        }
        pairedSenderStore.remove(pairingId)
        Unit
    }.onFailure {
        Timber.tag(TAG).e(it, "revoke pairing %s failed", pairingId)
    }

    private companion object {
        const val TAG = "PairingRepo"
    }
}

data class PairingCandidate(
    val brokerUrl: String,
    val pairingToken: String,
)
