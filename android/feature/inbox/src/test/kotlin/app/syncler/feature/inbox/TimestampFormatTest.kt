package app.syncler.feature.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimestampFormatTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-05-21T12:00:00Z")

    @Test
    fun `same day shows wall clock time`() {
        assertEquals("11:59", TimestampFormat.arrival("2026-05-21T11:59:30Z", now, zone))
        assertEquals("00:01", TimestampFormat.arrival("2026-05-21T00:01:00Z", now, zone))
        assertEquals("12:00", TimestampFormat.arrival("2026-05-21T12:00:00Z", now, zone))
    }

    @Test
    fun `yesterday shows month and day`() {
        assertEquals("May 20", TimestampFormat.arrival("2026-05-20T23:59:59Z", now, zone))
        assertEquals("May 20", TimestampFormat.arrival("2026-05-20T00:00:00Z", now, zone))
    }

    @Test
    fun `earlier this year shows month and day`() {
        assertEquals("May 19", TimestampFormat.arrival("2026-05-19T23:59:59Z", now, zone))
        assertEquals("Jan 1", TimestampFormat.arrival("2026-01-01T00:00:00Z", now, zone))
    }

    @Test
    fun `previous year still shows month and day`() {
        // Year is intentionally dropped — same-day vs. not-same-day is all
        // the user needs in the inbox row.
        assertEquals("Dec 31", TimestampFormat.arrival("2025-12-31T23:59:59Z", now, zone))
    }

    @Test
    fun `future same-day timestamp still uses wall clock`() {
        assertEquals("13:00", TimestampFormat.arrival("2026-05-21T13:00:00Z", now, zone))
    }

    @Test
    fun `garbage input falls through to raw string`() {
        assertEquals("garbage", TimestampFormat.arrival("garbage", now, zone))
    }
}
