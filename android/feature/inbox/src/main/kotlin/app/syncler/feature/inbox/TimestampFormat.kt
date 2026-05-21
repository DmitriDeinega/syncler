package app.syncler.feature.inbox

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Wall-clock arrival timestamp for inbox rows.
 *
 * The server's `sent_at` is canonical UTC. The user expects the row to show
 * the time it landed in their local zone:
 *
 *   - same local date  → "HH:mm"   (e.g. "14:32")
 *   - any other date   → "MMM d"   (e.g. "May 19")
 *
 * Unparseable input falls back to the raw string so we never block render.
 *
 * Prior versions of this format showed relative offsets ("just now", "5m",
 * "Yesterday"). The relative form was dropped because the inbox already
 * sorts newest-first and the user only needs to know *when* something
 * arrived — concrete values stay stable and don't redraw every minute.
 */
internal object TimestampFormat {
    private val timeOnly = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val monthDay = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT)

    fun arrival(iso: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val parsed = runCatching { Instant.parse(iso) }.getOrElse { return iso }
        val zoned = parsed.atZone(zone)
        // Derive `today` from the supplied `now` (not from LocalDate.now(zone))
        // so tests with a fake clock get deterministic same-day branching.
        val today = now.atZone(zone).toLocalDate()
        val date = zoned.toLocalDate()
        return if (date == today) zoned.format(timeOnly) else zoned.format(monthDay)
    }
}
