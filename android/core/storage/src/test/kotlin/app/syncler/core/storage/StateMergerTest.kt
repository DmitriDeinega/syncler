package app.syncler.core.storage

import org.json.JSONObject
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
            deletedMessages = listOf(DeletedMessageEntry("m4", "2026-05-21T10:00:00Z")),
        )
        val roundtrip = EncryptedUserState.fromJson(original.toJson())
        assertEquals(original.installedPlugins, roundtrip.installedPlugins)
        assertEquals(original.dismissedMessages, roundtrip.dismissedMessages)
        assertEquals(original.pluginSettings, roundtrip.pluginSettings)
        assertEquals(original.userScopedStorage, roundtrip.userScopedStorage)
        assertEquals(original.readMessages, roundtrip.readMessages)
        assertEquals(original.archivedMessages, roundtrip.archivedMessages)
        assertEquals(original.deletedMessages, roundtrip.deletedMessages)
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
    fun `deleted messages merge by id with max deletedAt wins`() {
        val local = EncryptedUserState(
            deletedMessages = listOf(DeletedMessageEntry("m1", "2026-05-20T10:00:00Z")),
        )
        val remote = EncryptedUserState(
            deletedMessages = listOf(
                DeletedMessageEntry("m1", "2026-05-20T11:00:00Z"),
                DeletedMessageEntry("m2", "2026-05-20T12:00:00Z"),
            ),
        )
        val merged = StateMerger.merge(local, remote)
        assertEquals(2, merged.deletedMessages.size)
        val m1 = merged.deletedMessages.first { it.messageId == "m1" }
        assertEquals("2026-05-20T11:00:00Z", m1.deletedAt)
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
    fun `V2 blob without deleted messages parses as empty list and forward-migrates`() {
        // Simulate a blob written by a pre-M11.6 device that lacked the
        // deleted_messages field.
        val v2Json = """{
            "schema_version": 2,
            "installed_plugins": [],
            "dismissed_messages": [{"message_id":"m1","dismissed_at":"2026-05-20T10:00:00Z"}],
            "plugin_settings": {},
            "user_scoped_storage": {},
            "read_messages": [],
            "archived_messages": []
        }""".trimIndent()
        val parsed = EncryptedUserState.fromJson(v2Json)
        assertEquals(EncryptedUserState.SCHEMA_CURRENT, parsed.schemaVersion)
        assertEquals(emptyList<ReadMessageEntry>(), parsed.readMessages)
        assertEquals(emptyList<ArchivedMessageEntry>(), parsed.archivedMessages)
        assertEquals(emptyList<DeletedMessageEntry>(), parsed.deletedMessages)
        assertEquals(emptyList<PairedSenderEntry>(), parsed.pairedSenders)
        assertEquals(1, parsed.dismissedMessages.size)
    }

    // ---------- Phase 1: pairedSenders merge ----------

    @Test
    fun `paired senders merge by pairing id, oldest firstPairedAt wins on add conflict`() {
        // Two devices accidentally created entries with the same pairingId
        // (essentially impossible with fresh UUIDs, but the merger must
        // still be deterministic). The earlier firstPairedAt wins.
        val older = paired("p1", "s1", firstPairedAt = "2026-05-20T10:00:00Z")
        val newer = paired("p1", "s1", firstPairedAt = "2026-05-20T11:00:00Z")
        val merged = StateMerger.merge(
            local = EncryptedUserState(pairedSenders = listOf(older)),
            remote = EncryptedUserState(pairedSenders = listOf(newer)),
        )
        assertEquals(1, merged.pairedSenders.size)
        assertEquals(older.firstPairedAt, merged.pairedSenders.single().firstPairedAt)
    }

    @Test
    fun `paired senders take union when pairing ids differ`() {
        val a = paired("p-a", "s1", firstPairedAt = "2026-05-20T10:00:00Z")
        val b = paired("p-b", "s2", firstPairedAt = "2026-05-20T11:00:00Z")
        val merged = StateMerger.merge(
            local = EncryptedUserState(pairedSenders = listOf(a)),
            remote = EncryptedUserState(pairedSenders = listOf(b)),
        )
        assertEquals(2, merged.pairedSenders.size)
        assertTrue(merged.pairedSenders.any { it.pairingId == "p-a" })
        assertTrue(merged.pairedSenders.any { it.pairingId == "p-b" })
    }

    @Test
    fun `paired sender tombstone wins over active entry`() {
        val active = paired("p1", "s1", firstPairedAt = "2026-05-20T10:00:00Z")
        val tombstoned = active.copy(removedAt = "2026-05-21T10:00:00Z")
        // Device A still has the active entry; device B revoked it. Merge
        // must keep the tombstone so the offline-A pairing doesn't come back.
        val merged = StateMerger.merge(
            local = EncryptedUserState(pairedSenders = listOf(active)),
            remote = EncryptedUserState(pairedSenders = listOf(tombstoned)),
        )
        assertEquals(1, merged.pairedSenders.size)
        assertEquals("2026-05-21T10:00:00Z", merged.pairedSenders.single().removedAt)
    }

    @Test
    fun `paired sender later tombstone wins when both sides tombstoned`() {
        // Defensive: if both sides have a tombstone (e.g. concurrent
        // revoke from two devices), the later removedAt wins. Stays
        // monotone.
        val first = paired("p1", "s1").copy(removedAt = "2026-05-21T10:00:00Z")
        val later = paired("p1", "s1").copy(removedAt = "2026-05-21T11:00:00Z")
        val merged = StateMerger.merge(
            local = EncryptedUserState(pairedSenders = listOf(first)),
            remote = EncryptedUserState(pairedSenders = listOf(later)),
        )
        assertEquals("2026-05-21T11:00:00Z", merged.pairedSenders.single().removedAt)
    }

    @Test
    fun `muted senders take union`() {
        val local = EncryptedUserState(mutedSenders = listOf("s1"))
        val remote = EncryptedUserState(mutedSenders = listOf("s1", "s2"))
        val merged = StateMerger.merge(local, remote)
        assertEquals(2, merged.mutedSenders.size)
        assertTrue(merged.mutedSenders.contains("s1"))
        assertTrue(merged.mutedSenders.contains("s2"))
    }

    @Test
    fun `paired senders survive schema V3 to V4 forward migration`() {
        // A V3 blob (pre-pairedSenders) parsed by a V4 client must yield
        // an empty pairedSenders list and the SCHEMA_CURRENT (V5) tag.
        val v3Json = """{
            "schema_version": 3,
            "installed_plugins": [],
            "dismissed_messages": [],
            "plugin_settings": {},
            "user_scoped_storage": {},
            "read_messages": [],
            "archived_messages": [],
            "deleted_messages": []
        }""".trimIndent()
        val parsed = EncryptedUserState.fromJson(v3Json)
        assertEquals(EncryptedUserState.SCHEMA_CURRENT, parsed.schemaVersion)
        assertEquals(emptyList<PairedSenderEntry>(), parsed.pairedSenders)
    }

    @Test
    fun `V4 blob without muted senders parses as empty list and forward-migrates`() {
        // V4 #19 bumps SCHEMA_CURRENT to V6; forward-migrate
        // path treats every old schema (V0..V5) the same way.
        val v4Json = """{
            "schema_version": 4,
            "installed_plugins": [],
            "dismissed_messages": [],
            "plugin_settings": {},
            "user_scoped_storage": {},
            "read_messages": [],
            "archived_messages": [],
            "deleted_messages": [],
            "paired_senders": []
        }""".trimIndent()
        val parsed = EncryptedUserState.fromJson(v4Json)
        assertEquals(EncryptedUserState.SCHEMA_CURRENT, parsed.schemaVersion)
        assertEquals(emptyList<String>(), parsed.mutedSenders)
    }

    @Test
    fun `V4 #19 plugin prefs round trip with all new fields`() {
        val original = PluginSettings(
            grantedCapabilities = listOf("network"),
            dismissBehaviorOverride = "dismiss_local_only",
            modifiedAt = "2026-05-26T22:00:00Z",
            labelOverride = "My Lottery",
            notificationCadence = PluginSettings.NOTIFICATION_CADENCE_BATCHED_15M,
            quietHours = QuietHours(
                enabled = true,
                startLocalHour = 22,
                endLocalHour = 7,
                timezone = "America/New_York",
            ),
            muted = true,
        )
        val parsed = PluginSettings.fromJson(original.toJson())
        assertEquals(original, parsed.copy(unknownFields = emptyMap()))
    }

    @Test
    fun `V4 #19 PluginSettings forward-compat preserves unknown fields`() {
        // A future device wrote "ringtone_uri"; the current
        // device must not drop it on writeback (codex 149 #10).
        val futureJson = JSONObject().apply {
            put("modified_at", "2026-05-26T22:00:00Z")
            put("notification_cadence", "realtime")
            put("ringtone_uri", "content://media/ring/42")
        }
        val parsed = PluginSettings.fromJson(futureJson)
        assertEquals(
            "content://media/ring/42",
            parsed.unknownFields["ringtone_uri"],
        )
        val rewritten = parsed.toJson()
        assertEquals(
            "content://media/ring/42",
            rewritten.optString("ringtone_uri"),
        )
    }

    @Test
    fun `V4 #19 PluginSettings unknown cadence falls back to realtime`() {
        val o = JSONObject().apply {
            put("modified_at", "2026-05-26T22:00:00Z")
            put("notification_cadence", "every_3_minutes_made_up_by_v0_2")
        }
        val parsed = PluginSettings.fromJson(o)
        assertEquals(
            PluginSettings.NOTIFICATION_CADENCE_REALTIME,
            parsed.notificationCadence,
        )
    }

    @Test
    fun `paired sender entry round trips through JSON`() {
        val original = paired(
            "p1", "s1",
            firstPairedAt = "2026-05-22T10:00:00Z",
        ).copy(removedAt = "2026-05-23T11:00:00Z", source = "migration")

        val parsed = PairedSenderEntry.fromJson(original.toJson())
        assertEquals(original, parsed)
    }

    @Test
    fun `phase1MigrationDoneAt is sticky-once-set across merges`() {
        // Local has the flag, remote doesn't → keep local.
        val local = EncryptedUserState(phase1MigrationDoneAt = "2026-05-22T10:00:00Z")
        val remote = EncryptedUserState()
        assertEquals(
            "2026-05-22T10:00:00Z",
            StateMerger.merge(local, remote).phase1MigrationDoneAt,
        )
        // Symmetric.
        assertEquals(
            "2026-05-22T10:00:00Z",
            StateMerger.merge(remote, local).phase1MigrationDoneAt,
        )
    }

    @Test
    fun `phase1MigrationDoneAt picks the earlier non-null timestamp on both-set`() {
        // Both sides set → keep the earlier (whichever device migrated first
        // is authoritative). Sticky-monotone: never null after set.
        val earlier = EncryptedUserState(phase1MigrationDoneAt = "2026-05-22T10:00:00Z")
        val later = EncryptedUserState(phase1MigrationDoneAt = "2026-05-22T11:00:00Z")
        assertEquals(
            "2026-05-22T10:00:00Z",
            StateMerger.merge(earlier, later).phase1MigrationDoneAt,
        )
        assertEquals(
            "2026-05-22T10:00:00Z",
            StateMerger.merge(later, earlier).phase1MigrationDoneAt,
        )
    }

    @Test
    fun `phase1MigrationDoneAt stays null when neither side has set it`() {
        val merged = StateMerger.merge(EncryptedUserState(), EncryptedUserState())
        assertEquals(null, merged.phase1MigrationDoneAt)
    }

    private fun paired(
        pairingId: String,
        senderId: String,
        firstPairedAt: String = "2026-05-22T10:00:00Z",
    ) = PairedSenderEntry(
        pairingId = pairingId,
        senderId = senderId,
        senderName = "Sender $senderId",
        senderPublicKey = "cHVibGlja2V5",
        fingerprint = "fp:$senderId",
        nameHash = "bmFtZWhhc2g=",
        pairingKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        firstPairedAt = firstPairedAt,
    )
}
