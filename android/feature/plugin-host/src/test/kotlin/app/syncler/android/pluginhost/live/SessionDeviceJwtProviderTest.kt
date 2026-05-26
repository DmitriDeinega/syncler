package app.syncler.android.pluginhost.live

import app.syncler.android.pluginhost.AuditLogger
import app.syncler.core.auth.Session
import app.syncler.core.auth.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Triad 160 codex+gemini test for the V3 #14 device-JWT
 * provider plumbing.
 *
 * Three behavioral branches:
 *
 *   1. Session present + has token → returns token.
 *   2. Session present + null token (locked / signed
 *      out) → throws LiveChannelException("no_session",
 *      "no device JWT available (locked / signed out)").
 *   3. Session NULL (composition root bug or test
 *      default) → throws LiveChannelException("no_session",
 *      "session not wired into PluginLoader.android()").
 *
 * The two no_session branches share an error code but
 * carry distinct messages so the wiring gap can't
 * silently masquerade as a locked-state error in
 * post-mortem inspection.
 */
class SessionDeviceJwtProviderTest {

    @Test
    fun `returns token when session is wired and unlocked`() = runBlocking {
        val provider = SessionDeviceJwtProvider(
            session = Session(InMemoryTokenStore("device-jwt-abc")),
            pluginId = "com.example.plugin",
            auditLogger = AuditLogger(),
        )
        assertEquals("device-jwt-abc", provider())
    }

    @Test
    fun `throws no_session when session returns null token`() = runBlocking {
        val provider = SessionDeviceJwtProvider(
            session = Session(InMemoryTokenStore(null)),
            pluginId = "com.example.plugin",
            auditLogger = AuditLogger(),
        )
        try {
            provider()
            fail("expected LiveChannelException")
        } catch (exc: LiveChannelException) {
            assertEquals("no_session", exc.code)
            assertNotNull(exc.message)
            // Distinct message for the locked branch.
            assert(exc.message!!.contains("locked")) {
                "expected 'locked' in message; got '${exc.message}'"
            }
        }
    }

    @Test
    fun `throws no_session when session is null (wiring gap)`() = runBlocking {
        val provider = SessionDeviceJwtProvider(
            session = null,
            pluginId = "com.example.plugin",
            auditLogger = AuditLogger(),
        )
        try {
            provider()
            fail("expected LiveChannelException")
        } catch (exc: LiveChannelException) {
            assertEquals("no_session", exc.code)
            // Distinct message for the wiring-gap branch so
            // post-mortem inspection can distinguish the
            // composition-root bug from the lock-state
            // error.
            assert(exc.message!!.contains("not wired")) {
                "expected 'not wired' in message; got '${exc.message}'"
            }
        }
    }

    private class InMemoryTokenStore(private val token: String?) : TokenStore {
        override fun readToken(): String? = token
        override fun writeToken(token: String) {}
        override fun clearToken() {}
    }
}
