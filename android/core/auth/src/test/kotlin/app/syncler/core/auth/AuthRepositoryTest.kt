package app.syncler.core.auth

import app.syncler.core.crypto.KEY_SIZE_BYTES
import app.syncler.core.network.DeviceEnrollRequest
import app.syncler.core.network.DeviceEnrollResponse
import app.syncler.core.network.DeviceItem
import app.syncler.core.network.LoginRequest
import app.syncler.core.network.LoginResponse
import app.syncler.core.network.PreLoginRequest
import app.syncler.core.network.PreLoginResponse
import app.syncler.core.network.SignupRequest
import app.syncler.core.network.SignupResponse
import app.syncler.core.network.SynclerApi
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AuthRepositoryTest {
    @Test
    fun signupStoresSessionAndEnrollsDevice() = runTest {
        val api = FakeApi()
        val tokenStore = InMemoryTokenStore()
        val session = Session(tokenStore)
        val identityStore = FakeDeviceIdentityStore()
        val repository = AuthRepository(api, session, FixedDevicePublicKeyProvider(), identityStore)

        val result = repository.signup("User@Example.com", "correct horse battery staple".toCharArray())

        assertTrue(result.isSuccess)
        assertEquals("token-1", session.currentToken())
        assertEquals("token-1", tokenStore.token)
        assertEquals("user@example.com", api.signupRequest?.email)
        assertEquals("user@example.com", api.preLoginRequest?.email)
        assertNotNull(api.signupRequest?.encryptedMasterKey)
        assertEquals(1, api.enrollCount)
        assertEquals("device-1", identityStore.value)
    }

    @Test
    fun logoutClearsStoredDeviceId() = runTest {
        val api = FakeApi()
        val identityStore = FakeDeviceIdentityStore()
        val repository = AuthRepository(api, Session(InMemoryTokenStore()), FixedDevicePublicKeyProvider(), identityStore)
        repository.signup("u@example.com", "correct horse battery staple".toCharArray())
        assertEquals("device-1", identityStore.value)

        repository.logout()

        assertEquals(null, identityStore.value)
    }

    @Test
    fun revokeFailureIsReturned() = runTest {
        val api = FakeApi(revokeSucceeds = false)
        val repository = AuthRepository(
            api,
            Session(InMemoryTokenStore()),
            FixedDevicePublicKeyProvider(),
            FakeDeviceIdentityStore(),
        )

        val result = repository.revokeDevice("missing")

        assertTrue(result.isFailure)
    }
}

private class FakeDeviceIdentityStore : DeviceIdentityStore {
    var value: String? = null
    override fun read(): String? = value
    override fun write(deviceId: String) { value = deviceId }
    override fun clear() { value = null }
}

private class InMemoryTokenStore : TokenStore {
    var token: String? = null

    override fun readToken(): String? = token
    override fun writeToken(token: String) {
        this.token = token
    }
    override fun clearToken() {
        token = null
    }
}

private class FixedDevicePublicKeyProvider : DevicePublicKeyProvider {
    override fun publicKey(): ByteArray = ByteArray(KEY_SIZE_BYTES) { 4 }
}

private class FakeApi(
    private val revokeSucceeds: Boolean = true,
) : SynclerApi {
    var signupRequest: SignupRequest? = null
    var preLoginRequest: PreLoginRequest? = null
    var enrollCount: Int = 0

    override suspend fun signup(body: SignupRequest): SignupResponse {
        signupRequest = body
        return SignupResponse(userId = "user-1", createdAt = "2026-05-20T00:00:00Z")
    }

    override suspend fun preLogin(body: PreLoginRequest): PreLoginResponse {
        preLoginRequest = body
        val signup = requireNotNull(signupRequest)
        assertEquals(signup.email, body.email)
        return PreLoginResponse(
            authSalt = signup.authSalt,
            argon2ParamsVersion = signup.argon2ParamsVersion,
        )
    }

    override suspend fun login(body: LoginRequest): LoginResponse {
        val signup = requireNotNull(signupRequest)
        assertEquals(signup.email, body.email)
        assertEquals(signup.authKeyHash, body.authKeyHash)
        return LoginResponse(
            userId = "user-1",
            sessionToken = "token-1",
            encryptedMasterKey = signup.encryptedMasterKey,
            authSalt = signup.authSalt,
            argon2ParamsVersion = signup.argon2ParamsVersion,
        )
    }

    override suspend fun enrollDevice(body: DeviceEnrollRequest): DeviceEnrollResponse {
        enrollCount += 1
        return DeviceEnrollResponse(deviceId = "device-1", createdAt = "2026-05-20T00:00:00Z")
    }

    override suspend fun listDevices(): List<DeviceItem> = emptyList()

    override suspend fun revokeDevice(id: String): Response<Unit> =
        if (revokeSucceeds) Response.success(Unit) else Response.error(404, ByteArray(0).toResponseBody())

    override suspend fun deleteAccount(): Response<Unit> = Response.success(Unit)

    // Methods unused by AuthRepository tests but required by the interface;
    // they were stubbed when added to keep this fixture compiling alongside
    // SynclerApi growth (state, pairing, plugin lookup, etc.).
    override suspend fun getMessage(id: String) = stub()
    override suspend fun inbox(since: String?, deviceId: String?) = stub()
    override suspend fun dismissMessage(id: String, deviceId: String): Response<Unit> = stub()
    override suspend fun previewPairing(token: String) = stub()
    override suspend fun completePairing(body: app.syncler.core.network.PairingCompleteRequestDto) = stub()
    override suspend fun revokePairing(id: String): Response<Unit> = stub()
    override suspend fun listPairings() = stub()
    override suspend fun getUserState() = stub()
    override suspend fun putUserState(body: app.syncler.core.network.StatePutRequestDto): Response<app.syncler.core.network.StatePutResponseDto> = stub()
    override suspend fun getPluginLatest(senderId: String, pluginIdentifier: String) = stub()
    override suspend fun getPluginById(pluginRowId: String) = stub()

    private fun stub(): Nothing = throw UnsupportedOperationException("not implemented by AuthRepositoryTest fake")
}
