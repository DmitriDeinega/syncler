package app.syncler.core.push

import app.syncler.core.storage.PluginSettings
import app.syncler.core.storage.QuietHours
import app.syncler.core.storage.UserStateMutator
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * V4 #19 — gate that decides whether an inbound plugin
 * message should fire a user-visible notification, based on
 * the user's per-plugin preferences in the encrypted
 * user-state blob.
 *
 * Spec: docs/plugin-prefs.md. Three suppression reasons:
 *
 * - [Decision.MUTED] — the user explicitly muted this
 *   plugin (per-plugin `muted` field) OR the per-sender
 *   mute is set (legacy path; not handled here, see
 *   [MuteStore]).
 * - [Decision.QUIET_HOURS] — the current local time falls
 *   inside the user's configured quiet-hours window.
 * - [Decision.BATCHED] — cadence is batched/digest; the
 *   notification is queued for a later wall-clock-aligned
 *   wake-up rather than fired now.
 * - [Decision.ALLOW] — fire the notification now.
 *
 * v0.1 scope: batching is recognized at the gate but the
 * scheduler that actually fires the deferred notification
 * is V0.2 (WorkManager wiring). Today a `batched_*` /
 * `digest_daily` cadence simply suppresses the immediate
 * notification — the user opens the inbox to see the
 * accumulated rows. Document this in the per-plugin sheet
 * before shipping if WorkManager batching is deferred.
 */
@Singleton
class PluginNotificationGate @Inject constructor(
    private val userState: UserStateMutator,
) {

    enum class Decision {
        ALLOW,
        MUTED,
        QUIET_HOURS,
        BATCHED,
    }

    /**
     * Read the live user-state and decide. Falls back to
     * ALLOW when no PluginSettings row exists for
     * [pluginIdentifier] — new plugins default to realtime,
     * not muted, no quiet hours.
     */
    fun shouldPost(pluginIdentifier: String): Decision {
        val prefs: PluginSettings? = userState.state.value.pluginSettings[pluginIdentifier]
        return decide(prefs, ZonedDateTime.now())
    }

    companion object {
        /**
         * Pure decision function — exposed for unit tests so
         * the gate logic can be exercised without faking
         * Hilt + UserStateMutator + system clock.
         */
        fun decide(prefs: PluginSettings?, now: ZonedDateTime): Decision {
            if (prefs == null) return Decision.ALLOW
            if (prefs.muted) return Decision.MUTED
            if (isInQuietHours(prefs.quietHours, now)) return Decision.QUIET_HOURS
            if (prefs.notificationCadence != PluginSettings.NOTIFICATION_CADENCE_REALTIME) {
                return Decision.BATCHED
            }
            return Decision.ALLOW
        }

        /**
         * True iff [now] falls inside the [hours] window. The
         * window is evaluated in the timezone the user set
         * on save (NOT the device's current timezone — a user
         * travelling shouldn't accidentally drop into
         * someone else's quiet hours).
         *
         * Wrap-midnight semantics: when start > end (e.g.
         * 22 → 7), the window covers [start..23] and
         * [0..end). Equal start + end means an empty window.
         */
        internal fun isInQuietHours(hours: QuietHours?, now: ZonedDateTime): Boolean {
            if (hours == null || !hours.enabled) return false
            if (hours.startLocalHour == hours.endLocalHour) return false
            val zone = runCatching { ZoneId.of(hours.timezone) }.getOrDefault(ZoneId.systemDefault())
            val localHour = now.withZoneSameInstant(zone).hour
            return if (hours.startLocalHour < hours.endLocalHour) {
                localHour in hours.startLocalHour until hours.endLocalHour
            } else {
                // wraps midnight
                localHour >= hours.startLocalHour || localHour < hours.endLocalHour
            }
        }
    }
}
