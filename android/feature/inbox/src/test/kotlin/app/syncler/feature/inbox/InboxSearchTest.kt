package app.syncler.feature.inbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks host-side metadata search to the contract documented in §2 of the
 * integration guide: substring match across title / subtitle / summary /
 * searchText / senderName, case-insensitive, blank query is a no-op
 * (returns true for all items so the UI shows the unfiltered set).
 */
class InboxSearchTest {

    private fun item(
        sender: String = "Sender",
        title: String = "Title",
        subtitle: String? = null,
        summary: String? = null,
        searchText: List<String> = emptyList(),
    ) = InboxItem(
        id = "id-1",
        senderId = "s",
        senderName = sender,
        pluginId = "p",
        pluginIdentifier = "com.x",
        payloadJson = "{}",
        sentAt = "2026-05-21T00:00:00Z",
        expiresAt = "2026-06-20T00:00:00Z",
        bundleJs = null,
        declaredEndpoints = emptyList(),
        bundleHash = null,
        revocationReason = null,
        hostPreview = HostPreview(title, subtitle, summary, searchText),
    )

    @Test fun `blank query matches everything`() {
        val it = item(title = "Anything")
        assertTrue(matchesQuery(it, ""))
        assertTrue(matchesQuery(it, "   "))
    }

    @Test fun `title substring match`() {
        val it = item(title = "Mega Millions draw results")
        assertTrue(matchesQuery(it, "millions"))
        assertTrue(matchesQuery(it, "MEGA"))
        assertFalse(matchesQuery(it, "powerball"))
    }

    @Test fun `subtitle and summary participate`() {
        val it = item(
            title = "Alert",
            subtitle = "Account 8KQ-193",
            summary = "Threshold exceeded by 12 percent",
        )
        assertTrue(matchesQuery(it, "8kq"))
        assertTrue(matchesQuery(it, "threshold"))
    }

    @Test fun `searchText tokens participate`() {
        val it = item(
            title = "Alert",
            searchText = listOf("ticker:AAPL", "trade", "buy"),
        )
        assertTrue(matchesQuery(it, "aapl"))
        assertTrue(matchesQuery(it, "buy"))
        assertFalse(matchesQuery(it, "sell"))
    }

    @Test fun `sender name participates`() {
        val it = item(sender = "Pais Lotto")
        assertTrue(matchesQuery(it, "pais"))
        assertTrue(matchesQuery(it, "LOTTO"))
    }

    @Test fun `fallback items without hostPreview still searchable by sender`() {
        val it = InboxItem(
            id = "x", senderId = "s", senderName = "FallbackBot",
            pluginId = "p", pluginIdentifier = "com.x", payloadJson = "{}",
            sentAt = "2026-05-21T00:00:00Z", expiresAt = "2026-06-20T00:00:00Z",
            bundleJs = null, declaredEndpoints = emptyList(),
            bundleHash = null, revocationReason = null,
            hostPreview = null,
        )
        assertTrue(matchesQuery(it, "fallback"))
        assertFalse(matchesQuery(it, "anything-else"))
    }
}
