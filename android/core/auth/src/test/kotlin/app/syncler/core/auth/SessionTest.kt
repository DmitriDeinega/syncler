package app.syncler.core.auth

import app.cash.turbine.test
import app.syncler.core.crypto.KEY_SIZE_BYTES
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTest {
    @Test
    fun sessionEmitsUnlockedStateAndClearsTokenOnLogout() = runTest {
        val tokenStore = object : TokenStore {
            var token: String? = null
            override fun readToken(): String? = token
            override fun writeToken(token: String) {
                this.token = token
            }
            override fun clearToken() {
                token = null
            }
        }
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
}
