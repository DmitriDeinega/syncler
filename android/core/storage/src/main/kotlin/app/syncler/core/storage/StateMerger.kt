package app.syncler.core.storage

/**
 * Three-way merge over [EncryptedUserState] (M7 Codex Round 6 spec):
 *
 *   - installedPlugins: merge by plugin_id, last-installed wins (newer
 *     installedAt timestamp).
 *   - dismissedMessages: union (a dismissal anywhere is final).
 *   - pluginSettings: merge by plugin_id, last-modified wins.
 *   - userScopedStorage[plugin_id][key]: per-key last-write-wins via
 *     modifiedAt on PluginSettings. (V1 simplification: nested per-key
 *     timestamps land in V1.5 if conflicts get noisy.)
 *
 * Schema-version downgrades are rejected; mismatched-up gets the local
 * version untouched (caller bumps via migration).
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

        // For user-scoped storage, prefer remote values when they exist
        // (V1 simplification — last-writer-wins per-key is V1.5).
        val userScopedStorage = mergeNestedMaps(
            local.userScopedStorage,
            remote.userScopedStorage,
        )

        return EncryptedUserState(
            schemaVersion = schema,
            installedPlugins = installedPlugins,
            dismissedMessages = dismissedMessages,
            pluginSettings = pluginSettings,
            userScopedStorage = userScopedStorage,
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

    private fun mergeNestedMaps(
        local: Map<String, Map<String, String>>,
        remote: Map<String, Map<String, String>>,
    ): Map<String, Map<String, String>> {
        val merged = local.mapValues { it.value.toMutableMap() }.toMutableMap()
        for ((pluginId, kvs) in remote) {
            val target = merged[pluginId]?.toMutableMap() ?: mutableMapOf()
            for ((k, v) in kvs) target[k] = v  // remote-wins
            merged[pluginId] = target
        }
        return merged
    }
}
