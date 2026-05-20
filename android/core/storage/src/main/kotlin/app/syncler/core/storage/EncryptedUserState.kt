package app.syncler.core.storage

import org.json.JSONArray
import org.json.JSONObject

/**
 * Structured user state — what the encrypted blob deserializes to after
 * the client decrypts it with the master key.
 *
 * Schema version is embedded so future migrations can detect old blobs
 * and run a one-shot migration step before merging.
 */
data class EncryptedUserState(
    val schemaVersion: Int = SCHEMA_V1,
    val installedPlugins: List<InstalledPluginRef> = emptyList(),
    val dismissedMessages: List<DismissedMessageEntry> = emptyList(),
    val pluginSettings: Map<String, PluginSettings> = emptyMap(),
    /** Per-plugin user-scoped storage (I1 storage scope = "user"). */
    val userScopedStorage: Map<String, Map<String, String>> = emptyMap(),
) {
    fun toJson(): String = JSONObject().apply {
        put("schema_version", schemaVersion)
        put("installed_plugins", JSONArray().apply {
            installedPlugins.forEach { put(it.toJson()) }
        })
        put("dismissed_messages", JSONArray().apply {
            dismissedMessages.forEach { put(it.toJson()) }
        })
        put("plugin_settings", JSONObject().apply {
            pluginSettings.forEach { (k, v) -> put(k, v.toJson()) }
        })
        put("user_scoped_storage", JSONObject().apply {
            userScopedStorage.forEach { (pluginId, kvs) ->
                put(pluginId, JSONObject().apply { kvs.forEach { (k, v) -> put(k, v) } })
            }
        })
    }.toString()

    companion object {
        const val SCHEMA_V1 = 1

        /** Pre-V1 blobs (no schema_version field) — migrated forward at parse. */
        const val SCHEMA_V0 = 0

        fun fromJson(json: String): EncryptedUserState {
            if (json.isEmpty()) return EncryptedUserState()
            val obj = JSONObject(json)
            // Absent schema_version = V0 (pre-V1). We treat it explicitly
            // rather than silently calling it V1, so future migrations can
            // route the blob through a dedicated upgrade step.
            val schema = if (obj.has("schema_version")) obj.getInt("schema_version") else SCHEMA_V0
            return EncryptedUserState(
                schemaVersion = if (schema == SCHEMA_V0) SCHEMA_V1 else schema,
                installedPlugins = obj.optJSONArray("installed_plugins")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull {
                            runCatching { InstalledPluginRef.fromJson(arr.getJSONObject(it)) }.getOrNull()
                        }
                    }
                    .orEmpty(),
                dismissedMessages = obj.optJSONArray("dismissed_messages")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull {
                            runCatching { DismissedMessageEntry.fromJson(arr.getJSONObject(it)) }.getOrNull()
                        }
                    }
                    .orEmpty(),
                pluginSettings = obj.optJSONObject("plugin_settings")
                    ?.let { o ->
                        o.keys().asSequence().mapNotNull { key ->
                            runCatching { key to PluginSettings.fromJson(o.getJSONObject(key)) }.getOrNull()
                        }.toMap()
                    }
                    .orEmpty(),
                userScopedStorage = obj.optJSONObject("user_scoped_storage")
                    ?.let { o ->
                        o.keys().asSequence().mapNotNull { pluginId ->
                            runCatching {
                                val nested = o.getJSONObject(pluginId)
                                pluginId to nested.keys().asSequence().mapNotNull { k ->
                                    runCatching { k to nested.getString(k) }.getOrNull()
                                }.toMap()
                            }.getOrNull()
                        }.toMap()
                    }
                    .orEmpty(),
            )
        }
    }
}

data class InstalledPluginRef(
    val pluginId: String,
    val senderId: String,
    val version: String,
    val installedAt: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("plugin_id", pluginId)
        put("sender_id", senderId)
        put("version", version)
        put("installed_at", installedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = InstalledPluginRef(
            pluginId = o.getString("plugin_id"),
            senderId = o.getString("sender_id"),
            version = o.getString("version"),
            installedAt = o.getString("installed_at"),
        )
    }
}

data class DismissedMessageEntry(
    val messageId: String,
    val dismissedAt: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("message_id", messageId)
        put("dismissed_at", dismissedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = DismissedMessageEntry(
            messageId = o.getString("message_id"),
            dismissedAt = o.getString("dismissed_at"),
        )
    }
}

data class PluginSettings(
    val grantedCapabilities: List<String> = emptyList(),
    val dismissBehaviorOverride: String? = null,
    val modifiedAt: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("granted_capabilities", JSONArray().apply { grantedCapabilities.forEach { put(it) } })
        put("dismiss_behavior_override", dismissBehaviorOverride ?: JSONObject.NULL)
        put("modified_at", modifiedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = PluginSettings(
            grantedCapabilities = o.optJSONArray("granted_capabilities")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                .orEmpty(),
            dismissBehaviorOverride = o.optString("dismiss_behavior_override").takeIf { it.isNotEmpty() && it != "null" },
            modifiedAt = o.getString("modified_at"),
        )
    }
}
