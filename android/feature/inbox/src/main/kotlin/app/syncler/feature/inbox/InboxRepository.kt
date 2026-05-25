package app.syncler.feature.inbox

import app.syncler.core.auth.DeviceIdentityStore
import app.syncler.core.auth.Session
import app.syncler.core.crypto.Aead
import app.syncler.core.crypto.Hpke
import app.syncler.core.crypto.Signing
import app.syncler.core.crypto.V2Aad
import app.syncler.core.crypto.base64ToBytes
import app.syncler.core.network.InboxFeedItemDto
import app.syncler.core.network.InboxFeedResponseDto
import app.syncler.core.network.MessageInboxItemDto
import app.syncler.core.network.RecipientEnvelopeDto
import app.syncler.core.network.SynclerApi
import app.syncler.core.network.TemplateBlockDto
import app.syncler.core.storage.DeviceEncryptionKeyStore
import app.syncler.core.storage.PairedSender
import app.syncler.core.storage.PairedSenderStore
import app.syncler.core.storage.compareIsoTimestamps
import app.syncler.feature.inbox.BuildConfig
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Pulls messages from /v1/messages/inbox on demand, decrypts each one with the
 * matching paired sender's pairing key, and (lazily) downloads the plugin
 * bundle so the inbox UI can render it via a WebView.
 *
 * Bundle cache lives for the process lifetime; on app restart we re-fetch.
 * That's fine for V1 ג€” caching across launches is an M11+ refinement that
 * needs invalidation tied to plugin version updates.
 */
@Singleton
class InboxRepository @Inject constructor(
    private val api: SynclerApi,
    private val session: Session,
    private val pairedSenderStore: PairedSenderStore,
    private val deviceIdentityStore: DeviceIdentityStore,
    private val deviceEncryptionKeyStore: DeviceEncryptionKeyStore,
) {
    private val _items = MutableStateFlow<List<InboxItem>>(emptyList())
    val items: StateFlow<List<InboxItem>> = _items.asStateFlow()

    private val bundleHttp = OkHttpClient.Builder()
        // Release builds refuse cleartext outright; debug allows it so devs
        // can host bundles on a LAN box. Matches PluginLoader.requireHttps.
        .connectionSpecs(
            if (BuildConfig.DEBUG) {
                listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
            } else {
                listOf(ConnectionSpec.MODERN_TLS)
            },
        )
        .build()
    /**
     * Bundle cache keyed by SHA-256 hash (lowercase hex). Multiple plugin
     * versions coexist: a new publish doesn't evict the old version, so old
     * messages keep rendering against the EXACT bundle they were originally
     * validated for (Codex's M11.3 concern: avoid history drift when a
     * plugin author publishes a render-incompatible v2).
     *
     * In-memory only in V1 ג€” survives process lifetime, lost on restart.
     * Disk persistence with size-based eviction is V1.5 (see also
     * `docs/integration-guide.md` ֲ§9 "Common errors" for the user-visible
     * "Plugin couldn't be re-fetched" path).
     */
    private val bundleByHash = mutableMapOf<String, CachedBundle>()
    private val pluginMutex = Mutex()

    private var lastSince: String? = null
    private val pollMutex = Mutex()

    suspend fun refresh(): Result<Int> = pollMutex.withLock {
        runCatching {
            val response = api.inbox(since = lastSince)
            val userId = session.currentUserId() ?: return@runCatching 0
            val pairingsBySender = pairedSenderStore.pairedSenders.value.groupBy { it.senderId }

            var sawMissingPairing = false
            // Triad 129 (Codex RED #2): if ANY event message fails
            // decrypt, hold the cursor at lastSince so the next poll
            // re-fetches it. Live cards aren't cursor-tracked
            // (they're fetched whole each poll) so this only affects
            // event flow.
            var sawDecryptFailure = false

            val newDecrypted = response.items.mapNotNull { dto ->
                val candidates = pairingsBySender[dto.senderId].orEmpty()
                if (candidates.isEmpty()) {
                    Timber.tag(TAG).w("no paired sender for senderId=%s; skipping", dto.senderId)
                    sawMissingPairing = true
                    return@mapNotNull null
                }

                // Triad 129 (Codex RED #1, spec §11.8): verify the
                // Ed25519 envelope signature against the trusted
                // paired sender's public key BEFORE consulting any
                // unsigned envelope field. Without this a malicious
                // server holding the device's public encryption key
                // could forge messages with any claimed sender_id.
                if (!verifyV2Signature(dto, candidates)) {
                    Timber.tag(TAG).w(
                        "rejected V2 envelope %s for sender %s: bad signature",
                        dto.id, dto.senderId,
                    )
                    if (dto.envelopeKind != "live_card_upsert") sawDecryptFailure = true
                    return@mapNotNull null
                }

                val decrypted = if (dto.envelopeKind == "live_card_upsert") {
                    decryptLiveCard(dto, userId, candidates)
                } else {
                    decryptEventMessage(dto, userId, candidates)
                }

                if (decrypted == null) {
                    Timber.tag(TAG).w(
                        "could not decrypt %s %s from sender %s",
                        dto.envelopeKind, dto.id, dto.senderId,
                    )
                    if (dto.envelopeKind != "live_card_upsert") sawDecryptFailure = true
                    return@mapNotNull null
                }

                // Resolve plugin bundle by historical row UUID (NOT by current
                // /latest) so old messages render against the exact bundle
                // they were validated for. Codex review 40: a sender publishing
                // v2 must not retroactively change v1 messages' render output.
                val plugin = fetchPluginByRowOrNull(dto.pluginId)
                decrypted.copy(
                    bundleJs = plugin?.bundleJs,
                    declaredEndpoints = plugin?.endpoints.orEmpty(),
                    renderer = plugin?.renderer ?: "script",
                    template = plugin?.template,
                    bundleHash = plugin?.bundleHashHex,
                    revocationReason = plugin?.revocationReason,
                )
            }

            if (newDecrypted.isNotEmpty()) {
                // Merge with existing items. Live cards overwrite by cardKey;
                // event messages merge by ID.
                val existing = _items.value
                val eventMessages = (newDecrypted.filter { it.type == "event" } + existing.filter { it.type == "event" })
                    .distinctBy { it.id }

                // Live cards: newer sequence number wins.
                val liveCards = (newDecrypted.filter { it.type == "live" } + existing.filter { it.type == "live" })
                    .groupBy { it.cardKey }
                    .map { (_, versions) ->
                        versions.sortedByDescending { it.sequenceNumber ?: -1 }[0]
                    }

                val merged = eventMessages + liveCards

                // Sort: live cards pinned above events.
                // Within events: sentAt descending.
                // Within live cards: updatedAt descending.
                _items.value = merged.sortedWith(
                    compareByDescending<InboxItem> { it.type == "live" }
                        .thenByDescending { if (it.type == "live") parseInstantOrNull(it.updatedAt ?: "") else parseInstantOrNull(it.sentAt) }
                        .thenByDescending { it.id },
                )
            }
            if (sawMissingPairing || sawDecryptFailure) {
                Timber.tag(TAG).w(
                    "holding lastSince at %s — sawMissingPairing=%s, sawDecryptFailure=%s; will re-fetch next refresh",
                    lastSince, sawMissingPairing, sawDecryptFailure,
                )
            } else {
                response.nextSince?.let { lastSince = it }
            }
            newDecrypted.size
        }.onFailure { Timber.tag(TAG).w(it, "refresh failed") }
    }

    /**
     * Revoke every active pairing for a sender. Mirrors `PairingRepository.revoke`
     * (which is the canonical path called from the device-management screen)
     * so the server-side tombstone always lands BEFORE the local synced
     * removal ג€” without that ordering, an offline device that hadn't sync'd
     * yet could resurrect the pairing on its next refresh, and the receiving
     * sender would keep being able to send messages we'd now decrypt.
     *
     * Closes Codex consultation 62 RED #7. Per-pairing errors are logged and
     * skipped so one network failure doesn't leave an inconsistent set of
     * pairings half-revoked; the next sheet-revoke retry picks them up.
     */
    suspend fun revokeSender(senderId: String) {
        val active = pairedSenderStore.activePairingsForSender(senderId)
        active.forEach { pairing ->
            val response = runCatching { api.revokePairing(pairing.pairingId) }
            response.onFailure {
                Timber.tag(TAG).w(it, "server revoke failed for pairing %s; keeping local", pairing.pairingId)
                return@forEach
            }
            val resp = response.getOrNull()
            if (resp == null || !resp.isSuccessful) {
                Timber.tag(TAG).w(
                    "server revoke for pairing %s returned %s; keeping local",
                    pairing.pairingId,
                    resp?.code()?.toString() ?: "no-response",
                )
                return@forEach
            }
            pairedSenderStore.remove(pairing.pairingId)
        }
    }

    suspend fun upsertCard(data: String) {
        val userId = session.currentUserId() ?: return
        val dto = runCatching {
            val moshi = com.squareup.moshi.Moshi.Builder()
                .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            moshi.adapter(InboxFeedItemDto::class.java).fromJson(data)
        }.getOrNull() ?: return

        val candidates = pairedSenderStore.pairedSenders.value.groupBy { it.senderId }[dto.senderId].orEmpty()
        if (candidates.isEmpty()) return

        val decrypted = decryptLiveCard(dto, userId, candidates) ?: return
        val plugin = fetchPluginByRowOrNull(dto.pluginId)
        val item = decrypted.copy(
            bundleJs = plugin?.bundleJs,
            declaredEndpoints = plugin?.endpoints.orEmpty(),
            renderer = plugin?.renderer ?: "script",
            template = plugin?.template,
            bundleHash = plugin?.bundleHashHex,
            revocationReason = plugin?.revocationReason,
        )

        _items.value = pollMutex.withLock {
            val existing = _items.value
            val currentCard = existing.find { it.type == "live" && it.cardKey == item.cardKey && it.senderId == item.senderId }

            if (currentCard != null && (item.sequenceNumber ?: 0L) <= (currentCard.sequenceNumber ?: 0L)) {
                return@withLock existing
            }

            val filtered = if (currentCard != null) existing.filter { it !== currentCard } else existing
            (filtered + item).sortedWith(
                compareByDescending<InboxItem> { it.type == "live" }
                    .thenByDescending { if (it.type == "live") parseInstantOrNull(it.updatedAt ?: "") else parseInstantOrNull(it.sentAt) }
                    .thenByDescending { it.id },
            )
        }
    }

    suspend fun deleteCard(senderId: String, cardKey: String) {
        _items.value = pollMutex.withLock {
            _items.value.filterNot { it.type == "live" && it.senderId == senderId && it.cardKey == cardKey }
        }
    }

    /**
     * Fetches the historical manifest for `pluginRowId` via the by-id
     * endpoint, verifies the bundle, caches bytes BY HASH. The row UUID
     * resolves to the exact bundle that was active when the message was
     * sent ג€” survives later sender publishes AND surfaces revocation
     * state (silent for ``superseded``, alert for ``compromised``, etc.).
     *
     * Already-cached hashes short-circuit. If the row's revocation_reason
     * is ``compromised``, refuse to load the bundle ג€” the render path
     * shows the security warning instead.
     */
    private suspend fun fetchPluginByRowOrNull(pluginRowId: String): CachedBundle? {
        return runCatching {
            val manifest = api.getPluginById(pluginRowId = pluginRowId)

            // Hard refusal: compromised plugins MUST NOT execute regardless
            // of cache state. Surface the reason on the InboxItem; the UI
            // shows a security banner instead of the WebView.
            if (manifest.revocationReason == "compromised") {
                return@runCatching CachedBundle(
                    bundleJs = null,
                    endpoints = emptyList(),
                    bundleHashHex = null,
                    revocationReason = manifest.revocationReason,
                    renderer = manifest.renderer,
                    template = manifest.template,
                )
            }

            // Phase 3a: template-renderer plugins ship NO bundle. Short-circuit
            // the bundle-fetch path entirely ג€” there's nothing to download or
            // verify by hash, and the WebView path is never used. The
            // server-side validator already enforced (layout, JSONPath, action
            // endpoint גˆˆ declaredEndpoints) at publish time, so the client
            // can trust the manifest's template block as-is.
            if (manifest.renderer == "template") {
                return@runCatching CachedBundle(
                    bundleJs = null,
                    endpoints = manifest.endpoints,
                    bundleHashHex = null,
                    revocationReason = manifest.revocationReason,
                    renderer = manifest.renderer,
                    template = manifest.template,
                )
            }

            val expectedHashBytes = runCatching { manifest.bundleHash.base64ToBytes() }.getOrNull()
                ?: error("plugin bundle_hash is not valid base64")
            val expectedHashHex = expectedHashBytes.joinToString("") { "%02x".format(it) }

            // Cache hit by hash ג€” same plugin version we already verified.
            pluginMutex.withLock { bundleByHash[expectedHashHex] }?.let { existing ->
                // Refresh revocation_reason on cache hit so a newly-revoked
                // plugin gets its banner even if its bytes were cached previously.
                return@runCatching existing.copy(
                    revocationReason = manifest.revocationReason,
                    renderer = manifest.renderer,
                    template = manifest.template,
                )
            }

            // Scheme check. Release: HTTPS only. Debug: any HTTP for LAN dev.
            val url = manifest.signedBundleUrl
            val schemeOk = url.startsWith("https://") || (BuildConfig.DEBUG && url.startsWith("http://"))
            if (!schemeOk) error("plugin bundle URL must be HTTPS (got $url)")

            val bundleBytes = withContext(Dispatchers.IO) {
                bundleHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("plugin bundle HTTP ${resp.code}")
                    resp.body?.bytes() ?: error("empty plugin bundle body")
                }
            }
            // SHA-256(bundle) must equal the server-published bundle_hash.
            val actualHash = MessageDigest.getInstance("SHA-256").digest(bundleBytes)
            if (!actualHash.contentEquals(expectedHashBytes)) {
                error("plugin bundle hash mismatch (CDN/MITM substitution?)")
            }

            val cached = CachedBundle(
                bundleJs = bundleBytes.toString(Charsets.UTF_8),
                endpoints = manifest.endpoints,
                bundleHashHex = expectedHashHex,
                revocationReason = manifest.revocationReason,
                renderer = manifest.renderer,
                template = manifest.template,
            )
            pluginMutex.withLock { bundleByHash[expectedHashHex] = cached }
            cached
        }.onFailure {
            Timber.tag(TAG).w(it, "plugin fetch failed for row %s", pluginRowId)
        }.getOrNull()
    }

    /**
     * The cache entry for a verified plugin bundle. `bundleJs` is null when
     * the bundle is refused (compromised revocation); the UI uses
     * `revocationReason` to decide what banner to show in that case.
     */
    data class CachedBundle(
        val bundleJs: String?,
        val endpoints: List<String>,
        val bundleHashHex: String?,
        val revocationReason: String?,
        /**
         * Phase 3a. "script" (use bundleJs in a WebView) or "template" (use
         * `template` with the native Compose [TemplateCard] renderer). Falls
         * back to "script" when the server doesn't supply a value.
         */
        val renderer: String = "script",
        /**
         * Phase 3a. The template manifest block. Non-null only when
         * `renderer == "template"`; the server validator enforces the pairing
         * at publish time.
         */
        val template: TemplateBlockDto? = null,
    )

    /**
     * Phase 9b V2 event-message decrypt (spec §11.4). Looks up THIS
     * device's recipient_envelope in `dto.recipientEnvelopes`,
     * HPKE-opens the wrapped CEK, then AES-GCM-decrypts the payload.
     * The candidates list is consulted only for the sender display
     * name — V1's pairing_key candidate loop is gone.
     */
    private fun decryptEventMessage(
        dto: InboxFeedItemDto,
        userId: String,
        candidates: List<PairedSender>,
    ): InboxItem? {
        val senderName = candidates
            .sortedWith { a, b -> compareIsoTimestamps(b.firstPairedAt, a.firstPairedAt) }
            .firstOrNull()?.senderName.orEmpty()

        val plaintextString = openV2EnvelopeOrNull(
            dto = dto,
            hpkeInfoFactory = { deviceId ->
                V2Aad.eventHpkeInfo(
                    senderId = dto.senderId,
                    userId = userId,
                    pluginId = dto.pluginId,
                    expiresAt = dto.expiresAt,
                    minPluginVersion = dto.minPluginVersion ?: "",
                    payloadNonceB64 = dto.payloadNonce,
                    payloadCiphertextSha256Hex = Hpke.sha256Hex(
                        dto.payloadCiphertext.base64ToBytes()
                    ),
                    deviceId = deviceId,
                )
            },
            payloadAad = V2Aad.eventPayloadAad(
                senderId = dto.senderId,
                userId = userId,
                pluginId = dto.pluginId,
                expiresAt = dto.expiresAt,
                minPluginVersion = dto.minPluginVersion ?: "",
            ),
        ) ?: return null

        return InboxItem(
            id = dto.id,
            senderId = dto.senderId,
            senderName = senderName,
            pluginId = dto.pluginId,
            pluginIdentifier = dto.pluginIdentifier,
            payloadJson = plaintextString,
            sentAt = dto.sentAt ?: "",
            expiresAt = dto.expiresAt,
            type = "event",
            bundleJs = null,
            declaredEndpoints = emptyList(),
            bundleHash = null,
            revocationReason = null,
            renderer = "script",
            template = null,
            hostPreview = HostPreviewParser.parse(plaintextString),
        )
    }

    /** Phase 9b V2 live-card decrypt (spec §11.5). Same flow as event. */
    private fun decryptLiveCard(
        dto: InboxFeedItemDto,
        userId: String,
        candidates: List<PairedSender>,
    ): InboxItem? {
        val senderName = candidates
            .sortedWith { a, b -> compareIsoTimestamps(b.firstPairedAt, a.firstPairedAt) }
            .firstOrNull()?.senderName.orEmpty()
        val cardKey = dto.cardKey ?: return null
        val cardType = dto.cardType ?: return null
        val sequenceNumber = dto.sequenceNumber ?: return null

        val plaintextString = openV2EnvelopeOrNull(
            dto = dto,
            hpkeInfoFactory = { deviceId ->
                V2Aad.liveCardHpkeInfo(
                    senderId = dto.senderId,
                    userId = userId,
                    pluginId = dto.pluginId,
                    cardKey = cardKey,
                    cardType = cardType,
                    sequenceNumber = sequenceNumber,
                    expiresAt = dto.expiresAt,
                    minPluginVersion = dto.minPluginVersion ?: "",
                    payloadNonceB64 = dto.payloadNonce,
                    payloadCiphertextSha256Hex = Hpke.sha256Hex(
                        dto.payloadCiphertext.base64ToBytes()
                    ),
                    deviceId = deviceId,
                )
            },
            payloadAad = V2Aad.liveCardPayloadAad(
                senderId = dto.senderId,
                userId = userId,
                pluginId = dto.pluginId,
                cardKey = cardKey,
                cardType = cardType,
                sequenceNumber = sequenceNumber,
                expiresAt = dto.expiresAt,
                minPluginVersion = dto.minPluginVersion ?: "",
            ),
        ) ?: return null

        return InboxItem(
            id = dto.id,
            senderId = dto.senderId,
            senderName = senderName,
            pluginId = dto.pluginId,
            pluginIdentifier = dto.pluginIdentifier,
            payloadJson = plaintextString,
            sentAt = dto.updatedAt ?: dto.expiresAt,
            expiresAt = dto.expiresAt,
            type = "live",
            cardKey = dto.cardKey,
            sequenceNumber = dto.sequenceNumber,
            updatedAt = dto.updatedAt,
            bundleJs = null,
            declaredEndpoints = emptyList(),
            bundleHash = null,
            revocationReason = null,
            renderer = "script",
            template = null,
            hostPreview = HostPreviewParser.parse(plaintextString),
        )
    }

    /**
     * Phase 9b §11.8 + Triad 129 (Codex RED #1) — verify the
     * Ed25519 envelope signature against the trusted paired
     * sender's public key. MUST run before any other envelope
     * field is consulted for routing / HPKE info reconstruction.
     *
     * Returns true only if the signature is valid for any of the
     * paired sender records. Wraps in runCatching so a malformed
     * signature/key never bubbles up as a crash; the inbox refresh
     * just drops the bad item.
     */
    private fun verifyV2Signature(
        dto: InboxFeedItemDto,
        candidates: List<PairedSender>,
    ): Boolean {
        val signatureBytes = runCatching { dto.envelopeSignature.base64ToBytes() }.getOrNull() ?: return false
        val sortedRecipients = dto.recipientEnvelopes
            .map { mapOf(
                "device_id" to it.deviceId.lowercase(),
                "hpke_ciphertext" to it.hpkeCiphertext,
                "hpke_kem_output" to it.hpkeKemOutput,
            ) }
            .sortedBy { it.getValue("device_id") }

        val canonicalBytes: ByteArray = when (dto.envelopeKind) {
            "event" -> V2Aad.eventSignedEnvelopeBytes(
                senderId = dto.senderId,
                userId = session.currentUserId() ?: return false,
                pluginId = dto.pluginId,
                expiresAt = dto.expiresAt,
                minPluginVersion = dto.minPluginVersion ?: "",
                payloadNonceB64 = dto.payloadNonce,
                payloadCiphertextB64 = dto.payloadCiphertext,
                recipientEnvelopesSerialized = sortedRecipients,
                recipientDirectoryVersion = dto.recipientDirectoryVersion,
            )
            "live_card_upsert" -> V2Aad.liveCardUpsertSignedEnvelopeBytes(
                senderId = dto.senderId,
                userId = session.currentUserId() ?: return false,
                pluginId = dto.pluginId,
                cardKey = dto.cardKey ?: return false,
                cardType = dto.cardType ?: return false,
                sequenceNumber = dto.sequenceNumber ?: return false,
                expiresAt = dto.expiresAt,
                minPluginVersion = dto.minPluginVersion ?: "",
                payloadNonceB64 = dto.payloadNonce,
                payloadCiphertextB64 = dto.payloadCiphertext,
                recipientEnvelopesSerialized = sortedRecipients,
                recipientDirectoryVersion = dto.recipientDirectoryVersion,
            )
            else -> return false  // unknown envelope_kind
        }

        // Try every paired sender record's public key — a sender
        // pairing rotation could leave multiple active records;
        // any matching verify passes. The senderId on the DTO
        // already narrowed the candidate list to records for that
        // sender, so this is a no-op in steady state.
        return candidates.any { paired ->
            runCatching {
                Signing.verify(paired.senderPublicKey, canonicalBytes, signatureBytes)
            }.getOrDefault(false)
        }
    }

    /**
     * Phase 9b V2 shared open path. Looks up THIS device's recipient
     * envelope by device_id, HPKE-opens the wrapped CEK, then
     * AES-256-GCM-decrypts the payload. Returns the plaintext JSON
     * string, or null if the device has no envelope on this item OR
     * the open / AEAD fails (logged, treated as undecryptable).
     */
    private fun openV2EnvelopeOrNull(
        dto: InboxFeedItemDto,
        hpkeInfoFactory: (deviceId: String) -> ByteArray,
        payloadAad: ByteArray,
    ): String? {
        val deviceId = deviceIdentityStore.read() ?: run {
            Timber.tag(TAG).w("no device_id on this device; cannot decrypt V2 envelope")
            return null
        }
        val ownEnvelope = dto.recipientEnvelopes.firstOrNull {
            it.deviceId.equals(deviceId, ignoreCase = true)
        }
        if (ownEnvelope == null) {
            Timber.tag(TAG).d(
                "no recipient_envelope for device %s on item %s",
                deviceId, dto.id,
            )
            return null
        }

        val keypair = deviceEncryptionKeyStore.getOrCreateKeypair()
        val cek = runCatching {
            Hpke.openCekForDevice(
                privateKeyRaw = keypair.privateKey,
                hpkeKemOutput = ownEnvelope.hpkeKemOutput.base64ToBytes(),
                hpkeCiphertext = ownEnvelope.hpkeCiphertext.base64ToBytes(),
                info = hpkeInfoFactory(deviceId),
            )
        }.getOrElse {
            Timber.tag(TAG).w(it, "HPKE open failed for item %s", dto.id)
            return null
        }

        return runCatching {
            val nonce = dto.payloadNonce.base64ToBytes()
            val ciphertext = dto.payloadCiphertext.base64ToBytes()
            val plaintext = Aead.decrypt(cek, nonce + ciphertext, payloadAad)
            plaintext.toString(Charsets.UTF_8)
        }.getOrElse {
            Timber.tag(TAG).w(it, "AES-GCM decrypt failed for item %s", dto.id)
            null
        }
    }

    private fun parseInstantOrNull(s: String): Instant? =
        runCatching { Instant.parse(s) }.getOrNull()

    companion object {
        const val TAG = "InboxRepo"
    }
}

data class InboxItem(
    val id: String,
    val senderId: String,
    val senderName: String,
    val pluginId: String,
    val pluginIdentifier: String,
    val payloadJson: String,
    val sentAt: String,
    val expiresAt: String,
    /**
     * Type: "event" (one-off message) or "live" (persistent upsertable card).
     */
    val type: String = "event",
    /**
     * Stable identifier for live cards. Null for event messages.
     */
    val cardKey: String? = null,
    /**
     * Sequence number for live cards (M11.5). Null for event messages.
     */
    val sequenceNumber: Long? = null,
    /**
     * Last update time for live cards. Null for event messages.
     */
    val updatedAt: String? = null,
    /**
     * Plugin JS bundle source. Null when the fetch is in-flight or failed ג€”
     * the UI surfaces a fallback error card in that case (still pullable from
     * the inbox; detail view can retry).
     */
    val bundleJs: String?,
    /**
     * Endpoints the plugin's manifest declared (glob patterns like
     * `https://lottery.app/api/...`). The card's network bridge refuses
     * `platform.network.fetch` calls to URLs that don't match any pattern.
     */
    val declaredEndpoints: List<String>,
    /**
     * SHA-256 (lowercase hex) of the bundle this message was originally
     * validated against (M11.4). Null until a successful fetch. The render
     * path uses this hash to look up the cached bundle, so old messages keep
     * rendering against the bundle they were verified for even after the
     * plugin author publishes a render-incompatible new version.
     */
    val bundleHash: String?,
    /**
     * Revocation classification when the plugin row this message references
     * has been pulled. One of: ``superseded`` (silent ג€” UX may show subdued
     * banner), ``compromised`` (refuse to render; show security warning),
     * ``sender_disabled`` (render with "no longer available" banner),
     * ``unspecified`` (pre-M11.4 legacy revoke ג€” treat conservatively).
     * Null on active rows.
     */
    val revocationReason: String?,
    /**
     * Phase 3a. "script" (legacy WebView bundle path) or "template" (native
     * Compose renderer via [TemplateCard]). Defaults to "script" so any code
     * that hasn't been updated for Phase 3a still picks the WebView path.
     */
    val renderer: String = "script",
    /**
     * Phase 3a. The template manifest. Non-null iff `renderer == "template"`;
     * the publish-time server validator enforces the pairing so the dispatch
     * site can trust this invariant.
     */
    val template: TemplateBlockDto? = null,
    /**
     * Structured row metadata extracted from the decrypted payload's reserved
     * `hostPreview` key. Null when the sender didn't supply one or the block
     * was malformed (logged and ignored ג€” the row falls back to a generic
     * "New message from {senderName}" rendering).
     */
    val hostPreview: HostPreview?,
)

/**
 * Mirror of sdk-plugin/HostPreview. Senders embed this under the reserved
 * `hostPreview` key of the encrypted payload; the host parses it post-decrypt
 * to render the inbox row natively without invoking the plugin's `render()`.
 *
 * `searchText` is folded into the host's global search index in addition to
 * the visible fields. The plugin's `render(payload)` (detail view) still
 * receives the entire decrypted payload, including the hostPreview block.
 */
data class HostPreview(
    val title: String,
    val subtitle: String?,
    val summary: String?,
    val searchText: List<String>,
)
