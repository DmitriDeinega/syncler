package app.syncler.core.storage

import android.util.Base64
import app.syncler.core.crypto.Hpke
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 9b §11.1: per-device X25519 keypair storage for the
 * HPKE recipient role.
 *
 * The X25519 private key is the SOLE secret on the device that
 * lets a sender's message decrypt; losing it (uninstall, factory
 * reset) means messages encrypted to the old key become
 * undecryptable on this device (spec §11.14). That's documented
 * as expected V0.1 behavior; future cross-device backfill would
 * re-encrypt at the sender.
 *
 * Storage lives in the existing [SecurePrefs] (androidx.security
 * EncryptedSharedPreferences) alongside session tokens etc. —
 * the master key wrapping that prefs file is what protects the
 * X25519 private key on disk.
 */
@Singleton
class DeviceEncryptionKeyStore @Inject constructor(
    private val securePrefs: SecurePrefs,
) {

    /**
     * Get the existing keypair OR generate + persist a fresh one
     * on first call. Returns (privateRaw32, publicRaw32).
     *
     * Called at device-enrollment time (the enroll request body
     * needs the X25519 public key) AND at decrypt time (the
     * inbox repository needs the private key to HPKE-open each
     * incoming envelope).
     */
    fun getOrCreateKeypair(): Keypair {
        val storedPrivate = securePrefs.getString(KEY_PRIVATE)
        val storedPublic = securePrefs.getString(KEY_PUBLIC)
        if (storedPrivate != null && storedPublic != null) {
            val sk = Base64.decode(storedPrivate, Base64.NO_WRAP)
            val pk = Base64.decode(storedPublic, Base64.NO_WRAP)
            if (sk.size == Hpke.X25519_PRIVATE_KEY_BYTES && pk.size == Hpke.X25519_PUBLIC_KEY_BYTES) {
                return Keypair(sk, pk)
            }
            // Corrupted entry — fall through to regenerate.
        }
        val (sk, pk) = Hpke.generateX25519Keypair()
        securePrefs.putString(KEY_PRIVATE, Base64.encodeToString(sk, Base64.NO_WRAP))
        securePrefs.putString(KEY_PUBLIC, Base64.encodeToString(pk, Base64.NO_WRAP))
        return Keypair(sk, pk)
    }

    /**
     * Rotate the keypair. Discards the existing one and creates a
     * fresh pair. Returns the new (privateRaw32, publicRaw32).
     *
     * Caller's responsibility to also send the new public key to
     * the server via `PUT /v1/auth/devices/me/encryption_key`
     * (spec §11.12). Old messages encrypted to the previous key
     * become undecryptable on this device after rotation.
     */
    fun rotate(): Keypair {
        val (sk, pk) = Hpke.generateX25519Keypair()
        securePrefs.putString(KEY_PRIVATE, Base64.encodeToString(sk, Base64.NO_WRAP))
        securePrefs.putString(KEY_PUBLIC, Base64.encodeToString(pk, Base64.NO_WRAP))
        return Keypair(sk, pk)
    }

    /** Wipe — used by `clear local state` / logout paths. */
    fun clear() {
        securePrefs.remove(KEY_PRIVATE)
        securePrefs.remove(KEY_PUBLIC)
    }

    data class Keypair(
        val privateKey: ByteArray,  // 32 bytes
        val publicKey: ByteArray,   // 32 bytes
    ) {
        // Hand-rolled equals/hashCode because data class default uses
        // ByteArray.equals (reference equality, not byte equality).
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Keypair) return false
            return privateKey.contentEquals(other.privateKey) &&
                publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int =
            31 * privateKey.contentHashCode() + publicKey.contentHashCode()
    }

    private companion object {
        const val KEY_PRIVATE = "syncler.v2.device.x25519_private"
        const val KEY_PUBLIC = "syncler.v2.device.x25519_public"
    }
}


@Module
@InstallIn(SingletonComponent::class)
object DeviceEncryptionKeyStoreModule {
    @Provides
    @Singleton
    fun provideDeviceEncryptionKeyStore(
        securePrefs: SecurePrefs,
    ): DeviceEncryptionKeyStore = DeviceEncryptionKeyStore(securePrefs)
}
