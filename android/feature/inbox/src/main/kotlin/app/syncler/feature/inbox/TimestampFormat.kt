package app.syncler.feature.inbox

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Compact relative-time formatting for inbox rows and the detail header.
 *
 * The server's `sent_at` is canonical UTC. The user expects to see it in
 * their local time, expressed as "how recent" rather than "what wall-clock
 * timestamp." Format rules:
 *
 *   - <60s ago         → "just now"
 *   - <60m ago         → "{n}m"
 *   - same local date  → "HH:mm"
 *   - yesterday        → "Yesterday"
 *   - same year        → "MMM d"  (e.g. "May 19")
 *   - older            → "MMM d, yyyy"
 *
 * Unparseable input falls back to the raw string so we never block render.
 */
internal object TimestampFormat {
    private val timeOnly = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val monthDay = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT)
    private val monthDayYear = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ROOT)

    fun relative(iso: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val parsed = runCatching { Instant.parse(iso) }.getOrElse { return iso }
        val zoned = parsed.atZone(zone)
        // Derive `today` from the supplied `now` (not from LocalDate.now(zone))
        // so tests with a fake clock get deterministic same-day / yesterday
        // / earlier-this-year branches. Codex review 43 caught this — the
        // previous LocalDate.now() call ignored the now parameter and made
        // any fixed-clock test stale on different real calendar days.
        val today = now.atZone(zone).toLocalDate()
        val date = zoned.toLocalDate()

        val delta = Duration.between(parsed, now)
        if (!delta.isNegative) {
            if (delta < Duration.ofMinutes(1)) return "just now"
            if (delta < Duration.ofHours(1)) return "${delta.toMinutes()}m"
        }

        return when {
            date == today -> zoned.format(timeOnly)
            date == today.minusDays(1) -> "Yesterday"
            date.year == today.year -> zoned.format(monthDay)
            else -> zoned.format(monthDayYear)
        }
    }
}
