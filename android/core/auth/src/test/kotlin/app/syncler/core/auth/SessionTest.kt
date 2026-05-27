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
        assertArrayEquals(masterKey, masterKeyStore.read())
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
        assertArrayEquals(masterKey, masterKeyStore.read())

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
