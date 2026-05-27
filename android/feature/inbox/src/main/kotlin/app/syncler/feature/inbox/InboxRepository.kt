package app.syncler.feature.inbox

import app.syncler.core.auth.DeviceIdentityStore
import app.syncler.core.auth.Session
import app.syncler.core.crypto.Aead
import app.syncler.core.crypto.Hpke
import app.syncler.core.crypto.Signing
import app.syncler.core.crypto.V2Aad
import app.syncler.core.crypto.base64ToBytes
import app.syncler.core.network.CardPatchEntryDto
import app.syncler.core.network.CardPatchEnvelopeDto
import app.syncler.core.network.InboxFeedItemDto
import app.syncler.core.network.InboxFeedResponseDto
import app.syncler.core.network.LivePatchSink
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
) : LivePatchSink {
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

                // V3 #16 catch-up: replay any patches the
                // server inlined for this live card. Patches
                // arrive sorted by patch_seq for the current
                // base_seq; apply them in order on top of the
                // freshly decrypted upsert payload.
                val withPatches = if (dto.envelopeKind == "live_card_upsert") {
                    applyCatchUpPatches(decrypted, dto.patches.orEmpty())
                } else {
                    decrypted
                }

                // Resolve plugin bundle by historical row UUID (NOT by current
                // /latest) so old messages render against the exact bundle
                // they were validated for. Codex review 40: a sender publishing
                // v2 must not retroactively change v1 messages' render output.
                val plugin = fetchPluginByRowOrNull(dto.pluginId)
                withPatches.copy(
                    bundleJs = plugin?.bundleJs,
                    declaredEndpoints = plugin?.endpoints.orEmpty(),
                    renderer = plugin?.renderer ?: "script",
                    template = plugin?.template,
                    bundleHash = plugin?.bundleHashHex,
                    revocationReason = plugin?.revocationReason,
                    sensitivity = plugin?.sensitivity ?: "public",
                    iconUrl = plugin?.iconUrl,
                    iconVisibility = plugin?.iconVisibility ?: "always",
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
            sensitivity = plugin?.sensitivity ?: "public",
            iconUrl = plugin?.iconUrl,
            iconVisibility = plugin?.iconVisibility ?: "always",
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
     * V3 #16 — entry point for incoming `card.patch` envelopes
     * arriving on the live channel. LiveBridge routes
     * card_patch frames here instead of fanning them out to
     * the plugin so the host's InboxItem state stays
     * authoritative.
     *
     * Spec: docs/live-card-patch.md. The decrypt + sequence-
     * check + replace-op-apply contract is identical to the
     * inbox-catch-up path; this just adapts the wire JSON
     * into a [CardPatchEnvelopeDto].
     *
     * Failures (parse, signature, decrypt, stale/gap seq,
     * unknown path) are logged and swallowed — patches fail
     * closed, never partially mutate the card.
     */
    override suspend fun acceptCardPatch(envelopeJson: String) {
        val dto = runCatching {
            val moshi = com.squareup.moshi.Moshi.Builder()
                .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            moshi.adapter(CardPatchEnvelopeDto::class.java).fromJson(envelopeJson)
        }.getOrNull() ?: run {
            Timber.tag(TAG).w("dropping malformed card_patch envelope")
            return
        }
        applyPatchDto(dto)
    }

    /**
     * V3 #16 — shared apply path used by both the live-channel
     * sink (acceptCardPatch) and the inbox catch-up loop
     * (refresh consuming `dto.patches`). Returns the patched
     * InboxItem when applied, null on any rejection (logged).
     *
     * Atomic semantics per spec privacy invariant: ANY failure
     * (sequence rejection, signature mismatch, decrypt failure,
     * unknown JSONPath in any op) discards the entire patch.
     * Local InboxItem state never sees partial mutation.
     */
    private suspend fun applyPatchDto(dto: CardPatchEnvelopeDto): InboxItem? {
        val userId = session.currentUserId() ?: return null
        if (dto.userId.equals(userId, ignoreCase = true).not()) {
            Timber.tag(TAG).w("dropping card_patch for foreign user %s", dto.userId)
            return null
        }

        val candidates = pairedSenderStore.pairedSenders.value
            .groupBy { it.senderId }[dto.senderId].orEmpty()
        if (candidates.isEmpty()) {
            Timber.tag(TAG).w("dropping card_patch: no paired sender %s", dto.senderId)
            return null
        }

        // Signature first — same invariant as event/upsert.
        val sortedRecipients = dto.recipientEnvelopes
            .map { mapOf(
                "device_id" to it.deviceId.lowercase(),
                "hpke_ciphertext" to it.hpkeCiphertext,
                "hpke_kem_output" to it.hpkeKemOutput,
            ) }
            .sortedBy { it.getValue("device_id") }
        val canonicalBytes = V2Aad.cardPatchSignedEnvelopeBytes(
            senderId = dto.senderId,
            userId = userId,
            pluginId = dto.pluginId,
            cardId = dto.cardId,
            baseSeq = dto.baseSeq,
            patchSeq = dto.patchSeq,
            payloadNonceB64 = dto.payloadNonce,
            payloadCiphertextB64 = dto.payloadCiphertext,
            recipientEnvelopesSerialized = sortedRecipients,
            recipientDirectoryVersion = dto.recipientDirectoryVersion,
        )
        val signatureBytes = runCatching { dto.envelopeSignature.base64ToBytes() }.getOrNull() ?: run {
            Timber.tag(TAG).w("card_patch bad signature encoding")
            return null
        }
        val sigOk = candidates.any { paired ->
            runCatching { Signing.verify(paired.senderPublicKey, canonicalBytes, signatureBytes) }
                .getOrDefault(false)
        }
        if (!sigOk) {
            Timber.tag(TAG).w("card_patch signature rejected for sender %s", dto.senderId)
            return null
        }

        return pollMutex.withLock {
            val existing = _items.value
            val card = existing.firstOrNull {
                it.type == "live" &&
                    it.senderId.equals(dto.senderId, ignoreCase = true) &&
                    it.pluginId.equals(dto.pluginId, ignoreCase = true) &&
                    it.id.equals(dto.cardId, ignoreCase = true)
            }
            if (card == null) {
                Timber.tag(TAG).d(
                    "card_patch %s/%s base=%d patch=%d arrived before upsert; dropping",
                    dto.cardId, dto.senderId, dto.baseSeq, dto.patchSeq,
                )
                return@withLock null
            }
            val currentBase = card.sequenceNumber ?: 0L
            val lastPatch = card.lastPatchSequence ?: 0L
            if (dto.baseSeq < currentBase) {
                Timber.tag(TAG).d("card_patch stale base_seq %d < %d", dto.baseSeq, currentBase)
                return@withLock null
            }
            if (dto.baseSeq > currentBase) {
                Timber.tag(TAG).w(
                    "card_patch ahead of base (got %d, have %d); refresh needed",
                    dto.baseSeq, currentBase,
                )
                return@withLock null
            }
            if (dto.patchSeq <= lastPatch) {
                Timber.tag(TAG).d("card_patch replay/late: %d <= %d", dto.patchSeq, lastPatch)
                return@withLock null
            }
            if (dto.patchSeq != lastPatch + 1L) {
                Timber.tag(TAG).w(
                    "card_patch gap: expected %d, got %d; refresh needed",
                    lastPatch + 1L, dto.patchSeq,
                )
                return@withLock null
            }

            // Decrypt the per-recipient HPKE-sealed CEK +
            // AES-GCM payload — same shape as event/live_card
            // open path but with the card_patch AAD/info.
            val deviceId = deviceIdentityStore.read() ?: return@withLock null
            val ownEnv = dto.recipientEnvelopes.firstOrNull {
                it.deviceId.equals(deviceId, ignoreCase = true)
            } ?: run {
                Timber.tag(TAG).d("card_patch has no envelope for this device")
                return@withLock null
            }
            val payloadNonceB64 = dto.payloadNonce
            val payloadCiphertextSha256Hex = Hpke.sha256Hex(
                dto.payloadCiphertext.base64ToBytes()
            )
            val keypair = deviceEncryptionKeyStore.getOrCreateKeypair()
            val cek = runCatching {
                Hpke.openCekForDevice(
                    privateKeyRaw = keypair.privateKey,
                    hpkeKemOutput = ownEnv.hpkeKemOutput.base64ToBytes(),
                    hpkeCiphertext = ownEnv.hpkeCiphertext.base64ToBytes(),
                    info = V2Aad.cardPatchHpkeInfo(
                        senderId = dto.senderId,
                        userId = userId,
                        pluginId = dto.pluginId,
                        cardId = dto.cardId,
                        baseSeq = dto.baseSeq,
                        patchSeq = dto.patchSeq,
                        payloadNonceB64 = payloadNonceB64,
                        payloadCiphertextSha256Hex = payloadCiphertextSha256Hex,
                        deviceId = deviceId,
                    ),
                )
            }.getOrElse {
                Timber.tag(TAG).w(it, "card_patch HPKE open failed")
                return@withLock null
            }
            val plaintext = runCatching {
                val nonce = dto.payloadNonce.base64ToBytes()
                val ct = dto.payloadCiphertext.base64ToBytes()
                Aead.decrypt(
                    cek, nonce + ct,
                    V2Aad.cardPatchPayloadAad(
                        senderId = dto.senderId,
                        userId = userId,
                        pluginId = dto.pluginId,
                        cardId = dto.cardId,
                        baseSeq = dto.baseSeq,
                        patchSeq = dto.patchSeq,
                    ),
                ).toString(Charsets.UTF_8)
            }.getOrElse {
                Timber.tag(TAG).w(it, "card_patch AES-GCM decrypt failed")
                return@withLock null
            }

            // Atomic apply on a deep clone — any unknown path
            // discards the entire batch (spec privacy invariant).
            val mutated = runCatching {
                applyReplaceOpsOrThrow(card.payloadJson, plaintext)
            }.getOrElse {
                Timber.tag(TAG).w(it, "card_patch op apply failed")
                return@withLock null
            }

            val updated = card.copy(
                payloadJson = mutated,
                lastPatchSequence = dto.patchSeq,
                hostPreview = HostPreviewParser.parse(mutated),
            )
            _items.value = existing.map { if (it === card) updated else it }
            updated
        }
    }

    /**
     * V3 #16 — inbox-pull catch-up applier. For each persisted
     * patch the server inlined on the live-card item, apply
     * the same decrypt + sequence-check + replace-op flow as
     * the live-channel sink. Failed/skipped patches halt the
     * chain (the device falls back to refetching on next
     * refresh; spec "Catch-up surface" gap handling).
     *
     * Returns the InboxItem with `payloadJson` and
     * `lastPatchSequence` updated by every applied patch.
     */
    private suspend fun applyCatchUpPatches(
        card: InboxItem,
        patches: List<CardPatchEntryDto>,
    ): InboxItem {
        if (patches.isEmpty()) return card
        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(CardPatchEnvelopeDto::class.java)
        var current = card
        val expectedBase = card.sequenceNumber ?: 0L
        for (entry in patches.sortedBy { it.patchSeq }) {
            if (entry.baseSeq != expectedBase) {
                Timber.tag(TAG).w(
                    "catch-up patch base_seq %d != card base %d; halting chain",
                    entry.baseSeq, expectedBase,
                )
                return current
            }
            val expectedPatchSeq = (current.lastPatchSequence ?: 0L) + 1L
            if (entry.patchSeq != expectedPatchSeq) {
                Timber.tag(TAG).w(
                    "catch-up patch gap: expected %d got %d; halting chain",
                    expectedPatchSeq, entry.patchSeq,
                )
                return current
            }
            val dto = runCatching { adapter.fromJson(entry.envelopeJson) }.getOrNull()
                ?: run {
                    Timber.tag(TAG).w("catch-up patch envelope malformed; halting chain")
                    return current
                }
            // The shared sink path validates signature +
            // sequence + decrypts + applies — but it operates
            // on _items.value, which we haven't populated yet
            // here. Build a transient apply by reusing the
            // single-op routine directly.
            val applied = applyPatchToCardOrNull(current, dto) ?: run {
                Timber.tag(TAG).w("catch-up patch failed apply; halting chain")
                return current
            }
            current = applied
        }
        return current
    }

    /**
     * V3 #16 — pure-function variant of acceptCardPatch that
     * operates on the supplied InboxItem rather than walking
     * `_items.value`. Used by the catch-up path during
     * refresh() before the card has been inserted into the
     * state flow. Returns null on any rejection.
     */
    private fun applyPatchToCardOrNull(
        card: InboxItem,
        dto: CardPatchEnvelopeDto,
    ): InboxItem? {
        val userId = session.currentUserId() ?: return null
        if (!dto.userId.equals(userId, ignoreCase = true)) return null
        if (!dto.senderId.equals(card.senderId, ignoreCase = true)) return null
        if (!dto.pluginId.equals(card.pluginId, ignoreCase = true)) return null
        if (!dto.cardId.equals(card.id, ignoreCase = true)) return null

        val candidates = pairedSenderStore.pairedSenders.value
            .groupBy { it.senderId }[dto.senderId].orEmpty()
        if (candidates.isEmpty()) return null

        val sortedRecipients = dto.recipientEnvelopes
            .map { mapOf(
                "device_id" to it.deviceId.lowercase(),
                "hpke_ciphertext" to it.hpkeCiphertext,
                "hpke_kem_output" to it.hpkeKemOutput,
            ) }
            .sortedBy { it.getValue("device_id") }
        val canonicalBytes = V2Aad.cardPatchSignedEnvelopeBytes(
            senderId = dto.senderId,
            userId = userId,
            pluginId = dto.pluginId,
            cardId = dto.cardId,
            baseSeq = dto.baseSeq,
            patchSeq = dto.patchSeq,
            payloadNonceB64 = dto.payloadNonce,
            payloadCiphertextB64 = dto.payloadCiphertext,
            recipientEnvelopesSerialized = sortedRecipients,
            recipientDirectoryVersion = dto.recipientDirectoryVersion,
        )
        val signatureBytes = runCatching { dto.envelopeSignature.base64ToBytes() }.getOrNull() ?: return null
        val sigOk = candidates.any { paired ->
            runCatching { Signing.verify(paired.senderPublicKey, canonicalBytes, signatureBytes) }
                .getOrDefault(false)
        }
        if (!sigOk) return null

        val deviceId = deviceIdentityStore.read() ?: return null
        val ownEnv = dto.recipientEnvelopes.firstOrNull {
            it.deviceId.equals(deviceId, ignoreCase = true)
        } ?: return null
        val keypair = deviceEncryptionKeyStore.getOrCreateKeypair()
        val payloadNonceB64 = dto.payloadNonce
        val payloadCiphertextSha256Hex = Hpke.sha256Hex(
            dto.payloadCiphertext.base64ToBytes()
        )
        val cek = runCatching {
            Hpke.openCekForDevice(
                privateKeyRaw = keypair.privateKey,
                hpkeKemOutput = ownEnv.hpkeKemOutput.base64ToBytes(),
                hpkeCiphertext = ownEnv.hpkeCiphertext.base64ToBytes(),
                info = V2Aad.cardPatchHpkeInfo(
                    senderId = dto.senderId,
                    userId = userId,
                    pluginId = dto.pluginId,
                    cardId = dto.cardId,
                    baseSeq = dto.baseSeq,
                    patchSeq = dto.patchSeq,
                    payloadNonceB64 = payloadNonceB64,
                    payloadCiphertextSha256Hex = payloadCiphertextSha256Hex,
                    deviceId = deviceId,
                ),
            )
        }.getOrNull() ?: return null
        val plaintext = runCatching {
            val nonce = dto.payloadNonce.base64ToBytes()
            val ct = dto.payloadCiphertext.base64ToBytes()
            Aead.decrypt(
                cek, nonce + ct,
                V2Aad.cardPatchPayloadAad(
                    senderId = dto.senderId,
                    userId = userId,
                    pluginId = dto.pluginId,
                    cardId = dto.cardId,
                    baseSeq = dto.baseSeq,
                    patchSeq = dto.patchSeq,
                ),
            ).toString(Charsets.UTF_8)
        }.getOrNull() ?: return null

        val mutated = runCatching {
            applyReplaceOpsOrThrow(card.payloadJson, plaintext)
        }.getOrNull() ?: return null

        return card.copy(
            payloadJson = mutated,
            lastPatchSequence = dto.patchSeq,
            hostPreview = HostPreviewParser.parse(mutated),
        )
    }

    private fun applyReplaceOpsOrThrow(payloadJson: String, patchPlaintext: String): String =
        applyReplaceOps(payloadJson, patchPlaintext)

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
    /**
     * V4 #21: builds the GET URL for a plugin's icon, or null when
     * the plugin didn't publish one. Server hosts the bytes at
     * `/v1/plugins/{plugin_row_id}/assets/{content_hash_b64url}`;
     * content-hash is base64-standard on the wire, this helper
     * converts to URL-safe base64 to match the server's url-decode
     * path. Padding is stripped because the server's
     * `urlsafe_b64decode(content_hash_b64url + "==")` re-adds it.
     */
    private fun buildIconUrlOrNull(pluginRowId: String, iconContentHashB64: String?): String? {
        if (iconContentHashB64.isNullOrBlank()) return null
        val base = app.syncler.core.network.BuildConfig.SERVER_BASE_URL.trimEnd('/')
        val urlsafe = iconContentHashB64
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
        return "$base/v1/plugins/$pluginRowId/assets/$urlsafe"
    }

    private suspend fun fetchPluginByRowOrNull(pluginRowId: String): CachedBundle? {
        return runCatching {
            val manifest = api.getPluginById(pluginRowId = pluginRowId)
            val iconUrl = buildIconUrlOrNull(pluginRowId, manifest.iconContentHash)
            val iconVisibility = manifest.iconVisibility ?: "always"

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
                    sensitivity = manifest.sensitivity,
                    iconUrl = iconUrl,
                    iconVisibility = iconVisibility,
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
                    sensitivity = manifest.sensitivity,
                    iconUrl = iconUrl,
                    iconVisibility = iconVisibility,
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
                    sensitivity = manifest.sensitivity,
                    iconUrl = iconUrl,
                    iconVisibility = iconVisibility,
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
                sensitivity = manifest.sensitivity,
                iconUrl = iconUrl,
                iconVisibility = iconVisibility,
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
        /**
         * V4 #20. Plugin-author-declared sensitivity. "public" (default) or
         * "sensitive". The inbox UI consults this to decide whether to show
         * a "🔒 Locked" placeholder; the card-open path consults
         * [SensitiveActionGate] when this is "sensitive". Defaults to
         * "public" so plugins published before this field existed
         * (or any other null/missing-field path) continue to behave as
         * non-sensitive.
         */
        val sensitivity: String = "public",
        /**
         * V4 #21. Server-hosted icon URL the inbox row + notification
         * large icon load from. Null when the plugin author didn't
         * publish an icon. Coil cache-keyed by content hash so updates
         * propagate when the publisher re-publishes with a new icon.
         */
        val iconUrl: String? = null,
        /**
         * V4 #21. "always" | "on_unlock" | "never". Defaults to
         * "always" for non-sensitive plugins (server-side default).
         */
        val iconVisibility: String = "always",
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

/**
 * V3 #16 — top-level testable applier for `card.patch` ops.
 * Exposed `internal` so unit tests can exercise the JSONPath
 * walk + deep-clone semantics directly without faking the
 * surrounding decrypt path.
 *
 * Returns the mutated payload JSON. Throws on:
 *  - missing/invalid `patches` array
 *  - any op with kind != "replace" (V0.1 supports replace only)
 *  - path that doesn't match `$.foo(.bar)*` syntax
 *  - any path segment that doesn't exist in the target
 *
 * Atomic: the function clones [payloadJson] before applying;
 * a throw mid-batch leaves the input untouched, so the caller
 * can discard the whole patch on any failure (codex privacy
 * invariant).
 */
internal fun applyReplaceOps(payloadJson: String, patchPlaintext: String): String {
    val root = org.json.JSONObject(payloadJson)
    val clone = org.json.JSONObject(root.toString())
    val ops = org.json.JSONObject(patchPlaintext).optJSONArray("patches")
        ?: error("patch batch missing 'patches' array")
    for (i in 0 until ops.length()) {
        val op = ops.optJSONObject(i) ?: error("op[$i] not an object")
        val kind = op.optString("op")
        require(kind == "replace") { "op[$i] unsupported kind=$kind" }
        val path = op.optString("path")
        val value = op.opt("value") ?: error("op[$i] missing value")
        applyReplaceOne(clone, path, value)
    }
    return clone.toString()
}

private fun applyReplaceOne(
    target: org.json.JSONObject,
    path: String,
    value: Any,
) {
    require(path.startsWith("\$.")) { "path must start with \$.; got $path" }
    val segments = path.substring(2).split('.')
    require(segments.isNotEmpty() && segments.none { it.isEmpty() }) {
        "malformed path $path"
    }
    var current: org.json.JSONObject = target
    for (i in 0 until segments.size - 1) {
        val seg = segments[i]
        require(current.has(seg)) { "path $path missing segment $seg" }
        val next = current.opt(seg)
        require(next is org.json.JSONObject) { "path $path segment $seg not an object" }
        current = next
    }
    val leaf = segments.last()
    require(current.has(leaf)) { "path $path missing leaf $leaf" }
    current.put(leaf, value)
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
     * V3 #16. Highest `patch_seq` applied to this live card at
     * the current `sequenceNumber` (treat as 0 if null). Resets
     * to 0 on each new upsert. Null for event messages.
     */
    val lastPatchSequence: Long? = null,
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
    /**
     * V4 #20. "public" (default) or "sensitive". Sensitive cards render a
     * "🔒 Locked" placeholder in the inbox list (no preview content) and
     * require [SensitiveActionGate] unlock before the detail screen opens.
     * Defaults to "public" so any row whose plugin manifest fetch hasn't
     * completed yet, or whose plugin row predates this field, renders
     * normally.
     */
    val sensitivity: String = "public",
    /**
     * V4 #21. Server-hosted plugin icon URL — null when the plugin
     * hasn't published one. The inbox row + notification large icon
     * load from this URL via Coil; the content-hash in the URL makes
     * the cache key naturally version-aware.
     */
    val iconUrl: String? = null,
    /**
     * V4 #21. "always" | "on_unlock" | "never". Defaults to "always"
     * (matches the public-plugin default the server applies).
     */
    val iconVisibility: String = "always",
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
