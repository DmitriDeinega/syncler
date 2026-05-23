package app.syncler.feature.inbox

import app.syncler.core.auth.Session
import app.syncler.core.auth.TokenStore
import app.syncler.core.network.MessageInboxResponseDto
import app.syncler.core.network.PluginLatestDto
import app.syncler.core.network.SignupRequest
import app.syncler.core.network.SignupResponse
import app.syncler.core.network.SynclerApi
import app.syncler.core.network.LoginRequest
import app.syncler.core.network.LoginResponse
import app.syncler.core.network.PreLoginRequest
import app.syncler.core.network.PreLoginResponse
import app.syncler.core.network.StateGetResponseDto
import app.syncler.core.network.StatePutRequestDto
import app.syncler.core.network.StatePutResponseDto
import app.syncler.core.network.DeviceEnrollRequest
import app.syncler.core.network.DeviceEnrollResponse
import app.syncler.core.network.DeviceItem
import app.syncler.core.network.MessageInboxItemDto
import app.syncler.core.network.PairingCompleteRequestDto
import app.syncler.core.network.PairingCompleteResponseDto
import app.syncler.core.network.PairingItemDto
import app.syncler.core.network.PairingPreviewResponseDto
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Regression coverage for the CAS dirty-flag bug found in consultation 47
 * (Codex Reviewer B): when both the initial PUT /v1/state and the subsequent
 * conflict-retry PUT returned non-200 responses, the previous implementation
 * silently logged and let push()'s outer runCatching reach clearDirty(),
 * losing the pending local change.
 *
 * The fix in [UserStateRepository.handleConflictAndRetry] throws on retry
 * failure so the outer runCatching captures it and skips clearDirty().
 *
 * This test exercises the path with an in-memory [UserStatePrefs] backend
 * and a fake [SynclerApi] that always conflicts, then asserts that the
 * dirty flag remains true after push() returns failure.
 */
class UserStateRepositoryPushTest {

    @Test
    fun `dirty flag stays set when CAS retry also conflicts`() = runTest {
        val prefs = InMemoryUserStatePrefs()
        val api = AlwaysConflictApi()
        val session = unlockedSession()
        val repo = UserStateRepository(prefs, api, session, java.time.Clock.systemUTC())

        // Local mutation triggers markDirty + push; push hits 409, retries via
        // handleConflictAndRetry, retry also 409, repo throws → outer
        // runCatching skips clearDirty.
        repo.markRead("msg-1")

        // The crux: dirty MUST stay true so flushPendingPush on the next
        // foreground tick re-attempts the upload. The pre-fix bug cleared it.
        assertTrue("dirty flag must remain set after a permanent CAS conflict", prefs.getBoolean("dirty", false))

        // flushPendingPush should also leave dirty true if the conflict
        // persists, and the underlying api PUT must have been called again.
        api.putCount = 0
        val result = repo.flushPendingPush()
        assertTrue(result.isFailure || api.putCount > 0)
        assertTrue("dirty flag must remain set after a failed flushPendingPush", prefs.getBoolean("dirty", false))
    }

    @Test
    fun `dirty flag clears on successful push`() = runTest {
        val prefs = InMemoryUserStatePrefs()
        val api = AlwaysSucceedsApi()
        val session = unlockedSession()
        val repo = UserStateRepository(prefs, api, session, java.time.Clock.systemUTC())

        repo.markRead("msg-1")

        assertFalse("dirty flag must be cleared after a successful push", prefs.getBoolean("dirty", false))
    }

    private fun unlockedSession(): Session {
        val store = object : TokenStore {
            private var token: String? = "test-token"
            override fun readToken() = token
            override fun writeToken(token: String) { this.token = token }
            override fun clearToken() { token = null }
        }
        val session = Session(store)
        session.authenticate(token = "test-token", masterKey = ByteArray(32) { 0x42 })
        return session
    }
}

private class InMemoryUserStatePrefs : UserStatePrefs {
    private val strings = mutableMapOf<String, String>()
    private val ints = mutableMapOf<String, Int>()
    private val bools = mutableMapOf<String, Boolean>()

    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String) { strings[key] = value }
    override fun getInt(key: String, default: Int): Int = ints[key] ?: default
    override fun putInt(key: String, value: Int) { ints[key] = value }
    override fun getBoolean(key: String, default: Boolean): Boolean = bools[key] ?: default
    override fun putBoolean(key: String, value: Boolean) { bools[key] = value }
    override fun clear() {
        strings.clear(); ints.clear(); bools.clear()
    }
}

/** Both initial PUT and retry PUT return 409. Verifies dirty stays true. */
private class AlwaysConflictApi : SynclerApi by StubApi() {
    var putCount: Int = 0

    override suspend fun getUserState(): StateGetResponseDto =
        StateGetResponseDto(stateVersion = 0, encryptedBlob = "", updatedAt = null)

    override suspend fun putUserState(body: StatePutRequestDto): Response<StatePutResponseDto> {
        putCount++
        return Response.error(409, ByteArray(0).toResponseBody())
    }
}

/** First PUT succeeds; verifies the happy path still clears dirty. */
private class AlwaysSucceedsApi : SynclerApi by StubApi() {
    private var version = 0

    override suspend fun getUserState(): StateGetResponseDto =
        StateGetResponseDto(stateVersion = version, encryptedBlob = "", updatedAt = null)

    override suspend fun putUserState(body: StatePutRequestDto): Response<StatePutResponseDto> {
        version++
        return Response.success(StatePutResponseDto(newStateVersion = version))
    }
}

/**
 * Stub for the SynclerApi methods we don't exercise. Each throws so any
 * accidental dependency surfaces in the test rather than silently passing
 * a wrong code path.
 */
private class StubApi : SynclerApi {
    private fun stub(): Nothing = throw UnsupportedOperationException("not implemented by test stub")

    override suspend fun signup(body: SignupRequest): SignupResponse = stub()
    override suspend fun preLogin(body: PreLoginRequest): PreLoginResponse = stub()
    override suspend fun login(body: LoginRequest): LoginResponse = stub()
    override suspend fun enrollDevice(authHeader: String, body: DeviceEnrollRequest): DeviceEnrollResponse = stub()
    override suspend fun listDevices(): List<DeviceItem> = stub()
    override suspend fun revokeDevice(id: String): Response<Unit> = stub()
    override suspend fun deleteAccount(): Response<Unit> = stub()
    override suspend fun getMessage(id: String): MessageInboxItemDto = stub()
    override suspend fun inbox(since: String?): MessageInboxResponseDto = stub()
    override suspend fun dismissMessage(id: String): Response<Unit> = stub()
    override suspend fun previewPairing(token: String): PairingPreviewResponseDto = stub()
    override suspend fun completePairing(body: PairingCompleteRequestDto): PairingCompleteResponseDto = stub()
    override suspend fun revokePairing(id: String): Response<Unit> = stub()
    override suspend fun listPairings(): List<PairingItemDto> = stub()
    override suspend fun getUserState(): StateGetResponseDto = stub()
    override suspend fun putUserState(body: StatePutRequestDto): Response<StatePutResponseDto> = stub()
    override suspend fun getPluginLatest(senderId: String, pluginIdentifier: String): PluginLatestDto = stub()
    override suspend fun getPluginById(pluginRowId: String): PluginLatestDto = stub()
}
