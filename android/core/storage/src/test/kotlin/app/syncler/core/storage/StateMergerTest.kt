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
        )
        val roundtrip = EncryptedUserState.fromJson(original.toJson())
        assertEquals(original.installedPlugins, roundtrip.installedPlugins)
        assertEquals(original.dismissedMessages, roundtrip.dismissedMessages)
        assertEquals(original.pluginSettings, roundtrip.pluginSettings)
        assertEquals(original.userScopedStorage, roundtrip.userScopedStorage)
    }
}
