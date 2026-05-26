package app.syncler.core.push

import app.syncler.core.storage.PluginSettings
import app.syncler.core.storage.QuietHours
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginNotificationGateTest {

    @Test
    fun `null prefs default to ALLOW`() {
        val decision = PluginNotificationGate.decide(prefs = null, now = ny("2026-05-26T15:00:00"))
        assertEquals(PluginNotificationGate.Decision.ALLOW, decision)
    }

    @Test
    fun `realtime cadence with no quiet hours posts`() {
        val prefs = base()
        assertEquals(
            PluginNotificationGate.Decision.ALLOW,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T15:00:00")),
        )
    }

    @Test
    fun `muted prefs suppress`() {
        val prefs = base(muted = true)
        assertEquals(
            PluginNotificationGate.Decision.MUTED,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T15:00:00")),
        )
    }

    @Test
    fun `mute beats quiet hours in priority`() {
        // A muted plugin returns MUTED even when inside the
        // quiet-hours window — caller doesn't need to disambiguate
        // both conditions, just knows the notification is gone.
        val prefs = base(
            muted = true,
            quietHours = QuietHours(true, 22, 7, "America/New_York"),
        )
        assertEquals(
            PluginNotificationGate.Decision.MUTED,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T02:00:00")),
        )
    }

    @Test
    fun `non-wrapping quiet window applies during the day`() {
        // 09:00-17:00 quiet (e.g. work hours)
        val prefs = base(quietHours = QuietHours(true, 9, 17, "America/New_York"))
        assertEquals(
            PluginNotificationGate.Decision.QUIET_HOURS,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T11:30:00")),
        )
        assertEquals(
            PluginNotificationGate.Decision.ALLOW,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T17:00:00")),
        )
        assertEquals(
            PluginNotificationGate.Decision.ALLOW,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T08:00:00")),
        )
    }

    @Test
    fun `wrap-midnight quiet window applies before and after midnight`() {
        // 22:00-07:00 quiet (overnight)
        val prefs = base(quietHours = QuietHours(true, 22, 7, "America/New_York"))
        assertEquals(
            PluginNotificationGate.Decision.QUIET_HOURS,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T23:30:00")),
        )
        assertEquals(
            PluginNotificationGate.Decision.QUIET_HOURS,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T02:00:00")),
        )
        assertEquals(
            PluginNotificationGate.Decision.QUIET_HOURS,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T06:59:00")),
        )
        // 07:00 is the exclusive upper bound — at 7 sharp we're out.
        assertEquals(
            PluginNotificationGate.Decision.ALLOW,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T07:00:00")),
        )
    }

    @Test
    fun `quiet window uses saved timezone not device timezone`() {
        // User saved 22:00-07:00 New York time. Device is in
        // Tokyo (UTC+9). 22:00 NY = 11:00 next-day Tokyo.
        // At 11:00 Tokyo local, the gate should still say
        // QUIET_HOURS because the saved tz is what matters.
        val prefs = base(quietHours = QuietHours(true, 22, 7, "America/New_York"))
        // 2026-05-26T11:00 in Tokyo = 2026-05-25T22:00 in NY
        val nowTokyo = ZonedDateTime.of(
            LocalDateTime.of(2026, 5, 26, 11, 0),
            ZoneId.of("Asia/Tokyo"),
        )
        assertEquals(
            PluginNotificationGate.Decision.QUIET_HOURS,
            PluginNotificationGate.decide(prefs, nowTokyo),
        )
    }

    @Test
    fun `disabled quiet hours never apply even mid-window`() {
        val prefs = base(quietHours = QuietHours(false, 9, 17, "America/New_York"))
        assertEquals(
            PluginNotificationGate.Decision.ALLOW,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T11:30:00")),
        )
    }

    @Test
    fun `equal start and end means empty window not all-day`() {
        // start==end is ambiguous; spec says empty (not 24h).
        val prefs = base(quietHours = QuietHours(true, 12, 12, "America/New_York"))
        assertEquals(
            PluginNotificationGate.Decision.ALLOW,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T12:00:00")),
        )
    }

    @Test
    fun `batched cadence suppresses even outside quiet hours`() {
        val prefs = base(cadence = PluginSettings.NOTIFICATION_CADENCE_BATCHED_15M)
        assertEquals(
            PluginNotificationGate.Decision.BATCHED,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T15:00:00")),
        )
    }

    @Test
    fun `quiet hours beats batched in reporting priority`() {
        // Quiet-hours is a stronger signal — the user wanted
        // silence, not deferral. Caller can show different copy.
        val prefs = base(
            cadence = PluginSettings.NOTIFICATION_CADENCE_BATCHED_15M,
            quietHours = QuietHours(true, 22, 7, "America/New_York"),
        )
        assertEquals(
            PluginNotificationGate.Decision.QUIET_HOURS,
            PluginNotificationGate.decide(prefs, ny("2026-05-26T02:00:00")),
        )
    }

    // --- helpers ---

    private fun base(
        muted: Boolean = false,
        cadence: String = PluginSettings.NOTIFICATION_CADENCE_REALTIME,
        quietHours: QuietHours? = null,
    ) = PluginSettings(
        modifiedAt = "2026-05-26T22:00:00Z",
        notificationCadence = cadence,
        quietHours = quietHours,
        muted = muted,
    )

    private fun ny(local: String): ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.parse(local), ZoneId.of("America/New_York"))
}
