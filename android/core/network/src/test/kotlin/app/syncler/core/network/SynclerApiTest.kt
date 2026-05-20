package app.syncler.core.network

import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SynclerApiTest {
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
    fun signupPostsExpectedJson() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"user_id":"u1","created_at":"now"}"""))
        val api = api(token = null)

        val response = api.signup(
            SignupRequest(
                email = "user@example.com",
                authKeyHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa=",
                encryptedMasterKey = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                authSalt = "cccccccccccccccccccccw==",
                argon2ParamsVersion = 1,
            ),
        )
        val request = server.takeRequest()

        assertEquals("u1", response.userId)
        assertEquals("/v1/auth/signup", request.path)
        assertEquals("POST", request.method)
        assertEquals(null, request.getHeader("Authorization"))
        assertEquals(true, request.body.readUtf8().contains("auth_key_hash"))
    }

    @Test
    fun authInterceptorAddsBearerToken() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        val api = api(token = "jwt-token")

        api.listDevices()
        val request = server.takeRequest()

        assertEquals("Bearer jwt-token", request.getHeader("Authorization"))
    }

    @Test
    fun preLoginPostsExpectedJson() = runTest {
        server.enqueue(MockResponse().setBody("""{"auth_salt":"cccccccccccccccccw==","argon2_params_version":1}"""))
        val api = api(token = null)

        val response = api.preLogin(PreLoginRequest(email = "user@example.com"))
        val request = server.takeRequest()

        assertEquals("cccccccccccccccccw==", response.authSalt)
        assertEquals(1, response.argon2ParamsVersion)
        assertEquals("/v1/auth/pre-login", request.path)
        assertEquals("POST", request.method)
        assertEquals(true, request.body.readUtf8().contains("user@example.com"))
    }

    private fun api(token: String?): SynclerApi {
        val interceptor = NetworkModule.provideAuthInterceptor(
            setOf(object : AuthTokenProvider {
                override fun currentToken(): String? = token
            }),
        )
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
            .build()
            .create(SynclerApi::class.java)
    }
}
