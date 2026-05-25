package app.syncler.core.auth

import app.syncler.core.crypto.KEY_SIZE_BYTES
import app.syncler.core.crypto.KeyDerivation
import app.syncler.core.crypto.MasterKey
import app.syncler.core.crypto.toBase64
import app.syncler.core.network.DeviceEnrollRequest
import app.syncler.core.network.DeviceEnrollResponse
import app.syncler.core.network.DeviceItem
import app.syncler.core.network.LoginRequest
import app.syncler.core.network.LoginResponse
import app.syncler.core.network.PreLoginRequest
import app.syncler.core.network.PreLoginResponse
import app.syncler.core.network.RotateMasterKeyRequestDto
import app.syncler.core.network.RotateMasterKeyResponseDto
import app.syncler.core.network.RotationChallengeResponseDto
import app.syncler.core.network.SignupRequest
import app.syncler.core.network.SignupResponse
import app.syncler.core.network.SynclerApi
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class RotationRepositoryTest {

    @Test
    fun rewrapHappyPathUpdatesSessionAndKeyGenerationStore() = runTest {
        val fixture = newFixture()
        fixture.signupAndLogin()

        val before = fixture.session.sessionState.value
        assertTrue(before.isUnlocked)
        val masterKeyBefore = before.masterKey!!.copyOf()

        val result = fixture.rotation.rewrapPassword(
            currentPassword = PASSWORD.toCharArray(),
            newPassword = NEW_PASSWORD.toCharArray(),
        )

        assertTrue(result.toString(), result.isSuccess)
        val payload = fixture.api.rotateCallBody!!
        assertEquals("password_rewrap", payload.reason)
        assertEquals(1, payload.keyGenerationObserved)
        assertNotNull(payload.newAuthSalt)
        assertNotNull(payload.newAuthKeyProof)
        assertEquals(null, payload.newEncryptedUserState)
        assertEquals(null, payload.pairings)

        val after = fixture.session.sessionState.value
        // Same plaintext MK by VALUE (password_rewrap doesn't change it).
        assertTrue(masterKeyBefore.contentEquals(after.masterKey))
        // Salt + wrapped MK rotated.
        assertNotNull(after.authSalt)
        assertNotEquals(true, before.authSalt!!.contentEquals(after.authSalt))
        assertNotEquals(true, before.encryptedMasterKey!!.contentEquals(after.encryptedMasterKey!!))
        // High-water mark not lowered (server returned same gen).
        assertEquals(1, fixture.keyGenStore.read(fixture.api.signupRequest!!.userId!!))
    }

    @Test
    fun rewrapWithWrongCurrentPasswordSurfacesWrongCurrentPasswordError() = runTest {
        val fixture = newFixture()
        fixture.signupAndLogin()

        val result = fixture.rotation.rewrapPassword(
            currentPassword = "this is wrong".toCharArray(),
            newPassword = NEW_PASSWORD.toCharArray(),
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is WrongCurrentPasswordError)
        // CRITICAL: we MUST NOT have sent the proof to the server when the
        // local unwrap-test failed. Otherwise typos burn the server's
        // failed-proof rate limit.
        assertEquals(null, fixture.api.rotateCallBody)
        // And we MUST NOT have written the new generation to the high-water
        // mark.
        assertEquals(1, fixture.keyGenStore.read(fixture.api.signupRequest!!.userId!!))
    }

    @Test
    fun rewrapServer429IsBubbledUp() = runTest {
        val fixture = newFixture(rotateStatus = 429)
        fixture.signupAndLogin()

        val result = fixture.rotation.rewrapPassword(
            currentPassword = PASSWORD.toCharArray(),
            newPassword = NEW_PASSWORD.toCharArray(),
        )

        assertTrue(result.isFailure)
        val cause = result.exceptionOrNull()
        assertNotNull(cause)
        val http = cause as? retrofit2.HttpException
            ?: error("expected HttpException, got ${cause?.javaClass}")
        assertEquals(429, http.code())
        // Session NOT updated on failure.
        val state = fixture.session.sessionState.value
        assertTrue(state.isUnlocked)
        // High-water mark NOT bumped on failure.
        assertEquals(1, fixture.keyGenStore.read(fixture.api.signupRequest!!.userId!!))
    }

    @Test
    fun rewrapRequiresUnlockedSession() = runTest {
        val fixture = newFixture()
        // No signup/login → Session is locked.

        val result = fixture.rotation.rewrapPassword(
            currentPassword = PASSWORD.toCharArray(),
            newPassword = NEW_PASSWORD.toCharArray(),
        )

        assertTrue(result.isFailure)
        // The session-not-unlocked precondition uses `require()` which
        // raises IllegalArgumentException; either form is acceptable
        // as "programmer error" from the caller's perspective.
        val cause = result.exceptionOrNull()
        assertTrue(
            "expected IllegalState/Argument exception, got ${cause?.javaClass}",
            cause is IllegalStateException || cause is IllegalArgumentException,
        )
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val NEW_PASSWORD = "Tr0ub4dor & 3"
        const val EMAIL = "u@example.com"
    }

    private data class Fixture(
        val api: RotationFakeApi,
        val session: Session,
        val keyGenStore: FakeKeyGenerationStore,
        val auth: AuthRepository,
        val rotation: RotationRepository,
    ) {
        suspend fun signupAndLogin() {
            auth.signup(EMAIL, PASSWORD.toCharArray()).getOrThrow()
            // After signup the session is authenticated and has authSalt +
            // encryptedMasterKey cached (per Phase 8c wiring).
        }
    }

    private fun newFixture(rotateStatus: Int = 200): Fixture {
        val api = RotationFakeApi(rotateStatus = rotateStatus)
        val tokenStore = InMemoryTokenStore()
        val session = Session(tokenStore)
        val keyGenStore = FakeKeyGenerationStore()
        val identityStore = FakeDeviceIdentityStore()
        val auth = AuthRepository(
            api = api,
            session = session,
            deviceKeyProvider = FixedDevicePublicKeyProvider(),
            deviceIdentityStore = identityStore,
            pairedSenderStore = FakePairedSenderStore(),
            keyGenerationStore = keyGenStore,
        )
        val rotation = RotationRepository(api, session, keyGenStore)
        return Fixture(api, session, keyGenStore, auth, rotation)
    }
}

/**
 * Build a JWT-shaped string with `sub = userId`. Header + body are
 * base64url-encoded (no padding); the signature segment is a fixed
 * literal. ``Session.currentUserId`` only base64-decodes the middle
 * segment and parses `sub` — it does NOT verify the MAC — so this
 * shape is sufficient for tests.
 */
private fun makeFakeJwt(userId: String): String {
    val header = "{\"alg\":\"HS256\"}".toByteArray(Charsets.UTF_8)
    val body = "{\"sub\":\"$userId\"}".toByteArray(Charsets.UTF_8)
    val enc = java.util.Base64.getUrlEncoder().withoutPadding()
    return enc.encodeToString(header) + "." + enc.encodeToString(body) + ".signature"
}

private class RotationFakeApi(
    private val rotateStatus: Int = 200,
) : SynclerApi {
    var signupRequest: SignupRequest? = null
    var rotateCallBody: RotateMasterKeyRequestDto? = null

    override suspend fun signup(body: SignupRequest): SignupResponse {
        signupRequest = body
        return SignupResponse(
            // Phase 8d: echo back the client-supplied user_id so MK
            // wrap AAD round-trips through login.
            userId = body.userId ?: "user-1",
            createdAt = "2026-05-20T00:00:00Z",
        )
    }

    override suspend fun preLogin(body: PreLoginRequest): PreLoginResponse {
        val s = requireNotNull(signupRequest)
        return PreLoginResponse(authSalt = s.authSalt, argon2ParamsVersion = s.argon2ParamsVersion)
    }

    override suspend fun login(body: LoginRequest): LoginResponse {
        val s = requireNotNull(signupRequest)
        return LoginResponse(
            userId = s.userId ?: "user-1",
            sessionToken = "token-1",
            encryptedMasterKey = s.encryptedMasterKey,
            authSalt = s.authSalt,
            argon2ParamsVersion = s.argon2ParamsVersion,
            keyGeneration = 1,
        )
    }

    override suspend fun enrollDevice(
        authHeader: String,
        body: DeviceEnrollRequest,
    ): DeviceEnrollResponse {
        // Phase 8d: the JWT's `sub` claim must match the user_id
        // used at signup-wrap time, otherwise the AAD on
        // rewrap-unwrap won't match. Pull from the signup we
        // captured.
        val userId = signupRequest?.userId ?: "user-1"
        return DeviceEnrollResponse(
            deviceId = "device-1",
            createdAt = "2026-05-20T00:00:00Z",
            sessionToken = makeFakeJwt(userId = userId),
        )
    }

    override suspend fun rotateMasterKeyChallenge(): RotationChallengeResponseDto =
        RotationChallengeResponseDto(
            rotationChallenge = ByteArray(32) { it.toByte() }.toBase64(),
            expiresAt = "2026-05-20T00:05:00Z",
        )

    override suspend fun rotateMasterKey(
        body: RotateMasterKeyRequestDto,
    ): Response<RotateMasterKeyResponseDto> {
        rotateCallBody = body
        return if (rotateStatus == 200) {
            Response.success(
                RotateMasterKeyResponseDto(
                    keyGeneration = body.keyGenerationObserved,
                    encryptedUserState = null,
                    pairings = emptyList(),
                ),
            )
        } else {
            Response.error(rotateStatus, ByteArray(0).toResponseBody())
        }
    }

    // Unused stubs.
    override suspend fun listDevices(): List<DeviceItem> = emptyList()
    override suspend fun revokeDevice(id: String): Response<Unit> = Response.success(Unit)
    override suspend fun deleteAccount(): Response<Unit> = Response.success(Unit)
    override suspend fun getMessage(id: String) = stub()
    override suspend fun inbox(since: String?) = stub()
    override suspend fun dismissMessage(id: String): Response<Unit> = stub()
    override suspend fun previewPairing(token: String) = stub()
    override suspend fun completePairing(body: app.syncler.core.network.PairingCompleteRequestDto) = stub()
    override suspend fun revokePairing(id: String): Response<Unit> = stub()
    override suspend fun listPairings(): List<app.syncler.core.network.PairingItemDto> = emptyList()
    override suspend fun getUserState() = stub()
    override suspend fun putUserState(
        body: app.syncler.core.network.StatePutRequestDto,
    ): Response<app.syncler.core.network.StatePutResponseDto> = stub()
    override suspend fun getPluginLatest(senderId: String, pluginIdentifier: String) = stub()
    override suspend fun getPluginById(pluginRowId: String) = stub()

    private fun stub(): Nothing = throw UnsupportedOperationException("not implemented by RotationFakeApi")
}
