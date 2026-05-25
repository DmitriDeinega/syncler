package app.syncler.core.network

import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Phase 5a-2.1 — BrokerApi DTO serialization + auth-header isolation.
 *
 * Two consultation-87 constraints:
 *  1. BootstrapEnvelopeDto must serialize with snake_case JSON keys
 *     so the Python broker app sees the exact same wire format the
 *     spec defines.
 *  2. The broker POST goes to a SENDER-CONTROLLED URL. The client
 *     used for this call must NOT inject an `Authorization` header
 *     (no auth interceptor) and must NOT inherit logging interceptors
 *     from the main client.
 */
class BrokerApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BrokerApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Plain OkHttpClient — no interceptors, mirroring what
        // NetworkModule.provideBrokerOkHttpClient() builds.
        val client = OkHttpClient.Builder().build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
            .build()
            .create(BrokerApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sampleDto() = BootstrapEnvelopeDto(
        protocolVersion = 1,
        pairingId = "00000000-1111-2222-3333-444444444444",
        senderId = "55555555-6666-7777-8888-999999999999",
        bootstrapKeyId = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        exp = "2026-05-25T00:00:00Z",
        ephemeralPubkey = "Bw==",
        nonce = "AQEBAQEBAQEBAQEB",
        ciphertext = "Y2lwaGVydGV4dGV4dGV4dGV4dGV4dGV4dGV4dA==",
    )

    @Test
    fun `BootstrapEnvelopeDto serializes with exactly the 8 spec snake_case keys`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        api.postBootstrapEnvelope(server.url("/").toString(), sampleDto())
        val recorded: RecordedRequest = server.takeRequest()
        val body = JSONObject(recorded.body.readUtf8())
        val keys = body.keys().asSequence().toSortedSet()
        assertEquals(
            sortedSetOf(
                "protocol_version", "pairing_id", "sender_id",
                "bootstrap_key_id", "exp", "ephemeral_pubkey",
                "nonce", "ciphertext",
            ),
            keys,
        )
        // Spot-check a couple of values to confirm @Json names map
        // camelCase Kotlin -> snake_case JSON.
        assertEquals(1, body.getInt("protocol_version"))
        assertEquals(
            "00000000-1111-2222-3333-444444444444",
            body.getString("pairing_id"),
        )
        assertEquals("Bw==", body.getString("ephemeral_pubkey"))
    }

    @Test
    fun `broker POST sends NO Authorization header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        api.postBootstrapEnvelope(server.url("/").toString(), sampleDto())
        val recorded = server.takeRequest()
        // The unauthenticated client must not leak the user's session
        // token to the sender-controlled broker URL.
        assertNull(
            "Authorization header leaked to broker URL",
            recorded.getHeader("Authorization"),
        )
    }

    @Test
    fun `broker POST emits Content-Type application slash json`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        api.postBootstrapEnvelope(server.url("/").toString(), sampleDto())
        val recorded = server.takeRequest()
        val contentType = recorded.getHeader("Content-Type") ?: ""
        assertTrue(
            "expected Content-Type to be JSON, got $contentType",
            contentType.startsWith("application/json"),
        )
    }
}
