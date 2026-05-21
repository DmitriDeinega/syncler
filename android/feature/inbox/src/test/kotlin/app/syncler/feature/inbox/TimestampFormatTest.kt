package app.syncler.feature.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimestampFormatTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-05-21T12:00:00Z")

    @Test
    fun `just now for less than 60 seconds`() {
        assertEquals("just now", TimestampFormat.relative("2026-05-21T11:59:30Z", now, zone))
        assertEquals("just now", TimestampFormat.relative("2026-05-21T11:59:01Z", now, zone))
    }

    @Test
    fun `minutes for less than 60 minutes`() {
        assertEquals("1m", TimestampFormat.relative("2026-05-21T11:59:00Z", now, zone))
        assertEquals("59m", TimestampFormat.relative("2026-05-21T11:00:01Z", now, zone))
    }

    @Test
    fun `HH mm for same local day`() {
        // 11:00:00Z is 1 hour ago, but it's >= 60m so it should show HH:mm
        assertEquals("11:00", TimestampFormat.relative("2026-05-21T11:00:00Z", now, zone))
        assertEquals("00:01", TimestampFormat.relative("2026-05-21T00:01:00Z", now, zone))
    }

    @Test
    fun `Yesterday for previous local day`() {
        assertEquals("Yesterday", TimestampFormat.relative("2026-05-20T23:59:59Z", now, zone))
        assertEquals("Yesterday", TimestampFormat.relative("2026-05-20T00:00:00Z", now, zone))
    }

    @Test
    fun `MMM d for same year`() {
        assertEquals("May 19", TimestampFormat.relative("2026-05-19T23:59:59Z", now, zone))
        assertEquals("Jan 1", TimestampFormat.relative("2026-01-01T00:00:00Z", now, zone))
    }

    @Test
    fun `MMM d yyyy for older years`() {
        assertEquals("Dec 31, 2025", TimestampFormat.relative("2025-12-31T23:59:59Z", now, zone))
    }

    @Test
    fun `future timestamps show HH mm if same day`() {
        // If it's today but in the future (skew), show wall clock
        assertEquals("13:00", TimestampFormat.relative("2026-05-21T13:00:00Z", now, zone))
    }

    @Test
    fun `fallback to raw string on parse error`() {
        assertEquals("garbage", TimestampFormat.relative("garbage", now, zone))
    }
}
