package app.syncler.core.auth

import app.cash.turbine.test
import app.syncler.core.crypto.KEY_SIZE_BYTES
import app.syncler.core.storage.InMemoryMasterKeyStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTest {
    private fun newTokenStore(initial: String? = null) = object : TokenStore {
        var token: String? = initial
        override fun readToken(): String? = token
        override fun writeToken(token: String) {
            this.token = token
        }
        override fun clearToken() {
            token = null
        }
    }

    @Test
    fun sessionEmitsUnlockedStateAndClearsTokenOnLogout() = runTest {
        val tokenStore = newTokenStore()
        val session = Session(tokenStore)

        session.isUnlocked.test {
            assertEquals(false, awaitItem())
            session.authenticate("jwt", ByteArray(KEY_SIZE_BYTES))
            assertEquals(true, awaitItem())
            session.logout()
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * V4 #20: a successful login persists the master key under the
     * Keystore-protected MasterKeyStore; cold-start re-creates the
     * unlocked SessionState without prompting for a password.
     */
    @Test
    fun authenticatePersistsMasterKeyForColdStart() = runTest {
        val masterKey = ByteArray(KEY_SIZE_BYTES) { 0x42.toByte() }
        val masterKeyStore = InMemoryMasterKeyStore()
        val tokenStore = newTokenStore()

        // Simulate a fresh login.
        Session(tokenStore, masterKeyStore).authenticate("jwt", masterKey)
        val persisted = masterKeyStore.read()!!
        assertArrayEquals(masterKey, persisted.masterKey)
        assertEquals(1, persisted.keyGeneration)
        assertEquals("jwt", tokenStore.token)

        // New Session instance (simulates process restart). Should
        // boot directly into an unlocked state without re-auth.
        val coldStart = Session(tokenStore, masterKeyStore)
        coldStart.isUnlocked.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Triad 167 codex must-fix: cold start MUST restore the
     * persisted keyGeneration, not hardcode 1. Without this, a
     * rotation persisted in a previous process is silently undone
     * on next cold start.
     */
    @Test
    fun coldStartRestoresPersistedKeyGeneration() = runTest {
        val masterKey = ByteArray(KEY_SIZE_BYTES) { 0x11.toByte() }
        val masterKeyStore = InMemoryMasterKeyStore()
        val tokenStore = newTokenStore()
        // Simulate a session that was authenticated under gen 5 and
        // then process died.
        Session(tokenStore, masterKeyStore).authenticate(
            token = "jwt", masterKey = masterKey, keyGeneration = 5,
        )

        val coldStart = Session(tokenStore, masterKeyStore)
        assertEquals(5, coldStart.currentKeyGeneration())
    }

    /**
     * Triad 167 codex must-fix: updateAfterRotation MUST persist
     * the new (key, generation) atomically so a process death right
     * after rotation cannot resurrect the stale key on cold start.
     */
    @Test
    fun updateAfterRotationPersistsNewKeyAndGeneration() = runTest {
        val initialKey = ByteArray(KEY_SIZE_BYTES) { 0x22.toByte() }
        val rotatedKey = ByteArray(KEY_SIZE_BYTES) { 0x33.toByte() }
        val masterKeyStore = InMemoryMasterKeyStore()
        val tokenStore = newTokenStore()
        val session = Session(tokenStore, masterKeyStore)
        session.authenticate("jwt", initialKey, keyGeneration = 1)

        session.updateAfterRotation(newMasterKey = rotatedKey, newKeyGeneration = 2)

        val persisted = masterKeyStore.read()!!
        assertArrayEquals(rotatedKey, persisted.masterKey)
        assertEquals(2, persisted.keyGeneration)
        // Cold start sees the rotated state, not the original.
        assertEquals(2, Session(tokenStore, masterKeyStore).currentKeyGeneration())
    }

    /**
     * V4 #20 sign-out wipes the persisted master key — the "atomic
     * with persistence" rule both triad reviewers demanded. Without
     * this, a logged-out app could cold-boot back into an unlocked
     * state on the next launch.
     */
    @Test
    fun logoutWipesPersistedMasterKey() = runTest {
        val masterKey = ByteArray(KEY_SIZE_BYTES) { 0x99.toByte() }
        val masterKeyStore = InMemoryMasterKeyStore()
        val tokenStore = newTokenStore()
        val session = Session(tokenStore, masterKeyStore)

        session.authenticate("jwt", masterKey)
        assertArrayEquals(masterKey, masterKeyStore.read()!!.masterKey)

        session.logout()
        assertNull(masterKeyStore.read())
        assertNull(tokenStore.token)

        // Cold start after logout — still locked.
        val coldStart = Session(tokenStore, masterKeyStore)
        coldStart.isUnlocked.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Boundary: only a token without a persisted master key means the
     * app launches into the locked state — same outcome as a clean
     * install. Guards against an edge where the JWT survives a
     * partial wipe.
     */
    @Test
    fun tokenWithoutMasterKeyBootsLocked() = runTest {
        val tokenStore = newTokenStore(initial = "lingering-jwt")
        val masterKeyStore = InMemoryMasterKeyStore()  // never written

        val session = Session(tokenStore, masterKeyStore)
        session.isUnlocked.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
