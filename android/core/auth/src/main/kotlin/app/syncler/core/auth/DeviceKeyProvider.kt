package app.syncler.core.auth

import app.syncler.core.crypto.KEY_SIZE_BYTES
import app.syncler.core.crypto.Signing
import app.syncler.core.crypto.base64ToBytes
import app.syncler.core.crypto.toBase64
import app.syncler.core.storage.SecurePrefs
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceKeyProvider @Inject constructor(
    private val securePrefs: SecurePrefs,
) : DevicePublicKeyProvider {
    override fun publicKey(): ByteArray =
        Signing.androidKeystorePublicKeyOrNull() ?: fallbackPublicKey()

    private fun fallbackPublicKey(): ByteArray {
        val seed = securePrefs.getString(KEY_FALLBACK_SEED)?.base64ToBytes()
            ?: ByteArray(KEY_SIZE_BYTES).also {
                SecureRandom().nextBytes(it)
                securePrefs.putString(KEY_FALLBACK_SEED, it.toBase64())
            }
        return Signing.publicKeyFromSeed(seed)
    }

    private companion object {
        const val KEY_FALLBACK_SEED = "device_ed25519_seed_v1"
    }
}

interface DevicePublicKeyProvider {
    fun publicKey(): ByteArray
}
