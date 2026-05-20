package app.syncler.core.storage

import android.content.Context
import android.content.SharedPreferences
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
 * Pending plugin updates the user has been notified about but hasn't yet
 * accepted/declined/deferred. Each entry persists per-device (no sync).
 */
data class PluginUpdateAvailable(
    val pluginId: String,
    val senderId: String,
    val currentVersion: String,
    val latestVersion: String,
    val manifestHash: String,
    val signedBundleUrl: String,
    val capabilitiesDelta: List<String>,  // new capabilities the user must re-grant
    val discoveredAt: String,
    /** unix millis to wait until before re-prompting; 0 = no defer. */
    val remindAfterMs: Long = 0L,
)

interface PendingUpdatesStore {
    val updates: StateFlow<List<PluginUpdateAvailable>>
    suspend fun addOrReplace(update: PluginUpdateAvailable)
    suspend fun remove(pluginId: String)
    suspend fun byPluginId(pluginId: String): PluginUpdateAvailable?
    suspend fun deferUpdate(pluginId: String, untilMs: Long)
}

@Singleton
class EncryptedPendingUpdatesStore @Inject constructor(
    @ApplicationContext context: Context,
) : PendingUpdatesStore {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val cache = MutableStateFlow<List<PluginUpdateAvailable>>(loadAll())
    override val updates: StateFlow<List<PluginUpdateAvailable>> = cache.asStateFlow()

    @Synchronized
    override suspend fun addOrReplace(update: PluginUpdateAvailable) {
        prefs.edit().putString(recordKey(update.pluginId), encode(update)).apply()
        updateIndex { ids -> ids + update.pluginId }
        cache.value = loadAll()
    }

    @Synchronized
    override suspend fun remove(pluginId: String) {
        prefs.edit().remove(recordKey(pluginId)).apply()
        updateIndex { ids -> ids - pluginId }
        cache.value = loadAll()
    }

    override suspend fun byPluginId(pluginId: String): PluginUpdateAvailable? =
        cache.value.firstOrNull { it.pluginId == pluginId }

    @Synchronized
    override suspend fun deferUpdate(pluginId: String, untilMs: Long) {
        val existing = cache.value.firstOrNull { it.pluginId == pluginId } ?: return
        // Monotonic: never roll a reminder backwards. If a caller supplies
        // an earlier untilMs than the current one, ignore.
        if (untilMs <= existing.remindAfterMs) return
        addOrReplace(existing.copy(remindAfterMs = untilMs))
    }

    private fun updateIndex(transform: (Set<String>) -> Set<String>) {
        val current = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_IDS, transform(current)).apply()
    }

    private fun recordKey(id: String) = "update:$id"

    private fun loadAll(): List<PluginUpdateAvailable> {
        val ids = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        return ids.mapNotNull { id -> prefs.getString(recordKey(id), null)?.let(::decode) }
    }

    private fun encode(u: PluginUpdateAvailable): String = JSONObject().apply {
        put("plugin_id", u.pluginId)
        put("sender_id", u.senderId)
        put("current_version", u.currentVersion)
        put("latest_version", u.latestVersion)
        put("manifest_hash", u.manifestHash)
        put("signed_bundle_url", u.signedBundleUrl)
        put("capabilities_delta", org.json.JSONArray(u.capabilitiesDelta))
        put("discovered_at", u.discoveredAt)
        put("remind_after_ms", u.remindAfterMs)
    }.toString()

    private fun decode(json: String): PluginUpdateAvailable? = runCatching {
        val o = JSONObject(json)
        PluginUpdateAvailable(
            pluginId = o.getString("plugin_id"),
            senderId = o.getString("sender_id"),
            currentVersion = o.getString("current_version"),
            latestVersion = o.getString("latest_version"),
            manifestHash = o.getString("manifest_hash"),
            signedBundleUrl = o.getString("signed_bundle_url"),
            capabilitiesDelta = o.optJSONArray("capabilities_delta")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                .orEmpty(),
            discoveredAt = o.getString("discovered_at"),
            remindAfterMs = o.optLong("remind_after_ms", 0L),
        )
    }.getOrNull()

    private companion object {
        const val PREFS_NAME = "syncler.pending_updates.enc"
        const val KEY_IDS = "update_ids"
    }
}
