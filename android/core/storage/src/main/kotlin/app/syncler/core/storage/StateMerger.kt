package app.syncler.core.storage

/**
 * Two-way merge over [EncryptedUserState] (M7.1 — refined per Codex review):
 *
 *   - installedPlugins: merge by plugin_id, newer installedAt wins.
 *   - dismissedMessages: union (a dismissal anywhere is final).
 *   - pluginSettings: merge by plugin_id, newer modifiedAt wins.
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
            pickWinner = { a, b -> if (a.installedAt >= b.installedAt) a else b },
        )

        val dismissedMessages = (local.dismissedMessages + remote.dismissedMessages)
            .distinctBy { it.messageId }

        val pluginSettings = mergeMapByKey(
            local.pluginSettings,
            remote.pluginSettings,
        ) { a, b -> if (a.modifiedAt >= b.modifiedAt) a else b }

        // userScopedStorage NOT merged in V1 — local wins. See class kdoc.
        return EncryptedUserState(
            schemaVersion = schema,
            installedPlugins = installedPlugins,
            dismissedMessages = dismissedMessages,
            pluginSettings = pluginSettings,
            userScopedStorage = local.userScopedStorage,
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

}
