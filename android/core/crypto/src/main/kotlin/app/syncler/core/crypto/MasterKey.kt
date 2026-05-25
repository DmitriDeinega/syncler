package app.syncler.core.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val KEY_SIZE_BYTES = 32
private const val NONCE_SIZE_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val AES_GCM = "AES/GCM/NoPadding"

object MasterKey {
    private val secureRandom = SecureRandom()

    fun generate(): ByteArray = ByteArray(KEY_SIZE_BYTES).also(secureRandom::nextBytes)

    /**
     * Wrap the master key under [masterKeyWrapKey] with optional AAD.
     * Phase 8d (docs/crypto-spec.md §10.9): pass
     * ``RotationAad.masterKeyWrap(userId, authSaltB64)`` so the
     * ciphertext is bound to the user identity + auth salt. ``null``
     * AAD is the pre-Phase-8d shape (kept for back-compat with old
     * unrotated blobs during the transition window — but new wraps
     * MUST provide AAD).
     */
    fun wrap(
        masterKey: ByteArray,
        masterKeyWrapKey: ByteArray,
        aad: ByteArray? = null,
    ): ByteArray {
        require(masterKey.size == KEY_SIZE_BYTES)
        return Aead.encrypt(masterKeyWrapKey, masterKey, aad = aad)
    }

    fun unwrap(
        encryptedMasterKey: ByteArray,
        masterKeyWrapKey: ByteArray,
        aad: ByteArray? = null,
    ): ByteArray = Aead.decrypt(masterKeyWrapKey, encryptedMasterKey, aad = aad)
}

object Aead {
    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null, nonce: ByteArray = randomNonce()): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "AES-256-GCM key must be 32 bytes" }
        require(nonce.size == NONCE_SIZE_BYTES) { "AES-GCM nonce must be 12 bytes" }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        aad?.let(cipher::updateAAD)
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    fun decrypt(key: ByteArray, wire: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "AES-256-GCM key must be 32 bytes" }
        require(wire.size >= NONCE_SIZE_BYTES + 16) { "AES-GCM wire payload is too short" }
        val nonce = wire.copyOfRange(0, NONCE_SIZE_BYTES)
        val ciphertext = wire.copyOfRange(NONCE_SIZE_BYTES, wire.size)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(ciphertext)
    }

    private fun randomNonce(): ByteArray = ByteArray(NONCE_SIZE_BYTES).also(SecureRandom()::nextBytes)
}

fun ByteArray.toBase64(): String =
    Base64.getEncoder().encodeToString(this)

fun String.base64ToBytes(): ByteArray =
    Base64.getDecoder().decode(this)

fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex length must be even" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
