package app.syncler.core.storage

import java.time.Instant

/**
 * Two-way merge over [EncryptedUserState] (M7.1 — refined per Codex review;
 * M11.2 — added readMessages and archivedMessages):
 *
 *   - installedPlugins: merge by plugin_id, newer installedAt wins.
 *   - dismissedMessages: union by message_id (a dismissal anywhere is final;
 *     no "un-dismiss" path in V1, so first dismissedAt is fine).
 *   - pluginSettings: merge by plugin_id, newer modifiedAt wins.
 *   - readMessages (M11.2): merge by message_id, max(readAt) wins. Read is a
 *     monotone state in V1 — no "mark unread" — so taking the most recent
 *     timestamp gives consistent cross-device semantics.
 *   - archivedMessages (M11.2): same as readMessages.
 *   - userScopedStorage: **NOT MERGED in V1**. Per Codex M7 review, naive
 *     remote-wins risks resurrecting locally-deleted keys and dropping
 *     local edits on same-key conflicts. Without per-key timestamps or
 *     tombstones the merge cannot distinguish "remote is fresher" from
 *     "remote never saw the local delete." V1 keeps userScopedStorage
 *     device-local; cross-device plugin state sync lands V1.5 alongside
 *     per-key metadata. Plugins that need cross-device state today must
 *     sync through their own sender backend.
 *
 * Schema-version mismatch: take the max; [EncryptedUserState.fromJson] is
 * responsible for forward-migrating older blobs at parse time.
 */
object StateMerger {
    fun merge(
        local: EncryptedUserState,
        remote: EncryptedUserState,
    ): EncryptedUserState {
        val schema = maxOf(local.schemaVersion, remote.schemaVersion)

        val installedPlugins = mergeByKey(
            local.installedPlugins + remote.installedPlugins,
            key = { it.pluginId },
            pickWinner = { a, b -> if (compareTimestamps(a.installedAt, b.installedAt) >= 0) a else b },
        )

        val dismissedMessages = (local.dismissedMessages + remote.dismissedMessages)
            .distinctBy { it.messageId }

        val pluginSettings = mergeMapByKey(
            local.pluginSettings,
            remote.pluginSettings,
        ) { a, b -> if (compareTimestamps(a.modifiedAt, b.modifiedAt) >= 0) a else b }

        val readMessages = mergeByKey(
            local.readMessages + remote.readMessages,
            key = { it.messageId },
            pickWinner = { a, b -> if (compareTimestamps(a.readAt, b.readAt) >= 0) a else b },
        )

        val archivedMessages = mergeByKey(
            local.archivedMessages + remote.archivedMessages,
            key = { it.messageId },
            pickWinner = { a, b -> if (compareTimestamps(a.archivedAt, b.archivedAt) >= 0) a else b },
        )

        // userScopedStorage NOT merged in V1 — local wins. See class kdoc.
        return EncryptedUserState(
            schemaVersion = schema,
            installedPlugins = installedPlugins,
            dismissedMessages = dismissedMessages,
            pluginSettings = pluginSettings,
            userScopedStorage = local.userScopedStorage,
            readMessages = readMessages,
            archivedMessages = archivedMessages,
        )
    }

    private fun <T> mergeByKey(
        items: List<T>,
        key: (T) -> String,
        pickWinner: (T, T) -> T,
    ): List<T> {
        val byKey = linkedMapOf<String, T>()
        for (item in items) {
            val k = key(item)
            byKey[k] = byKey[k]?.let { pickWinner(it, item) } ?: item
        }
        return byKey.values.toList()
    }

    private fun <V> mergeMapByKey(
        local: Map<String, V>,
        remote: Map<String, V>,
        pickWinner: (V, V) -> V,
    ): Map<String, V> {
        val merged = local.toMutableMap()
        for ((k, v) in remote) {
            merged[k] = merged[k]?.let { pickWinner(it, v) } ?: v
        }
        return merged
    }

    /**
     * Compare two ISO-8601 timestamps as [Instant]s. Bug Codex flagged in
     * Phase 2 review: string-lexical comparison is fragile because
     * `Instant.toString()` is variable-length — `2026-05-21T10:00:00Z`
     * sorts AFTER `2026-05-21T10:00:00.500Z` lexicographically even though
     * the latter is later in real time. Parsing both sides through
     * `Instant.parse` and using `compareTo` gives the right answer.
     *
     * On unparseable input (corrupt blob), we fall back to string compare —
     * the wrong-but-deterministic behavior — so the merge always returns
     * a valid blob rather than throwing.
     */
    private fun compareTimestamps(a: String, b: String): Int {
        return try {
            Instant.parse(a).compareTo(Instant.parse(b))
        } catch (_: Exception) {
            a.compareTo(b)
        }
    }
}
