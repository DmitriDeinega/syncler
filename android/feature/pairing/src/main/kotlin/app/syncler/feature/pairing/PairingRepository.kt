package app.syncler.feature.pairing

import android.net.Uri
import android.util.Base64
import app.syncler.core.crypto.BootstrapEnvelope
import app.syncler.core.crypto.Signing
import app.syncler.core.network.BootstrapEnvelopeDto
import app.syncler.core.network.BrokerApi
import app.syncler.core.network.PairingCompleteRequestDto
import app.syncler.core.network.PairingPreviewResponseDto
import app.syncler.core.network.SynclerApi
import app.syncler.core.storage.PairedSender
import app.syncler.core.storage.PairedSenderStore
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import org.json.JSONObject
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
    private val brokerApi: BrokerApi,
    private val pairedSenderStore: PairedSenderStore,
    private val session: app.syncler.core.auth.Session,
) {
    fun parseBrokerUrl(url: String): PairingCandidate? {
        // Debug builds accept plain http:// so devs can pair against a local
        // broker without TLS. Release builds require HTTPS.
        val schemeOk = url.startsWith("https://") || (BuildConfig.DEBUG && url.startsWith("http://"))
        if (!schemeOk) {
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
        pairingKey: ByteArray,
        encryptedInitialState: ByteArray,
    ): Result<PairedSender> = runCatching {
        val response = api.completePairing(
            PairingCompleteRequestDto(
                pairingToken = candidate.pairingToken,
                encryptedInitialState = Base64.encodeToString(
                    encryptedInitialState,
                    Base64.NO_WRAP,
                ),
                // Phase 8 §10.5 — bind the encrypted_initial_state AAD
                // to the locked-server generation. Server 409s on
                // mismatch so the client knows to refetch.
                keyGenerationObserved = session.currentKeyGeneration(),
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
            pairingKey = pairingKey,
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

    // ------------------------ V1.5 automated pairing ------------------------

    /**
     * Inspect a preview for V1.5 automated-pairing metadata. The
     * server returns all four bootstrap fields if-and-only-if the
     * sender has registered a bootstrap key.
     */
    fun classifyBootstrap(preview: PairingPreviewResponseDto): BootstrapClassification {
        val keyB64 = preview.bootstrapKey
        val sigB64 = preview.bootstrapKeySignature
        val brokerUrl = preview.senderBrokerUrl
        val version = preview.bootstrapProtocolVersion
        val nullCount = listOf(keyB64, sigB64, brokerUrl, version).count { it == null }
        if (nullCount == 4) return BootstrapClassification.Manual
        if (nullCount != 0) {
            // Partial = malformed metadata = substitution-attack
            // indicator. Hard refusal — DO NOT silently fall back.
            return BootstrapClassification.HardError(
                "incomplete automated-pairing metadata from server (got $nullCount of 4 nulls)",
            )
        }
        if (version != 1) {
            return BootstrapClassification.HardError(
                "unsupported bootstrap_protocol_version: $version (this app supports 1)",
            )
        }
        // Decode key + signature, check exact byte lengths.
        val keyRaw = runCatching { Base64.decode(keyB64, Base64.NO_WRAP) }.getOrNull()
            ?: return BootstrapClassification.HardError("bootstrap_key is not valid base64")
        if (keyRaw.size != 32) {
            return BootstrapClassification.HardError("bootstrap_key must decode to 32 bytes (got ${keyRaw.size})")
        }
        val sigRaw = runCatching { Base64.decode(sigB64, Base64.NO_WRAP) }.getOrNull()
            ?: return BootstrapClassification.HardError("bootstrap_key_signature is not valid base64")
        if (sigRaw.size != 64) {
            return BootstrapClassification.HardError("bootstrap_key_signature must decode to 64 bytes (got ${sigRaw.size})")
        }
        // Broker URL: require https:// in release, allow http:// in debug.
        val schemeOk = brokerUrl!!.startsWith("https://") ||
            (BuildConfig.DEBUG && brokerUrl.startsWith("http://"))
        if (!schemeOk) {
            return BootstrapClassification.HardError("sender_broker_url scheme is not allowed: $brokerUrl")
        }
        return BootstrapClassification.Automated(
            bootstrapKeyRaw = keyRaw,
            bootstrapKeySignatureRaw = sigRaw,
            senderBrokerUrl = brokerUrl,
        )
    }

    /**
     * Verify the sender's bootstrap_key signature against the sender's
     * Ed25519 pub key (which the user just confirmed by fingerprint).
     * The signed input is `"syncler-v1-bootstrap-key:" || raw_x25519_pub`.
     * Defeats syncler-server bootstrap-key substitution.
     */
    fun verifyBootstrapKeySignature(
        senderPublicKeyEd25519Raw: ByteArray,
        bootstrapKeyRaw: ByteArray,
        bootstrapKeySignatureRaw: ByteArray,
    ): Boolean {
        val input = BOOTSTRAP_KEY_SIG_PREFIX + bootstrapKeyRaw
        return Signing.verify(senderPublicKeyEd25519Raw, input, bootstrapKeySignatureRaw)
    }

    /**
     * Build + POST the bootstrap envelope to the sender's broker URL.
     * Retries on transient failures only — network I/O exceptions and
     * HTTP 5xx — up to a total of [maxAttempts] attempts with bounded
     * backoff. ALL 4xx responses (400/401/409/429/other) are terminal
     * in V1.5; honoring `Retry-After` on 429 is a documented V2
     * refinement (see docs/integration-guide.md §8.5 "Failure modes").
     *
     * Returns success on 2xx, failure on any non-retryable status or
     * after retries are exhausted. The caller distinguishes the
     * "BootstrapSucceeded" state from the "BootstrapFailedFallback"
     * state from this Result.
     */
    suspend fun postBootstrapEnvelope(
        senderBrokerUrl: String,
        envelope: BootstrapEnvelopeDto,
        maxAttempts: Int = 3,
        backoffMillis: List<Long> = listOf(250L, 750L),
    ): Result<Unit> {
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                val response = brokerApi.postBootstrapEnvelope(senderBrokerUrl, envelope)
                if (response.isSuccessful) {
                    return Result.success(Unit)
                }
                val code = response.code()
                // V1.5: treat ALL 4xx as terminal (no retry), including
                // 429. Honoring 429 with Retry-After is a documented V2
                // refinement — see docs/integration-guide.md §8.5
                // "Failure modes" + crypto-spec §9 deferred work.
                if (code in 400..499) {
                    return Result.failure(HttpException(response))
                }
                lastError = HttpException(response) // 5xx — will retry
            } catch (e: IOException) {
                // Network / DNS / timeout — retryable.
                lastError = e
            }
            if (attempt < maxAttempts) {
                val delayMs = backoffMillis.getOrNull(attempt - 1) ?: backoffMillis.last()
                delay(delayMs)
            }
        }
        return Result.failure(lastError ?: IOException("broker POST failed after $maxAttempts attempts"))
    }

    /**
     * Build the bootstrap envelope from the validated automated
     * metadata and locally-known (user_id, pairing_key). Hands the
     * crypto off to [BootstrapEnvelope.build]; this wrapper just
     * assembles the wire DTO with base64-encoded fields.
     */
    fun buildEnvelopeDto(
        automated: BootstrapClassification.Automated,
        pairingId: String,
        senderId: String,
        userId: String,
        pairingKey: ByteArray,
    ): BootstrapEnvelopeDto {
        require(pairingKey.size == 32) { "pairingKey must be 32 bytes, got ${pairingKey.size}" }
        // exp = now + 60s, ISO8601 UTC with Z suffix (not +00:00) — spec §9.
        val expIso = DateTimeFormatter.ISO_INSTANT.format(
            Instant.now().plusSeconds(60),
        )
        // Canonical plaintext JSON: {"user_id": "...", "pairing_key": "<b64>"} sorted keys.
        val plaintext = JSONObject()
            .put("pairing_key", Base64.encodeToString(pairingKey, Base64.NO_WRAP))
            .put("user_id", userId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val envelope = BootstrapEnvelope.build(
            senderBootstrapPub = automated.bootstrapKeyRaw,
            pairingId = pairingId,
            senderId = senderId,
            senderBrokerUrl = automated.senderBrokerUrl,
            expIso = expIso,
            plaintext = plaintext,
        )
        return BootstrapEnvelopeDto(
            protocolVersion = 1,
            pairingId = pairingId,
            senderId = senderId,
            bootstrapKeyId = envelope.bootstrapKeyIdB64,
            exp = expIso,
            ephemeralPubkey = Base64.encodeToString(envelope.ephemeralPubkey, Base64.NO_WRAP),
            nonce = Base64.encodeToString(envelope.nonce, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
        )
    }

    private companion object {
        const val TAG = "PairingRepo"
        // Domain-separated input the bootstrap_key_signature is computed
        // over. Mirror of Phase 5a-1 spec §9 + Python's `Client.register_bootstrap_key`.
        private val BOOTSTRAP_KEY_SIG_PREFIX = "syncler-v1-bootstrap-key:".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * Result of inspecting a preview for V1.5 automated-pairing metadata.
 */
sealed interface BootstrapClassification {
    /** No automated metadata (all four fields null). V1 manual flow. */
    data object Manual : BootstrapClassification

    /** All four fields present and well-formed. Take the automated path. */
    data class Automated(
        val bootstrapKeyRaw: ByteArray,
        val bootstrapKeySignatureRaw: ByteArray,
        val senderBrokerUrl: String,
    ) : BootstrapClassification {
        // ByteArray equality requires content* methods.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Automated) return false
            return bootstrapKeyRaw.contentEquals(other.bootstrapKeyRaw) &&
                bootstrapKeySignatureRaw.contentEquals(other.bootstrapKeySignatureRaw) &&
                senderBrokerUrl == other.senderBrokerUrl
        }

        override fun hashCode(): Int {
            var result = bootstrapKeyRaw.contentHashCode()
            result = 31 * result + bootstrapKeySignatureRaw.contentHashCode()
            result = 31 * result + senderBrokerUrl.hashCode()
            return result
        }
    }

    /**
     * Metadata is malformed or partial. Substitution-attack indicator
     * — hard refusal. Do NOT silently fall back to manual.
     */
    data class HardError(val message: String) : BootstrapClassification
}

data class PairingCandidate(
    val brokerUrl: String,
    val pairingToken: String,
)
