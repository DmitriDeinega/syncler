package app.syncler.feature.inbox

import app.syncler.core.auth.Session
import app.syncler.core.crypto.Aead
import app.syncler.core.crypto.MessageAad
import app.syncler.core.crypto.base64ToBytes
import app.syncler.core.crypto.toCanonicalJsonBytes
import app.syncler.core.network.MessageInboxItemDto
import app.syncler.core.network.SynclerApi
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
 * That's fine for V1 — caching across launches is an M11+ refinement that
 * needs invalidation tied to plugin version updates.
 */
@Singleton
class InboxRepository @Inject constructor(
    private val api: SynclerApi,
    private val session: Session,
    private val pairedSenderStore: PairedSenderStore,
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
     * In-memory only in V1 — survives process lifetime, lost on restart.
     * Disk persistence with size-based eviction is V1.5 (see also
     * `docs/integration-guide.md` §9 "Common errors" for the user-visible
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
            // Phase 1: a single sender may have multiple active pairings
            // during the transition window where pre-sync devices each
            // paired separately (each got its own pairingKey; the sender
            // encrypts under whichever it received). Group all active
            // pairings by senderId so decrypt can try each key in turn.
            val pairingsBySender = pairedSenderStore.pairedSenders.value.groupBy { it.senderId }

            // Track whether any message was skipped because the local
            // pairing set didn't have a candidate for it. If so, we do
            // NOT advance `lastSince` — the messages might still resolve
            // once a pending migration / state sync completes, and we
            // want the next refresh to re-fetch them rather than losing
            // them (Codex consultation 53 RED #1).
            var sawMissingPairing = false

            val newDecrypted = response.messages.mapNotNull { dto ->
                val candidates = pairingsBySender[dto.senderId].orEmpty()
                if (candidates.isEmpty()) {
                    Timber.tag(TAG).w("no paired sender for senderId=%s; skipping", dto.senderId)
                    sawMissingPairing = true
                    return@mapNotNull null
                }
                val decrypted = decryptWithAnyKey(
                    dto = dto,
                    userId = userId,
                    candidates = candidates,
                ) ?: run {
                    Timber.tag(TAG).w(
                        "no candidate pairing key decrypted message %s from sender %s",
                        dto.id, dto.senderId,
                    )
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
                    bundleHash = plugin?.bundleHashHex,
                    revocationReason = plugin?.revocationReason,
                )
            }

            if (newDecrypted.isNotEmpty()) {
                val merged = (newDecrypted + _items.value).distinctBy { it.id }
                // Sort by parsed Instant, not by raw string. ISO timestamps
                // with variable fractional seconds sort lexically the wrong
                // way ("…10:00:00Z" > "…10:00:00.500Z") and would put older
                // messages above newer ones in the feed. Items that fail to
                // parse fall to the bottom so a corrupt timestamp can't
                // hijack the top of the inbox.
                _items.value = merged.sortedWith(
                    compareByDescending<InboxItem> { parseInstantOrNull(it.sentAt) }
                        .thenByDescending { it.id },
                )
            }
            if (sawMissingPairing) {
                Timber.tag(TAG).w(
                    "holding lastSince at %s — at least one message had no pairing candidate; will re-fetch next refresh once pairings sync",
                    lastSince,
                )
            } else {
                response.nextSince?.let { lastSince = it }
            }
            newDecrypted.size
        }.onFailure { Timber.tag(TAG).w(it, "refresh failed") }
    }

    /**
     * Fetches the historical manifest for `pluginRowId` via the by-id
     * endpoint, verifies the bundle, caches bytes BY HASH. The row UUID
     * resolves to the exact bundle that was active when the message was
     * sent — survives later sender publishes AND surfaces revocation
     * state (silent for ``superseded``, alert for ``compromised``, etc.).
     *
     * Already-cached hashes short-circuit. If the row's revocation_reason
     * is ``compromised``, refuse to load the bundle — the render path
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
                )
            }

            val expectedHashBytes = runCatching { manifest.bundleHash.base64ToBytes() }.getOrNull()
                ?: error("plugin bundle_hash is not valid base64")
            val expectedHashHex = expectedHashBytes.joinToString("") { "%02x".format(it) }

            // Cache hit by hash — same plugin version we already verified.
            pluginMutex.withLock { bundleByHash[expectedHashHex] }?.let { existing ->
                // Refresh revocation_reason on cache hit so a newly-revoked
                // plugin gets its banner even if its bytes were cached previously.
                return@runCatching existing.copy(revocationReason = manifest.revocationReason)
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
    )

    /**
     * Try every candidate pairing key in turn until one decrypts successfully.
     * Returns the first matching [InboxItem]; null if none of the keys work.
     *
     * Multi-key fallback exists for the Phase 1 transition window: pre-sync,
     * each device paired separately with its own pairingKey. The sender
     * encrypts under whichever key it has registered. After the user re-pairs
     * (which tombstones the old pairings), this collapses to a single
     * active key.
     *
     * Newest pairing first (by firstPairedAt descending) so a freshly-rotated
     * key wins the race against stale legacy pairings.
     */
    private fun decryptWithAnyKey(
        dto: MessageInboxItemDto,
        userId: String,
        candidates: List<PairedSender>,
    ): InboxItem? {
        // Try newest-first by parsed Instant — variable-fraction-second
        // ISO-8601 strings don't sort lexicographically (consultation 53).
        val ordered = candidates.sortedWith { a, b ->
            compareIsoTimestamps(b.firstPairedAt, a.firstPairedAt)  // b vs a = descending
        }
        for (candidate in ordered) {
            if (candidate.pairingKey.size != 32) continue
            val item = decrypt(
                dto = dto,
                userId = userId,
                pairingKey = candidate.pairingKey,
                senderName = candidate.senderName,
            )
            if (item != null) return item
        }
        return null
    }

    private fun decrypt(
        dto: MessageInboxItemDto,
        userId: String,
        pairingKey: ByteArray,
        senderName: String,
    ): InboxItem? = runCatching {
        val aad = MessageAad(
            senderId = dto.senderId,
            userId = userId,
            pluginId = dto.pluginId,
            // Sender uses empty string when min_plugin_version isn't set; server
            // stores null and returns null over the wire — reconstruct ""
            // so the AAD bytes match what the sender signed/encrypted.
            minPluginVersion = dto.minPluginVersion ?: "",
            expiresAt = dto.expiresAt,
        ).toCanonicalJsonBytes()

        val nonce = dto.nonce.base64ToBytes()
        val ciphertext = dto.encryptedBody.base64ToBytes()
        // Aead.decrypt expects (nonce || ciphertext) concatenated; wire splits
        // them, so we reassemble before handing to the AES-GCM primitive.
        val wire = nonce + ciphertext
        val plaintext = Aead.decrypt(pairingKey, wire, aad)

        val plaintextString = plaintext.toString(Charsets.UTF_8)
        InboxItem(
            id = dto.id,
            senderId = dto.senderId,
            senderName = senderName,
            pluginId = dto.pluginId,
            pluginIdentifier = dto.pluginIdentifier,
            payloadJson = plaintextString,
            sentAt = dto.sentAt,
            expiresAt = dto.expiresAt,
            bundleJs = null,
            declaredEndpoints = emptyList(),
            bundleHash = null,
            revocationReason = null,
            hostPreview = HostPreviewParser.parse(plaintextString),
        )
    }.onFailure { Timber.tag(TAG).w(it, "decrypt failed for message %s", dto.id) }.getOrNull()

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
     * Plugin JS bundle source. Null when the fetch is in-flight or failed —
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
     * has been pulled. One of: ``superseded`` (silent — UX may show subdued
     * banner), ``compromised`` (refuse to render; show security warning),
     * ``sender_disabled`` (render with "no longer available" banner),
     * ``unspecified`` (pre-M11.4 legacy revoke — treat conservatively).
     * Null on active rows.
     */
    val revocationReason: String?,
    /**
     * Structured row metadata extracted from the decrypted payload's reserved
     * `hostPreview` key. Null when the sender didn't supply one or the block
     * was malformed (logged and ignored — the row falls back to a generic
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
