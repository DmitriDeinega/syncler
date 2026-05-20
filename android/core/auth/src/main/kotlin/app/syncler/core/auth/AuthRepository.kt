package app.syncler.core.auth

import app.syncler.core.crypto.KeyDerivation
import app.syncler.core.crypto.MasterKey
import app.syncler.core.crypto.base64ToBytes
import app.syncler.core.crypto.toBase64
import app.syncler.core.network.DeviceEnrollRequest
import app.syncler.core.network.DeviceItem
import app.syncler.core.network.LoginRequest
import app.syncler.core.network.SignupRequest
import app.syncler.core.network.SynclerApi
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
) {
    suspend fun signup(email: String, password: CharArray): Result<SignupResult> = runCatching {
        val normalizedEmail = AuthSalt.normalizedEmail(email)
        val salt = AuthSalt.forEmail(normalizedEmail)
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
        val normalizedEmail = AuthSalt.normalizedEmail(email)
        val salt = AuthSalt.forEmail(normalizedEmail)
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
            session.authenticate(response.sessionToken, masterKey)
            runCatching { enrollCurrentDevice() }
                .onFailure {
                    session.logout()
                    throw it
                }
        } finally {
            masterKey.fill(0)
            keys.authKey.fill(0)
            keys.masterKeyWrapKey.fill(0)
        }
        LoginResult(userId = response.userId)
    }

    suspend fun logout() {
        session.logout()
    }

    suspend fun listDevices(): Result<List<DeviceItem>> = runCatching {
        api.listDevices()
    }

    suspend fun revokeDevice(id: String): Result<Unit> = runCatching {
        val response = api.revokeDevice(id)
        if (!response.isSuccessful) error("Device revoke failed: HTTP ${response.code()}")
    }

    private suspend fun enrollCurrentDevice() {
        api.enrollDevice(DeviceEnrollRequest(publicKey = deviceKeyProvider.publicKey().toBase64()))
    }
}
