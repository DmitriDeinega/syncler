package app.syncler.core.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Phase 1 synced paired-sender semantics. Exercises
 * the store's interaction with the [UserStateMutator] interface
 * directly so we don't need Android EncryptedSharedPreferences in the
 * test environment.
 */
class SyncedPairedSenderStoreLogicTest {

    @Test
    fun `add pushes new entry into synced state`() = runTest {
        val mutator = FakeUserStateMutator()
        val store = TestPairedSenderStore(mutator)

        store.add(samplePairedSender("p1", "s1"))
        advanceUntilIdle()

        val entries = mutator.current().pairedSenders
        assertEquals(1, entries.size)
        assertEquals("p1", entries.single().pairingId)
        assertEquals("manual", entries.single().source)
        assertNull(entries.single().removedAt)
    }

    @Test
    fun `add tombstones existing active pairing for the same sender on re-pair`() = runTest {
        val mutator = FakeUserStateMutator()
        val store = TestPairedSenderStore(mutator)

        store.add(samplePairedSender("p1", "s1"))
        advanceUntilIdle()
        store.add(samplePairedSender("p2", "s1"))  // re-pair: new pairingId, same senderId
        advanceUntilIdle()

        val entries = mutator.current().pairedSenders.associateBy { it.pairingId }
        assertEquals(2, entries.size)
        assertNotNull("old pairing must be tombstoned", entries["p1"]!!.removedAt)
        assertNull("new pairing must be active", entries["p2"]!!.removedAt)
        // bySenderId returns the newest active one (p2).
        assertEquals("p2", store.bySenderId("s1")?.pairingId)
        // activePairingsForSender now lists only p2.
        assertEquals(listOf("p2"), store.activePairingsForSender("s1").map { it.pairingId })
    }

    @Test
    fun `add for unrelated sender does not touch other pairings`() = runTest {
        val mutator = FakeUserStateMutator()
        val store = TestPairedSenderStore(mutator)

        store.add(samplePairedSender("p1", "s1"))
        advanceUntilIdle()
        store.add(samplePairedSender("p2", "s2"))
        advanceUntilIdle()

        val entries = mutator.current().pairedSenders
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.removedAt == null })
    }

    @Test
    fun `remove tombstones the matching pairing instead of dropping it`() = runTest {
        val mutator = FakeUserStateMutator()
        val store = TestPairedSenderStore(mutator)

        store.add(samplePairedSender("p1", "s1"))
        advanceUntilIdle()
        store.remove("p1")
        advanceUntilIdle()

        val entries = mutator.current().pairedSenders
        assertEquals(1, entries.size)
        assertNotNull("must keep the entry as a tombstone", entries.single().removedAt)
        // Active projection filters out the tombstoned entry.
        assertNull(store.byPairingId("p1"))
        assertEquals(emptyList<PairedSender>(), store.activePairingsForSender("s1"))
    }

    @Test
    fun `projection filters out tombstoned entries from active list`() = runTest {
        val mutator = FakeUserStateMutator(
            initial = EncryptedUserState(
                pairedSenders = listOf(
                    samplePairedSenderEntry("p-active", "s1"),
                    samplePairedSenderEntry("p-tombstoned", "s1").copy(removedAt = "2026-05-22T10:00:00Z"),
                ),
            ),
        )
        val store = TestPairedSenderStore(mutator)
        advanceUntilIdle()

        val active = store.activePairingsForSender("s1")
        assertEquals(1, active.size)
        assertEquals("p-active", active.single().pairingId)
        assertNull(store.byPairingId("p-tombstoned"))
    }

    @Test
    fun `bySenderId returns the newest active pairing during transition`() = runTest {
        // Pre-sync transition: two devices each paired for the same sender.
        // After migration neither is tombstoned (re-pair has not happened).
        // bySenderId must return the newest by firstPairedAt.
        val mutator = FakeUserStateMutator(
            initial = EncryptedUserState(
                pairedSenders = listOf(
                    samplePairedSenderEntry("p-old", "s1").copy(firstPairedAt = "2026-05-20T10:00:00Z"),
                    samplePairedSenderEntry("p-new", "s1").copy(firstPairedAt = "2026-05-21T10:00:00Z"),
                ),
            ),
        )
        val store = TestPairedSenderStore(mutator)
        advanceUntilIdle()

        val resolved = store.bySenderId("s1")
        assertEquals("p-new", resolved?.pairingId)
        assertEquals(2, store.activePairingsForSender("s1").size)
    }

    @Test
    fun `idempotent add does not duplicate the same pairing`() = runTest {
        val mutator = FakeUserStateMutator()
        val store = TestPairedSenderStore(mutator)

        store.add(samplePairedSender("p1", "s1"))
        advanceUntilIdle()
        store.add(samplePairedSender("p1", "s1"))  // retry; same id
        advanceUntilIdle()

        val entries = mutator.current().pairedSenders
        assertEquals(1, entries.size)
        assertEquals("p1", entries.single().pairingId)
    }
}

/**
 * Logic-only variant of [SyncedPairedSenderStore] that owns the same
 * mutator-projection + add/remove semantics without the
 * `EncryptedSharedPreferences` initialisation that requires Android
 * Context. Allows the test to exercise the Phase 1 sync semantics in a
 * pure-JVM unit test (Robolectric would let us drive the production
 * class directly; until that's set up, this duplicate logic + tested
 * production class is the simplest seam).
 */
private class TestPairedSenderStore(
    private val mutator: FakeUserStateMutator,
) {
    private val clock = java.time.Clock.fixed(java.time.Instant.parse("2026-05-25T12:00:00Z"), java.time.ZoneOffset.UTC)

    suspend fun add(pairedSender: PairedSender) {
        val now = java.time.Instant.now(clock).toString()
        mutator.mutateAndPush { state ->
            val rotated = state.pairedSenders.map { existing ->
                if (existing.senderId == pairedSender.senderId &&
                    existing.removedAt == null &&
                    existing.pairingId != pairedSender.pairingId
                ) {
                    existing.copy(removedAt = now)
                } else existing
            }
            val withoutNew = rotated.filterNot { it.pairingId == pairedSender.pairingId }
            state.copy(
                pairedSenders = withoutNew + pairedSender.toEntry("manual"),
            )
        }
    }

    suspend fun remove(pairingId: String) {
        val now = java.time.Instant.now(clock).toString()
        mutator.mutateAndPush { state ->
            state.copy(
                pairedSenders = state.pairedSenders.map { e ->
                    if (e.pairingId == pairingId && e.removedAt == null) e.copy(removedAt = now) else e
                },
            )
        }
    }

    fun activePairingsForSender(senderId: String): List<PairedSender> =
        mutator.current().pairedSenders
            .filter { it.senderId == senderId && it.removedAt == null }
            .map { it.toPairedSender() }

    fun bySenderId(senderId: String): PairedSender? =
        activePairingsForSender(senderId).maxByOrNull { it.firstPairedAt }

    fun byPairingId(pairingId: String): PairedSender? =
        mutator.current().pairedSenders
            .firstOrNull { it.pairingId == pairingId && it.removedAt == null }
            ?.toPairedSender()
}

private class FakeUserStateMutator(
    initial: EncryptedUserState = EncryptedUserState(),
    unlocked: Boolean = true,
) : UserStateMutator {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<EncryptedUserState> = _state.asStateFlow()
    private val _isUnlocked = MutableStateFlow(unlocked)
    override val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    fun current(): EncryptedUserState = _state.value

    override suspend fun mutateAndPush(transform: (EncryptedUserState) -> EncryptedUserState) {
        _state.value = transform(_state.value)
    }
}

private fun samplePairedSender(pairingId: String, senderId: String): PairedSender =
    PairedSender(
        pairingId = pairingId,
        senderId = senderId,
        senderName = "Sender $senderId",
        senderPublicKey = ByteArray(32) { 0x11 },
        fingerprint = "fp:$senderId",
        nameHash = ByteArray(32) { 0x22 },
        firstPairedAt = "2026-05-25T11:00:00Z",
        pairingKey = ByteArray(32) { 0x33 },
    )

private fun samplePairedSenderEntry(pairingId: String, senderId: String): PairedSenderEntry =
    PairedSenderEntry(
        pairingId = pairingId,
        senderId = senderId,
        senderName = "Sender $senderId",
        senderPublicKey = "EREREREREREREREREREREREREREREREREREREREREREREQ==",
        fingerprint = "fp:$senderId",
        nameHash = "IiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiI=",
        pairingKey = "MzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzM=",
        firstPairedAt = "2026-05-22T10:00:00Z",
    )

private fun PairedSenderEntry.toPairedSender(): PairedSender {
    val dec = java.util.Base64.getDecoder()
    return PairedSender(
        pairingId = pairingId,
        senderId = senderId,
        senderName = senderName,
        senderPublicKey = dec.decode(senderPublicKey),
        fingerprint = fingerprint,
        nameHash = dec.decode(nameHash),
        firstPairedAt = firstPairedAt,
        pairingKey = dec.decode(pairingKey),
    )
}

private fun PairedSender.toEntry(source: String): PairedSenderEntry {
    val enc = java.util.Base64.getEncoder()
    return PairedSenderEntry(
        pairingId = pairingId,
        senderId = senderId,
        senderName = senderName,
        senderPublicKey = enc.encodeToString(senderPublicKey),
        fingerprint = fingerprint,
        nameHash = enc.encodeToString(nameHash),
        pairingKey = enc.encodeToString(pairingKey),
        firstPairedAt = firstPairedAt,
        source = source,
    )
}
