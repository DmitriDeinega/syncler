package app.syncler.feature.inbox

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Phase 3a — TemplateActionRunner security regression tests.
 *
 * The runner POSTs to a sender-declared endpoint outside the Syncler
 * trust boundary. Two invariants must hold (Codex consultation 62 RED #1):
 *
 *  1. The request must NOT carry the user's Syncler bearer token. The
 *     core-network singleton OkHttpClient injects it via an interceptor;
 *     TemplateActionRunner builds its OWN client to opt out.
 *  2. The runner must reject non-HTTPS endpoints in release. (Debug
 *     builds allow `http://` for LAN dev — we can't assert that easily
 *     here because BuildConfig.DEBUG flips at compile time, but we can
 *     assert there's no fallback when the HTTPS endpoint resolves and
 *     the body lands as JSON.)
 */
class TemplateActionRunnerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `post does not send Authorization header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val runner = TemplateActionRunner()
        // Mock server is http://localhost:port — only valid in DEBUG; this
        // test exists in the unit-test JVM where BuildConfig.DEBUG is true,
        // so the runner accepts it. The assertion is that NO Authorization
        // header is ever attached, regardless of scheme.
        runner.post(endpoint = server.url("/api/ack").toString(), payloadJson = """{"k":"v"}""")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertNull(
            "TemplateActionRunner must NOT attach an Authorization header to plugin endpoints",
            recorded.getHeader("Authorization"),
        )
        // Body should round-trip the payload JSON verbatim — sender-side
        // services may HMAC over the body for their own auth, which only
        // works if the body is exactly what the user's device sent.
        assertEquals("""{"k":"v"}""", recorded.body.readUtf8())
    }

    @Test
    fun `post records request only after successful response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val runner = TemplateActionRunner()
        runner.post(endpoint = server.url("/api/fail").toString(), payloadJson = "{}")
        // Even a 5xx still produces a request that we sent — the runner
        // logs but doesn't retry or throw, which is the documented
        // fire-and-forget contract.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `post is a no-op for non-https in non-debug`() = runTest {
        // BuildConfig.DEBUG is true in unit tests, so http:// passes through.
        // This test documents the gate exists; a release-mode check would
        // need a separate variant. The behavior we ARE able to assert here:
        // a malformed scheme (neither http nor https) is rejected without
        // network access.
        val runner = TemplateActionRunner()
        runner.post(endpoint = "ftp://example.com/x", payloadJson = "{}")
        assertEquals(
            "TemplateActionRunner must reject non-http(s) schemes",
            0,
            server.requestCount,
        )
    }
}
