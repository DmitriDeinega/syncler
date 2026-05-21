package app.syncler.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMergerTest {

    @Test
    fun `installed plugins merge by id with last-installed wins`() {
        val local = EncryptedUserState(
            installedPlugins = listOf(
                InstalledPluginRef("com.lottery", "s1", "1.0.0", "2026-05-20T10:00:00Z"),
            ),
        )
        val remote = EncryptedUserState(
            installedPlugins = listOf(
                InstalledPluginRef("com.lottery", "s1", "1.0.1", "2026-05-20T11:00:00Z"),
                InstalledPluginRef("com.trading", "s2", "1.0.0", "2026-05-20T09:00:00Z"),
            ),
        )
        val merged = StateMerger.merge(local, remote)
        assertEquals(2, merged.installedPlugins.size)
        val lottery = merged.installedPlugins.first { it.pluginId == "com.lottery" }
        assertEquals("1.0.1", lottery.version)  // newer installedAt wins
    }

    @Test
    fun `dismissed messages take union`() {
        val local = EncryptedUserState(
            dismissedMessages = listOf(
                DismissedMessageEntry("m1", "2026-05-20T10:00:00Z"),
            ),
        )
        val remote = EncryptedUserState(
            dismissedMessages = listOf(
                DismissedMessageEntry("m1", "2026-05-20T11:00:00Z"),
                DismissedMessageEntry("m2", "2026-05-20T12:00:00Z"),
            ),
        )
        val merged = StateMerger.merge(local, remote)
        assertEquals(2, merged.dismissedMessages.size)
        assertTrue(merged.dismissedMessages.any { it.messageId == "m1" })
        assertTrue(merged.dismissedMessages.any { it.messageId == "m2" })
    }

    @Test
    fun `plugin settings merge by id with last-modified wins`() {
        val local = EncryptedUserState(
            pluginSettings = mapOf(
                "com.lottery" to PluginSettings(
                    grantedCapabilities = listOf("network"),
                    modifiedAt = "2026-05-20T10:00:00Z",
                ),
            ),
        )
        val remote = EncryptedUserState(
            pluginSettings = mapOf(
                "com.lottery" to PluginSettings(
                    grantedCapabilities = listOf("network", "camera"),
                    modifiedAt = "2026-05-20T11:00:00Z",
                ),
            ),
        )
        val merged = StateMerger.merge(local, remote)
        assertEquals(listOf("network", "camera"), merged.pluginSettings["com.lottery"]!!.grantedCapabilities)
    }

    @Test
    fun `json round-trips through fromJson and toJson`() {
        val original = EncryptedUserState(
            installedPlugins = listOf(InstalledPluginRef("a", "s", "1.0", "2026-05-20T10:00:00Z")),
            dismissedMessages = listOf(DismissedMessageEntry("m1", "2026-05-20T10:00:00Z")),
            pluginSettings = mapOf("a" to PluginSettings(modifiedAt = "2026-05-20T10:00:00Z")),
            userScopedStorage = mapOf("a" to mapOf("k" to "v")),
            readMessages = listOf(ReadMessageEntry("m2", "2026-05-21T08:00:00Z")),
            archivedMessages = listOf(ArchivedMessageEntry("m3", "2026-05-21T09:00:00Z")),
        )
        val roundtrip = EncryptedUserState.fromJson(original.toJson())
        assertEquals(original.installedPlugins, roundtrip.installedPlugins)
        assertEquals(original.dismissedMessages, roundtrip.dismissedMessages)
        assertEquals(original.pluginSettings, roundtrip.pluginSettings)
        assertEquals(original.userScopedStorage, roundtrip.userScopedStorage)
        assertEquals(original.readMessages, roundtrip.readMessages)
        assertEquals(original.archivedMessages, roundtrip.archivedMessages)
    }

    @Test
    fun `read messages merge by id with max readAt wins`() {
        val local = EncryptedUserState(
            readMessages = listOf(
                ReadMessageEntry("m1", "2026-05-20T10:00:00Z"),
                ReadMessageEntry("m2", "2026-05-20T12:00:00Z"),
            ),
        )
        val remote = EncryptedUserState(
            readMessages = listOf(
                ReadMessageEntry("m1", "2026-05-20T11:00:00Z"),  // newer
                ReadMessageEntry("m3", "2026-05-20T13:00:00Z"),  // only on remote
            ),
        )
        val merged = StateMerger.merge(local, remote)
        assertEquals(3, merged.readMessages.size)
        val m1 = merged.readMessages.first { it.messageId == "m1" }
        assertEquals("2026-05-20T11:00:00Z", m1.readAt)  // newer remote read time wins
    }

    @Test
    fun `archived messages merge by id with max archivedAt wins`() {
        val local = EncryptedUserState(
            archivedMessages = listOf(ArchivedMessageEntry("m1", "2026-05-20T10:00:00Z")),
        )
        val remote = EncryptedUserState(
            archivedMessages = listOf(
                ArchivedMessageEntry("m1", "2026-05-20T11:00:00Z"),
                ArchivedMessageEntry("m2", "2026-05-20T12:00:00Z"),
            ),
        )
        val merged = StateMerger.merge(local, remote)
        assertEquals(2, merged.archivedMessages.size)
        val m1 = merged.archivedMessages.first { it.messageId == "m1" }
        assertEquals("2026-05-20T11:00:00Z", m1.archivedAt)
    }

    @Test
    fun `timestamp comparison uses Instant not string lex`() {
        // Real-time order: T2 (with .500 millis) is LATER than T1 (whole seconds).
        // Lexicographic comparison would put "T1Z" AFTER "T0.500Z" because 'Z' > '.'
        // in ASCII, which would let the wrong entry win the merge.
        val earlier = "2026-05-21T10:00:00Z"
        val later = "2026-05-21T10:00:00.500Z"
        val local = EncryptedUserState(
            readMessages = listOf(ReadMessageEntry("m1", earlier)),
        )
        val remote = EncryptedUserState(
            readMessages = listOf(ReadMessageEntry("m1", later)),
        )
        val merged = StateMerger.merge(local, remote)
        val m1 = merged.readMessages.first { it.messageId == "m1" }
        // The .500ms timestamp must win — that's the genuinely later one.
        assertEquals(later, m1.readAt)
    }

    @Test
    fun `V1 blob without read messages parses as empty list and forward-migrates`() {
        // Simulate a blob written by a pre-M11.2 device that lacked the
        // read_messages and archived_messages fields entirely.
        val v1Json = """{
            "schema_version": 1,
            "installed_plugins": [],
            "dismissed_messages": [{"message_id":"m1","dismissed_at":"2026-05-20T10:00:00Z"}],
            "plugin_settings": {},
            "user_scoped_storage": {}
        }""".trimIndent()
        val parsed = EncryptedUserState.fromJson(v1Json)
        assertEquals(EncryptedUserState.SCHEMA_CURRENT, parsed.schemaVersion)
        assertEquals(emptyList<ReadMessageEntry>(), parsed.readMessages)
        assertEquals(emptyList<ArchivedMessageEntry>(), parsed.archivedMessages)
        assertEquals(1, parsed.dismissedMessages.size)
    }
}
