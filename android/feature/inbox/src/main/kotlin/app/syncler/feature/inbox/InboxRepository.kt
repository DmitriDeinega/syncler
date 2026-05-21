package app.syncler.feature.inbox

import app.syncler.core.auth.Session
import app.syncler.core.crypto.Aead
import app.syncler.core.crypto.MessageAad
import app.syncler.core.crypto.base64ToBytes
import app.syncler.core.crypto.toCanonicalJsonBytes
import app.syncler.core.network.MessageInboxItemDto
import app.syncler.core.network.SynclerApi
import app.syncler.core.storage.PairedSenderStore
import app.syncler.feature.inbox.BuildConfig
import java.security.MessageDigest
import org.json.JSONObject
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
    /** Cache keyed by (senderId, pluginIdentifier). */
    private val pluginCache = mutableMapOf<String, CachedPlugin>()
    private val pluginMutex = Mutex()

    private var lastSince: String? = null
    private val pollMutex = Mutex()

    suspend fun refresh(): Result<Int> = pollMutex.withLock {
        runCatching {
            val response = api.inbox(since = lastSince)
            val userId = session.currentUserId() ?: return@runCatching 0
            val pairings = pairedSenderStore.pairedSenders.value.associateBy { it.senderId }

            val newDecrypted = response.messages.mapNotNull { dto ->
                val sender = pairings[dto.senderId] ?: run {
                    Timber.tag(TAG).w("no paired sender for senderId=%s; skipping", dto.senderId)
                    return@mapNotNull null
                }
                if (sender.pairingKey.size != 32) {
                    Timber.tag(TAG).w("paired sender %s has no pairing key; skipping", dto.senderId)
                    return@mapNotNull null
                }
                val decrypted = decrypt(dto, userId = userId, pairingKey = sender.pairingKey, senderName = sender.senderName)
                    ?: return@mapNotNull null
                val plugin = fetchPluginOrNull(dto.senderId, dto.pluginIdentifier)
                decrypted.copy(
                    bundleJs = plugin?.bundleJs,
                    declaredEndpoints = plugin?.endpoints.orEmpty(),
                )
            }

            if (newDecrypted.isNotEmpty()) {
                val merged = (newDecrypted + _items.value).distinctBy { it.id }
                _items.value = merged.sortedByDescending { it.sentAt }
            }
            response.nextSince?.let { lastSince = it }
            newDecrypted.size
        }.onFailure { Timber.tag(TAG).w(it, "refresh failed") }
    }

    private suspend fun fetchPluginOrNull(senderId: String, pluginIdentifier: String): CachedPlugin? {
        val key = "$senderId/$pluginIdentifier"
        pluginMutex.withLock { pluginCache[key] }?.let { return it }
        return runCatching {
            val latest = api.getPluginLatest(senderId = senderId, pluginIdentifier = pluginIdentifier)
            // Scheme check. Release: HTTPS only. Debug: any HTTP allowed for
            // LAN dev hosting. Mirrors the host's bundle-URL policy in
            // :feature:plugin-host PluginLoader.requireHttps.
            val url = latest.signedBundleUrl
            val schemeOk = url.startsWith("https://") || (BuildConfig.DEBUG && url.startsWith("http://"))
            if (!schemeOk) error("plugin bundle URL must be HTTPS (got $url)")

            val bundleBytes = withContext(Dispatchers.IO) {
                bundleHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("plugin bundle HTTP ${resp.code}")
                    resp.body?.bytes() ?: error("empty plugin bundle body")
                }
            }
            // Hash check: SHA-256(bundle) must equal the server-published
            // bundle_hash. The publish endpoint verified the sender's Ed25519
            // signature over (canonical_manifest || bundle_hash) at upload
            // time, so a matching hash is sufficient evidence that this is
            // the bundle the sender published — defense against a compromised
            // CDN / MITM mutating bytes in flight.
            val expectedHash = runCatching { latest.bundleHash.base64ToBytes() }.getOrNull()
                ?: error("plugin bundle_hash is not valid base64")
            val actualHash = MessageDigest.getInstance("SHA-256").digest(bundleBytes)
            if (!actualHash.contentEquals(expectedHash)) {
                error("plugin bundle hash mismatch (CDN/MITM substitution?)")
            }

            val js = bundleBytes.toString(Charsets.UTF_8)
            val cached = CachedPlugin(bundleJs = js, endpoints = latest.endpoints)
            pluginMutex.withLock { pluginCache[key] = cached }
            cached
        }.onFailure {
            Timber.tag(TAG).w(it, "plugin fetch failed for %s", key)
        }.getOrNull()
    }

    private data class CachedPlugin(val bundleJs: String, val endpoints: List<String>)

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
            hostPreview = parseHostPreview(plaintextString),
        )
    }.onFailure { Timber.tag(TAG).w(it, "decrypt failed for message %s", dto.id) }.getOrNull()

    /**
     * Extracts the optional `hostPreview` block from the decrypted payload.
     * The host renders the inbox row from this; the plugin's `render()` still
     * receives the full payload for the detail view. Missing block, malformed
     * block, or required-field violations all yield null and a warn log — the
     * row falls back to "New message from {senderName}" without crashing.
     *
     * Validation mirrors sdk-plugin/preview.ts and sdk-python/preview.py.
     */
    private fun parseHostPreview(plaintextJson: String): HostPreview? = runCatching {
        val root = JSONObject(plaintextJson)
        if (!root.has("hostPreview")) return@runCatching null
        val obj = root.optJSONObject("hostPreview") ?: return@runCatching null

        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: run {
            Timber.tag(TAG).w("hostPreview missing required title; falling back")
            return@runCatching null
        }
        if (title.toByteArray(Charsets.UTF_8).size > 80) {
            Timber.tag(TAG).w("hostPreview.title exceeds 80 UTF-8 bytes; falling back")
            return@runCatching null
        }
        val subtitle = obj.optString("subtitle", "").takeIf { it.isNotEmpty() }
        if (subtitle != null && subtitle.toByteArray(Charsets.UTF_8).size > 120) {
            Timber.tag(TAG).w("hostPreview.subtitle exceeds 120 UTF-8 bytes; falling back")
            return@runCatching null
        }
        val summary = obj.optString("summary", "").takeIf { it.isNotEmpty() }
        if (summary != null && summary.toByteArray(Charsets.UTF_8).size > 240) {
            Timber.tag(TAG).w("hostPreview.summary exceeds 240 UTF-8 bytes; falling back")
            return@runCatching null
        }

        val searchText = mutableListOf<String>()
        val searchArr = obj.optJSONArray("searchText")
        if (searchArr != null) {
            if (searchArr.length() > 16) {
                Timber.tag(TAG).w("hostPreview.searchText has %d entries; max 16", searchArr.length())
                return@runCatching null
            }
            for (i in 0 until searchArr.length()) {
                val token = searchArr.optString(i, "")
                if (token.isEmpty() || token.toByteArray(Charsets.UTF_8).size > 64) {
                    Timber.tag(TAG).w("hostPreview.searchText[%d] invalid; falling back", i)
                    return@runCatching null
                }
                searchText += token
            }
        }
        HostPreview(title = title, subtitle = subtitle, summary = summary, searchText = searchText)
    }.onFailure { Timber.tag(TAG).w(it, "hostPreview parse failed; falling back") }.getOrNull()

    private companion object {
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
