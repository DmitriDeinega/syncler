package app.syncler.feature.pairing

import android.net.Uri
import android.util.Base64
import app.syncler.core.network.PairingCompleteRequestDto
import app.syncler.core.network.PairingPreviewResponseDto
import app.syncler.core.network.SynclerApi
import app.syncler.core.storage.PairedSender
import app.syncler.core.storage.PairedSenderStore
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException
import timber.log.Timber

/**
 * Two-phase pairing flow (M6.1 fix per Codex+Gemini review):
 *
 *   1. parseBrokerUrl(url) -> PairingCandidate
 *   2. preview(candidate) -> PairingPreviewResponseDto  [non-consuming]
 *   3. user confirms fingerprint manually
 *   4. confirm(candidate, preview, encryptedInitialState) -> PairedSender
 *      ONLY at this point does the server consume the token and we
 *      persist the local PairedSender record.
 *
 * cancel(candidate) is a no-op server-side (the token is left un-consumed
 * and will expire on its TTL). Local state is never written until confirm.
 */
@Singleton
class PairingRepository @Inject constructor(
    private val api: SynclerApi,
    private val pairedSenderStore: PairedSenderStore,
) {
    fun parseBrokerUrl(url: String): PairingCandidate? {
        if (!url.startsWith("https://")) {
            Timber.tag(TAG).w("non-https broker URL rejected")
            return null
        }
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val token = uri.getQueryParameter("token") ?: return null
        return PairingCandidate(brokerUrl = url, pairingToken = token)
    }

    suspend fun preview(candidate: PairingCandidate): Result<PairingPreviewResponseDto> =
        runCatching { api.previewPairing(candidate.pairingToken) }
            .onFailure {
                if (it is HttpException) Timber.tag(TAG).w("preview http %d", it.code())
                else Timber.tag(TAG).e(it, "preview error")
            }

    suspend fun confirm(
        candidate: PairingCandidate,
        preview: PairingPreviewResponseDto,
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
        // Defense-in-depth: confirm the server's complete-response identity
        // matches the preview the user manually confirmed across ALL identity
        // fields (sender_id + public_key + fingerprint + name + name_hash).
        // A backend bug or mid-stream swap that mutated even just the name
        // would slip through a partial check.
        if (response.senderId != preview.senderId ||
            response.senderPublicKey != preview.senderPublicKey ||
            response.senderPublicKeyFingerprint != preview.senderPublicKeyFingerprint ||
            response.senderName != preview.senderName ||
            response.senderNameHash != preview.senderNameHash
        ) {
            Timber.tag(TAG).e("preview/complete identity mismatch — revoking and aborting")
            val revoked = runCatching { api.revokePairing(response.pairingId) }
            revoked.onFailure { Timber.tag(TAG).e(it, "failed to revoke after mismatch — manual cleanup required") }
            if (revoked.isSuccess && revoked.getOrNull()?.isSuccessful == false) {
                Timber.tag(TAG).e("revoke returned non-2xx after mismatch — manual cleanup required")
            }
            throw IllegalStateException("preview/complete identity mismatch")
        }

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
    }.onFailure { Timber.tag(TAG).e(it, "confirm error") }

    suspend fun revoke(pairingId: String): Result<Unit> = runCatching {
        val response = api.revokePairing(pairingId)
        if (!response.isSuccessful) throw HttpException(response)
        pairedSenderStore.remove(pairingId)
        Unit
    }.onFailure { Timber.tag(TAG).e(it, "revoke pairing %s failed", pairingId) }

    private companion object {
        const val TAG = "PairingRepo"
    }
}

data class PairingCandidate(
    val brokerUrl: String,
    val pairingToken: String,
)
