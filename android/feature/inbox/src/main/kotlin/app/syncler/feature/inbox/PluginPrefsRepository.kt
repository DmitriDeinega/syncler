package app.syncler.feature.inbox

import app.syncler.core.storage.PluginSettings
import app.syncler.core.storage.QuietHours
import app.syncler.core.storage.UserStateMutator
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * V4 #19 — typed write surface for per-plugin preferences.
 *
 * Spec: docs/plugin-prefs.md. Wraps the existing
 * [UserStateMutator] so callers (Settings UI in a follow-on
 * commit; tests + future plugin-prefs flows today) don't
 * hand-roll the read-modify-mutateAndPush loop.
 *
 * Cross-device CAS regression risk (codex 149 bigger picture)
 * is handled by [UserStateMutator.mutateAndPush] — the
 * transform lambda always sees the freshest local state, and
 * the underlying CAS retry path re-applies the lambda on a
 * fresh fetch when the server returns 409.
 *
 * v0.1 limitation: identity is the manifest `plugin_identifier`
 * alone (not `{sender_id}/{plugin_identifier}`). Documented in
 * the spec.
 */
@Singleton
class PluginPrefsRepository @Inject constructor(
    private val userState: UserStateMutator,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** Live prefs for [pluginIdentifier]; null when unset. */
    fun prefsOf(pluginIdentifier: String): Flow<PluginSettings?> =
        userState.state.map { it.pluginSettings[pluginIdentifier] }

    /** One-shot read of the current cached prefs (or null). */
    fun snapshot(pluginIdentifier: String): PluginSettings? =
        userState.state.value.pluginSettings[pluginIdentifier]

    /**
     * Apply [transform] to the current PluginSettings entry
     * for [pluginIdentifier]. If no entry exists, [transform]
     * receives an empty stub keyed on `modifiedAt=now`.
     * `modifiedAt` is rewritten to `clock.now()` after the
     * transform so cross-device merge logic in
     * [StateMerger] can pick the latest write.
     */
    suspend fun mutate(
        pluginIdentifier: String,
        transform: (PluginSettings) -> PluginSettings,
    ) {
        val now = clock.instant().toString()
        userState.mutateAndPush { state ->
            val previous = state.pluginSettings[pluginIdentifier]
                ?: PluginSettings(modifiedAt = now)
            val mutated = transform(previous).copy(modifiedAt = now)
            state.copy(
                pluginSettings = state.pluginSettings + (pluginIdentifier to mutated),
            )
        }
    }

    /** Set the per-plugin mute toggle. */
    suspend fun setMuted(pluginIdentifier: String, muted: Boolean) {
        mutate(pluginIdentifier) { it.copy(muted = muted) }
    }

    /** Set the per-plugin label override. Trimmed; null clears. */
    suspend fun setLabelOverride(pluginIdentifier: String, label: String?) {
        val trimmed = label?.trim()?.take(PluginSettings.LABEL_OVERRIDE_MAX_LEN)
        val normalized = if (trimmed.isNullOrEmpty()) null else trimmed
        mutate(pluginIdentifier) { it.copy(labelOverride = normalized) }
    }

    /** Set the notification cadence. Unknown values rejected. */
    suspend fun setNotificationCadence(
        pluginIdentifier: String,
        cadence: String,
    ) {
        require(cadence in PluginSettings.KNOWN_NOTIFICATION_CADENCES) {
            "unknown cadence: $cadence"
        }
        mutate(pluginIdentifier) { it.copy(notificationCadence = cadence) }
    }

    /** Set or clear the quiet-hours window. Null disables it. */
    suspend fun setQuietHours(
        pluginIdentifier: String,
        quietHours: QuietHours?,
    ) {
        mutate(pluginIdentifier) { it.copy(quietHours = quietHours) }
    }
}
