package app.syncler.core.auth

import app.syncler.core.crypto.KeyDerivation
import app.syncler.core.crypto.MasterKey
import app.syncler.core.crypto.base64ToBytes
import app.syncler.core.crypto.toBase64
import app.syncler.core.network.AuthFailureHandler
import app.syncler.core.network.DeviceEnrollRequest
import app.syncler.core.network.DeviceItem
import app.syncler.core.network.LoginRequest
import app.syncler.core.network.PreLoginRequest
import app.syncler.core.network.SignupRequest
import app.syncler.core.network.SynclerApi
import app.syncler.core.storage.PairedSenderStore
import java.security.SecureRandom
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class SignupResult(val userId: String)
data class LoginResult(val userId: String)

// KeyGenerationDowngradeError moved to SessionStore.kt so it can carry
// the consolidated verify-and-bump enforcement helper (§10.10).

@Singleton
class AuthRepository @Inject constructor(
    private val api: SynclerApi,
    private val session: Session,
    private val deviceKeyProvider: DevicePublicKeyProvider,
    private val deviceIdentityStore: DeviceIdentityStore,
    private val pairedSenderStore: PairedSenderStore,
    private val keyGenerationStore: KeyGenerationStore,
) : AuthFailureHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [AuthFailureHandler] implementation. EventStreamManager calls this
     * when the SSE handshake returns 401 (bootstrap-only token, revoked
     * device, or expired JWT). Clears the session so the AuthScreen
     * picks up — the existing `session.logout()` flow already wipes
     * device-identity and paired-sender state.
     *
     * Token-scoped: only logs out if the session still holds the exact
     * token that received the 401. A stale 401 — e.g., from an old
     * EventSource that died after a fast re-login — must not wipe a
     * newer successful session (Codex consultation 57 RED #12).
     *
     * Fires from any coroutine context (the EventStreamManager runs on
     * its own IO scope); we relaunch on our scope to keep the suspend
     * `logout()` call off the caller's thread.
     */
    override fun onAuthFailure(failedToken: String) {
        scope.launch {
            val currentToken = session.currentToken()
            if (currentToken != failedToken) {
                Timber.tag("AuthRepo").d("SSE 401 ignored — session token changed (stale failure)")
                return@launch
            }
            Timber.tag("AuthRepo").w("SSE 401 — clearing session and routing to login")
            logout()
        }
    }

    suspend fun signup(email: String, password: CharArray): Result<SignupResult> = runCatching {
        val normalizedEmail = normalizedEmail(email)
        val salt = generateAuthSalt()
        val keys = withContext(Dispatchers.Default) {
            KeyDerivation.derive(password, salt)
        }
        val masterKey = MasterKey.generate()
        try {
            val encryptedMasterKey = MasterKey.wrap(masterKey, keys.masterKeyWrapKey)
            api.signup(
                SignupRequest(
                    email = normalizedEmail,
                    authKeyHash = keys.authKey.toBase64(),
                    encryptedMasterKey = encryptedMasterKey.toBase64(),
                    authSalt = salt.toBase64(),
                    argon2ParamsVersion = KeyDerivation.PARAMS_VERSION,
                ),
            )

            val loginResult = login(normalizedEmail, password).getOrThrow()
            SignupResult(userId = loginResult.userId)
        } finally {
            masterKey.fill(0)
            keys.authKey.fill(0)
            keys.masterKeyWrapKey.fill(0)
        }
    }

    suspend fun login(email: String, password: CharArray): Result<LoginResult> = runCatching {
        val normalizedEmail = normalizedEmail(email)
        val preLogin = api.preLogin(PreLoginRequest(email = normalizedEmail))
        require(preLogin.argon2ParamsVersion == KeyDerivation.PARAMS_VERSION) {
            "Unsupported Argon2 params version: ${preLogin.argon2ParamsVersion}"
        }
        val salt = preLogin.authSalt.base64ToBytes()
        val keys = withContext(Dispatchers.Default) {
            KeyDerivation.derive(password, salt)
        }
        val response = api.login(
            LoginRequest(
                email = normalizedEmail,
                authKeyHash = keys.authKey.toBase64(),
            ),
        )
        require(response.argon2ParamsVersion == KeyDerivation.PARAMS_VERSION) {
            "Unsupported Argon2 params version: ${response.argon2ParamsVersion}"
        }
        // Phase 8 §10.10 — downgrade defense. BEFORE we unwrap the MK
        // (which would silently succeed under a stale generation),
        // the server-returned key_generation MUST be >= our locally-
        // persisted high-water mark for this user. Otherwise the
        // server returned a stale wrapped MK + state, and the client
        // would happily operate on a frozen snapshot.
        keyGenerationStore.verifyAndBump(
            userId = response.userId,
            observed = response.keyGeneration,
            source = "login",
        )
        val masterKey = MasterKey.unwrap(response.encryptedMasterKey.base64ToBytes(), keys.masterKeyWrapKey)
        try {
            // Enroll the device using the bootstrap token directly. We do
            // NOT call session.authenticate first because that would
            // briefly publish an unlocked Session carrying the user-only
            // bootstrap token. Observers (UserStateRepository pull, inbox
            // refresh on resume) could fire requests against sensitive
            // routes in that window and get 401 device_required (Codex
            // consultation 51 YELLOW #4).
            //
            // Pass the bootstrap token via @Header on enrollDevice; the
            // auth interceptor honors a pre-set Authorization header
            // instead of overwriting it.
            val enrollResponse = api.enrollDevice(
                authHeader = "Bearer ${response.sessionToken}",
                body = DeviceEnrollRequest(publicKey = deviceKeyProvider.publicKey().toBase64()),
            )
            deviceIdentityStore.write(enrollResponse.deviceId)
            // Single atomic transition: locked → unlocked WITH the
            // device-bound token already installed. No observer ever
            // sees the bootstrap token in an unlocked session.
            session.authenticate(
                token = enrollResponse.sessionToken,
                masterKey = masterKey,
                // Phase 8 — every state-mutating call needs to know
                // the current generation. The Session is the canonical
                // in-memory source.
                keyGeneration = response.keyGeneration,
                // Phase 8 — cache the salt + wrapped MK so the
                // "Change password" flow can re-rewrap without
                // bouncing through pre-login → login again.
                authSalt = salt,
                encryptedMasterKey = response.encryptedMasterKey.base64ToBytes(),
            )

            // Phase 1 legacy migration with explicit ownership proof.
            // Fetches the server-side pairing list (scoped to the user
            // we just authenticated) and asks the PairedSenderStore to
            // import only legacy local entries whose pairingId is
            // server-recognized for THIS user. Closes the multi-user
            // race Codex flagged in consultation 54.
            //
            // Network failure or no-op (no legacy + already migrated)
            // is silently swallowed; the store's phase1MigrationDoneAt
            // gate is idempotent so we retry harmlessly on next login.
            runCatching {
                val owned = api.listPairings()
                    .filter { it.revokedAt == null }
                    .map { it.id }
                    .toSet()
                pairedSenderStore.migratePhase1Owned(owned)
            }.onFailure { Timber.tag("AuthRepo").w(it, "phase 1 migration deferred") }
        } finally {
            masterKey.fill(0)
            keys.authKey.fill(0)
            keys.masterKeyWrapKey.fill(0)
        }
        LoginResult(userId = response.userId)
    }

    suspend fun logout() {
        deviceIdentityStore.clear()
        session.logout()
    }

    suspend fun listDevices(): Result<List<DeviceItem>> = runCatching {
        api.listDevices()
    }

    suspend fun revokeDevice(id: String): Result<Unit> = runCatching {
        val response = api.revokeDevice(id)
        if (!response.isSuccessful) error("Device revoke failed: HTTP ${response.code()}")
    }

    private fun normalizedEmail(email: String): String = email.trim().lowercase(Locale.US)

    private fun generateAuthSalt(): ByteArray =
        ByteArray(KeyDerivation.SALT_LENGTH_BYTES).also(SecureRandom()::nextBytes)
}
