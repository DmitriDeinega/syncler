package app.syncler.core.storage

import java.time.Instant

/**
 * Compare two ISO-8601 timestamp strings by parsing both as [Instant]s.
 *
 * Why: `Instant.toString()` is variable-length — `2026-05-21T10:00:00Z`
 * sorts AFTER `2026-05-21T10:00:00.500Z` lexicographically even though
 * the latter is later in real time. Anywhere we sort/min/max ISO
 * timestamps cross-device, we MUST parse first or risk reordering
 * causing subtle bugs (wrong pairing key tried first in
 * [InboxRepository.decryptWithAnyKey], wrong "newest" returned by
 * [SyncedPairedSenderStore.bySenderId], etc.).
 *
 * On unparseable input (corrupt blob), falls back to lexical compare so
 * the caller never crashes — the wrong-but-deterministic behavior.
 */
fun compareIsoTimestamps(a: String, b: String): Int = try {
    Instant.parse(a).compareTo(Instant.parse(b))
} catch (_: Exception) {
    a.compareTo(b)
}
