package app.syncler.core.auth

import app.syncler.core.crypto.KeyDerivation
import app.syncler.core.crypto.MasterKey
import app.syncler.core.crypto.base64ToBytes
import app.syncler.core.crypto.toBase64
import app.syncler.core.network.DeviceEnrollRequest
import app.syncler.core.network.DeviceItem
import app.syncler.core.network.LoginRequest
import app.syncler.core.network.PreLoginRequest
import app.syncler.core.network.SignupRequest
import app.syncler.core.network.SynclerApi
import java.security.SecureRandom
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SignupResult(val userId: String)
data class LoginResult(val userId: String)

@Singleton
class AuthRepository @Inject constructor(
    private val api: SynclerApi,
    private val session: Session,
    private val deviceKeyProvider: DevicePublicKeyProvider,
    private val deviceIdentityStore: DeviceIdentityStore,
) {
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
            session.authenticate(enrollResponse.sessionToken, masterKey)
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
