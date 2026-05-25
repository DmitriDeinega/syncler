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
        val repository = AuthRepository(api, session, FixedDevicePublicKeyProvider(), identityStore, FakePairedSenderStore(), FakeKeyGenerationStore())

        val result = repository.signup("User@Example.com", "correct horse battery staple".toCharArray())

        assertTrue(result.isSuccess)
        // Post-enroll the bootstrap "token-1" is swapped for the device-bound
        // token returned by /v1/auth/devices/enroll. Both Session and the
        // TokenStore should hold the new token.
        assertEquals("device-bound-token-1", session.currentToken())
        assertEquals("device-bound-token-1", tokenStore.token)
        assertEquals("user@example.com", api.signupRequest?.email)
        assertEquals("user@example.com", api.preLoginRequest?.email)
        assertNotNull(api.signupRequest?.encryptedMasterKey)
        assertEquals(1, api.enrollCount)
        assertEquals("device-1", identityStore.value)
    }

    @Test
    fun loginReplacesBootstrapTokenWithDeviceBoundToken() = runTest {
        val api = FakeApi()
        val tokenStore = InMemoryTokenStore()
        val session = Session(tokenStore)
        val repository = AuthRepository(
            api,
            session,
            FixedDevicePublicKeyProvider(),
            FakeDeviceIdentityStore(),
            FakePairedSenderStore(),
            FakeKeyGenerationStore(),
        )

        // Run signup to set up a user and pairing-key material the login
        // step can verify against.
        repository.signup("u@example.com", "correct horse battery staple".toCharArray()).getOrThrow()

        // Pure login path also goes through enroll and ends up with the
        // device-bound token.
        repository.login("u@example.com", "correct horse battery staple".toCharArray()).getOrThrow()

        assertEquals("device-bound-token-1", session.currentToken())
        assertEquals("device-bound-token-1", tokenStore.token)
    }

    @Test
    fun enrollIsCalledWithBootstrapTokenBeforeSessionUnlocks() = runTest {
        val api = FakeApi()
        val tokenStore = InMemoryTokenStore()
        val session = Session(tokenStore)
        val repository = AuthRepository(
            api,
            session,
            FixedDevicePublicKeyProvider(),
            FakeDeviceIdentityStore(),
            FakePairedSenderStore(),
            FakeKeyGenerationStore(),
        )

        repository.signup("u@example.com", "correct horse battery staple".toCharArray()).getOrThrow()

        // The enroll call must have carried the bootstrap token (from
        // /v1/auth/login) — NOT the device-bound token from its own
        // response, and not any token published via Session.authenticate
        // (Codex consultation 51 YELLOW #4: enroll runs BEFORE
        // session.authenticate so observers never see an unlocked session
        // carrying a user-only bootstrap token).
        assertEquals("Bearer token-1", api.lastEnrollAuthHeader)
    }

    @Test
    fun logoutClearsStoredDeviceId() = runTest {
        val api = FakeApi()
        val identityStore = FakeDeviceIdentityStore()
        val repository = AuthRepository(api, Session(InMemoryTokenStore()), FixedDevicePublicKeyProvider(), identityStore, FakePairedSenderStore(), FakeKeyGenerationStore())
        repository.signup("u@example.com", "correct horse battery staple".toCharArray())
        assertEquals("device-1", identityStore.value)

        repository.logout()

        assertEquals(null, identityStore.value)
    }

    @Test
    fun loginInvokesPhase1MigrationWithServerOwnedPairingIds() = runTest {
        // Codex consultation 54 RED #2: legacy migration must be gated on
        // server-side pairing ownership so user A's legacy entries can't
        // bleed into user B's account. Verify that AuthRepository.login
        // (a) calls listPairings, (b) forwards the active pairingIds (not
        // revoked ones) to migratePhase1Owned.
        val api = FakeApi()
        api.listPairingsResult = listOf(
            app.syncler.core.network.PairingItemDto(
                id = "owned-1",
                senderId = "sender-x",
                createdAt = "2026-05-20T10:00:00Z",
                revokedAt = null,
            ),
            app.syncler.core.network.PairingItemDto(
                id = "revoked-1",
                senderId = "sender-y",
                createdAt = "2026-05-20T10:00:00Z",
                revokedAt = "2026-05-21T10:00:00Z",
            ),
        )
        val store = FakePairedSenderStore()
        val repository = AuthRepository(
            api,
            Session(InMemoryTokenStore()),
            FixedDevicePublicKeyProvider(),
            FakeDeviceIdentityStore(),
            store,
            FakeKeyGenerationStore(),
        )

        repository.signup("u@example.com", "correct horse battery staple".toCharArray()).getOrThrow()

        // Signup calls login internally → exactly one migration trigger.
        assertEquals(1, store.migrateCalls)
        // Only the active (non-revoked) pairing's id should reach the store.
        assertEquals(setOf("owned-1"), store.lastOwnedIds)
        assertEquals(1, api.listPairingsCalls)
    }

    @Test
    fun revokeFailureIsReturned() = runTest {
        val api = FakeApi(revokeSucceeds = false)
        val repository = AuthRepository(
            api,
            Session(InMemoryTokenStore()),
            FixedDevicePublicKeyProvider(),
            FakeDeviceIdentityStore(),
            FakePairedSenderStore(),
            FakeKeyGenerationStore(),
        )

        val result = repository.revokeDevice("missing")

        assertTrue(result.isFailure)
    }
}

internal class FakeDeviceIdentityStore : DeviceIdentityStore {
    var value: String? = null
    override fun read(): String? = value
    override fun write(deviceId: String) { value = deviceId }
    override fun clear() { value = null }
}

internal class FakePairedSenderStore : app.syncler.core.storage.PairedSenderStore {
    var migrateCalls: Int = 0
    var lastOwnedIds: Set<String>? = null
    override val pairedSenders: kotlinx.coroutines.flow.StateFlow<List<app.syncler.core.storage.PairedSender>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    override suspend fun add(pairedSender: app.syncler.core.storage.PairedSender) {}
    override suspend fun remove(pairingId: String) {}
    override suspend fun byPairingId(pairingId: String) = null
    override suspend fun bySenderId(senderId: String) = null
    override suspend fun activePairingsForSender(senderId: String) = emptyList<app.syncler.core.storage.PairedSender>()
    override suspend fun migratePhase1Owned(ownedPairingIds: Set<String>) {
        migrateCalls++
        lastOwnedIds = ownedPairingIds
    }
}

internal class FakeKeyGenerationStore : KeyGenerationStore {
    private val values = mutableMapOf<String, Int>()

    override fun read(userId: String): Int = values[userId] ?: 0

    override fun bump(userId: String, observed: Int): Int {
        val current = read(userId)
        if (observed > current) {
            values[userId] = observed
            return observed
        }
        return current
    }
}

internal class InMemoryTokenStore : TokenStore {
    var token: String? = null

    override fun readToken(): String? = token
    override fun writeToken(token: String) {
        this.token = token
    }
    override fun clearToken() {
        token = null
    }
}

internal class FixedDevicePublicKeyProvider : DevicePublicKeyProvider {
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
        // Phase 8d §10.9: echo back the client-supplied user_id so
        // the MK wrap AAD (which binds user_id + auth_salt_b64)
        // matches between signup-wrap and login-unwrap. Fall back
        // to a stable string for pre-Phase-8d test cases.
        val echoUserId = body.userId ?: "user-1"
        return SignupResponse(userId = echoUserId, createdAt = "2026-05-20T00:00:00Z")
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
            // Phase 8d: echo the user_id the client passed at signup
            // (so the MK wrap AAD matches on unwrap).
            userId = signup.userId ?: "user-1",
            sessionToken = "token-1",
            encryptedMasterKey = signup.encryptedMasterKey,
            authSalt = signup.authSalt,
            argon2ParamsVersion = signup.argon2ParamsVersion,
            keyGeneration = 1,
        )
    }

    var lastEnrollAuthHeader: String? = null

    override suspend fun enrollDevice(authHeader: String, body: DeviceEnrollRequest): DeviceEnrollResponse {
        enrollCount += 1
        lastEnrollAuthHeader = authHeader
        return DeviceEnrollResponse(
            deviceId = "device-1",
            createdAt = "2026-05-20T00:00:00Z",
            sessionToken = "device-bound-token-1",
        )
    }

    override suspend fun listDevices(): List<DeviceItem> = emptyList()

    override suspend fun revokeDevice(id: String): Response<Unit> =
        if (revokeSucceeds) Response.success(Unit) else Response.error(404, ByteArray(0).toResponseBody())

    override suspend fun deleteAccount(): Response<Unit> = Response.success(Unit)

    // Methods unused by AuthRepository tests but required by the interface;
    // they were stubbed when added to keep this fixture compiling alongside
    // SynclerApi growth (state, pairing, plugin lookup, etc.).
    override suspend fun getMessage(id: String) = stub()
    override suspend fun inbox(since: String?) = stub()
    override suspend fun dismissMessage(id: String): Response<Unit> = stub()
    override suspend fun previewPairing(token: String) = stub()
    override suspend fun completePairing(body: app.syncler.core.network.PairingCompleteRequestDto) = stub()
    override suspend fun revokePairing(id: String): Response<Unit> = stub()
    var listPairingsCalls: Int = 0
    var listPairingsResult: List<app.syncler.core.network.PairingItemDto> = emptyList()
    override suspend fun listPairings(): List<app.syncler.core.network.PairingItemDto> {
        listPairingsCalls++
        return listPairingsResult
    }
    override suspend fun getUserState() = stub()
    override suspend fun putUserState(body: app.syncler.core.network.StatePutRequestDto): Response<app.syncler.core.network.StatePutResponseDto> = stub()
    override suspend fun getPluginLatest(senderId: String, pluginIdentifier: String) = stub()
    override suspend fun getPluginById(pluginRowId: String) = stub()
    override suspend fun rotateMasterKeyChallenge() = stub()
    override suspend fun rotateMasterKey(
        body: app.syncler.core.network.RotateMasterKeyRequestDto,
    ): Response<app.syncler.core.network.RotateMasterKeyResponseDto> = stub()

    private fun stub(): Nothing = throw UnsupportedOperationException("not implemented by AuthRepositoryTest fake")
}
