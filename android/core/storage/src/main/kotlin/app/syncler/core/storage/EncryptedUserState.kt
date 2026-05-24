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
    val schemaVersion: Int = SCHEMA_CURRENT,
    val installedPlugins: List<InstalledPluginRef> = emptyList(),
    val dismissedMessages: List<DismissedMessageEntry> = emptyList(),
    val pluginSettings: Map<String, PluginSettings> = emptyMap(),
    /** Per-plugin user-scoped storage (I1 storage scope = "user"). */
    val userScopedStorage: Map<String, Map<String, String>> = emptyMap(),
    /** M11.2: per-message read state, synced across user's devices. */
    val readMessages: List<ReadMessageEntry> = emptyList(),
    /** M11.2: per-message archive state, distinct from dismissal. */
    val archivedMessages: List<ArchivedMessageEntry> = emptyList(),
    /** M11.6: per-message delete state. Hidden from all surfaces; merges as union. */
    val deletedMessages: List<DeletedMessageEntry> = emptyList(),
    /**
     * Phase 3c: sender IDs the user has muted. Muted senders are filtered out
     * of the inbox view. Merged as a union (mute on any device propagates).
     */
    val mutedSenders: List<String> = emptyList(),
    /**
     * Phase 1: synced paired senders. Each user device that scans a sender's
     * QR adds a [PairedSenderEntry] to this list; the M7 state blob fans the
     * pairing key (encrypted under the user's master key) out to every other
     * device the user owns, so any device can decrypt incoming messages from
     * that sender. Tombstone-on-revoke (`removedAt` set) prevents an offline
     * device from resurrecting a revoked pairing on its next sync.
     */
    val pairedSenders: List<PairedSenderEntry> = emptyList(),
    /**
     * Phase 1: per-user marker for the one-shot legacy-prefs migration. When
     * non-null, the SyncedPairedSenderStore has already imported (or
     * inspected and decided to skip) any pre-Phase-1 local entries for this
     * user. Per-user gating is essential: without it, a multi-user device
     * could push one user's stale legacy pairings into the next user's
     * account (Codex consultation 53 RED #2). The synced state is
     * user-scoped, so this flag rides along with the right user.
     */
    val phase1MigrationDoneAt: String? = null,
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
        put("read_messages", JSONArray().apply {
            readMessages.forEach { put(it.toJson()) }
        })
        put("archived_messages", JSONArray().apply {
            archivedMessages.forEach { put(it.toJson()) }
        })
        put("deleted_messages", JSONArray().apply {
            deletedMessages.forEach { put(it.toJson()) }
        })
        put("paired_senders", JSONArray().apply {
            pairedSenders.forEach { put(it.toJson()) }
        })
        put("muted_senders", JSONArray().apply {
            mutedSenders.forEach { put(it) }
        })
        put("phase1_migration_done_at", phase1MigrationDoneAt ?: JSONObject.NULL)
    }.toString()

    companion object {
        const val SCHEMA_V1 = 1
        const val SCHEMA_V2 = 2
        const val SCHEMA_V3 = 3
        /** Phase 1: adds `paired_senders` field for synced pairing. */
        const val SCHEMA_V4 = 4
        /** Phase 3c: adds `muted_senders` field. */
        const val SCHEMA_V5 = 5
        const val SCHEMA_CURRENT = SCHEMA_V5

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
                schemaVersion = when (schema) {
                    SCHEMA_V0, SCHEMA_V1, SCHEMA_V2, SCHEMA_V3, SCHEMA_V4 -> SCHEMA_CURRENT  // forward-migrate
                    else -> schema
                },
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
                // V1 blobs lack these fields — fromJson returns empty lists,
                // which is the correct semantics (no messages read/archived
                // yet from the V1 device's perspective).
                readMessages = obj.optJSONArray("read_messages")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull {
                            runCatching { ReadMessageEntry.fromJson(arr.getJSONObject(it)) }.getOrNull()
                        }
                    }
                    .orEmpty(),
                archivedMessages = obj.optJSONArray("archived_messages")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull {
                            runCatching { ArchivedMessageEntry.fromJson(arr.getJSONObject(it)) }.getOrNull()
                        }
                    }
                    .orEmpty(),
                deletedMessages = obj.optJSONArray("deleted_messages")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull {
                            runCatching { DeletedMessageEntry.fromJson(arr.getJSONObject(it)) }.getOrNull()
                        }
                    }
                    .orEmpty(),
                mutedSenders = obj.optJSONArray("muted_senders")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull {
                            runCatching { arr.getString(it) }.getOrNull()
                        }
                    }
                    .orEmpty(),
                pairedSenders = obj.optJSONArray("paired_senders")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull {
                            runCatching { PairedSenderEntry.fromJson(arr.getJSONObject(it)) }.getOrNull()
                        }
                    }
                    .orEmpty(),
                phase1MigrationDoneAt = if (obj.isNull("phase1_migration_done_at")) {
                    null
                } else {
                    obj.optString("phase1_migration_done_at").takeIf { it.isNotEmpty() }
                },
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

/**
 * M11.2: a message the user has marked read on at least one device. Synced
 * via the M7 CAS state blob. Merge resolves conflicts by max(readAt) per
 * messageId — i.e. the most recently-set read timestamp wins, which works
 * because "read" is a monotone state (you don't go from read to unread
 * silently; a manual "mark unread" would be a separate action that writes a
 * tombstone, not in V1).
 */
data class ReadMessageEntry(
    val messageId: String,
    val readAt: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("message_id", messageId)
        put("read_at", readAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = ReadMessageEntry(
            messageId = o.getString("message_id"),
            readAt = o.getString("read_at"),
        )
    }
}

/**
 * M11.2: a message the user has archived. The archive state is synced via
 * the M7 CAS blob so an archive on one device shows up across all of the
 * user's devices.
 *
 * V1 semantic limit (called out in review 39 by Codex): "archived" is a
 * *filter* over the still-fetchable inbox — we don't persist the message
 * body locally beyond the server's 30-day retention cap. Once the server
 * expires the message, the archived id remains in this list but there's
 * no body to render. A proper "keep past expiry" archive needs a local
 * message body store and is V1.5 work. For now, treat archive as
 * "hide from the active inbox while the server still has the message."
 *
 * Distinct from "dismiss": dismiss is a server-side message lifecycle
 * action (POST /v1/messages/.../dismiss). Archive is purely the user's
 * cross-device organization state.
 */
data class ArchivedMessageEntry(
    val messageId: String,
    val archivedAt: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("message_id", messageId)
        put("archived_at", archivedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = ArchivedMessageEntry(
            messageId = o.getString("message_id"),
            archivedAt = o.getString("archived_at"),
        )
    }
}

/**
 * M11.6: per-message deletion. Distinct from archive: deleted messages are
 * hidden from BOTH the inbox AND the archive screen — the user said "make
 * it go away." Synced via the M7 CAS blob so deleting on one device hides
 * the card everywhere. Like archive, this is local state only; the server
 * still has the message until its retention cap expires.
 *
 * Merge semantics: union by message_id, max(deleted_at) wins on conflict.
 * No undelete in V1 — the entry is monotone.
 */
data class DeletedMessageEntry(
    val messageId: String,
    val deletedAt: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("message_id", messageId)
        put("deleted_at", deletedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = DeletedMessageEntry(
            messageId = o.getString("message_id"),
            deletedAt = o.getString("deleted_at"),
        )
    }
}

/**
 * Phase 1: a paired sender, synced via the M7 encrypted user-state blob.
 *
 * One QR scan on any of the user's devices propagates the pairing key (a
 * 32-byte AES-256 secret shared with the sender) to every other device
 * the user has enrolled. Each device decrypts the blob with its master
 * key, sees the new entry, and projects it into its local
 * `PairedSenderStore`.
 *
 * `removedAt` is a tombstone — when the user revokes the pairing on any
 * device, the entry stays in the synced blob with `removedAt` set. An
 * offline device that comes back online with stale local state cannot
 * resurrect the pairing on its next push because the merger keeps the
 * tombstone.
 *
 * `source = "manual"` for entries added by an explicit user pairing
 * action; `source = "migration"` for entries bootstrapped from a
 * device's local `PairedSenderStore` on the first launch after the
 * SCHEMA_V4 bump (so devices that paired pre-sync get caught up).
 */
data class PairedSenderEntry(
    val pairingId: String,
    val senderId: String,
    val senderName: String,
    val senderPublicKey: String,       // base64
    val fingerprint: String,
    val nameHash: String,              // base64
    val pairingKey: String,            // base64, 32 bytes (AES-256)
    val firstPairedAt: String,         // ISO-8601 UTC
    val removedAt: String? = null,
    val source: String = "manual",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pairing_id", pairingId)
        put("sender_id", senderId)
        put("sender_name", senderName)
        put("sender_public_key", senderPublicKey)
        put("fingerprint", fingerprint)
        put("name_hash", nameHash)
        put("pairing_key", pairingKey)
        put("first_paired_at", firstPairedAt)
        put("removed_at", removedAt ?: JSONObject.NULL)
        put("source", source)
    }

    companion object {
        fun fromJson(o: JSONObject) = PairedSenderEntry(
            pairingId = o.getString("pairing_id"),
            senderId = o.getString("sender_id"),
            senderName = o.getString("sender_name"),
            senderPublicKey = o.getString("sender_public_key"),
            fingerprint = o.getString("fingerprint"),
            nameHash = o.getString("name_hash"),
            pairingKey = o.getString("pairing_key"),
            firstPairedAt = o.getString("first_paired_at"),
            removedAt = if (o.isNull("removed_at")) null else o.optString("removed_at").takeIf { it.isNotEmpty() },
            source = o.optString("source", "manual").ifEmpty { "manual" },
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
