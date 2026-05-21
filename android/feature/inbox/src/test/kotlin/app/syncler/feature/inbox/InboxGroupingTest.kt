package app.syncler.feature.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class InboxGroupingTest {

    @Test
    fun `grouping by sender sorts by most recent activity`() {
        // Items for sender A: one today, one yesterday
        // Items for sender B: one today (but older than A's today)
        // Items for sender C: one 2 days ago
        
        val items = listOf(
            createItem("a1", "senderA", "2026-05-21T10:00:00Z"),
            createItem("a2", "senderA", "2026-05-20T10:00:00Z"),
            createItem("b1", "senderB", "2026-05-21T09:00:00Z"),
            createItem("c1", "senderC", "2026-05-19T10:00:00Z"),
        )

        val groups = InboxRepository.groupItemsBySender(items)

        assertEquals(3, groups.size)
        assertEquals("senderA", groups[0].first) // max is 10:00 today
        assertEquals("senderB", groups[1].first) // max is 09:00 today
        assertEquals("senderC", groups[2].first) // max is 10:00 2 days ago
    }

    private fun createItem(id: String, senderId: String, sentAt: String) = InboxItem(
        id = id,
        senderId = senderId,
        senderName = senderId,
        pluginId = "p1",
        pluginIdentifier = "pi1",
        payloadJson = "{}",
        sentAt = sentAt,
        expiresAt = "2026-06-21T10:00:00Z",
        bundleJs = null,
        declaredEndpoints = emptyList(),
        bundleHash = null,
        revocationReason = null,
        hostPreview = null
    )
}
