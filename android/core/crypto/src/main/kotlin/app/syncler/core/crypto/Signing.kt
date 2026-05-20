package app.syncler.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

object Signing {
    const val DEVICE_KEY_ALIAS = "syncler-device-ed25519-v1"

    fun publicKeyFromSeed(seed: ByteArray): ByteArray {
        require(seed.size == KEY_SIZE_BYTES)
        return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    fun signWithSeed(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == KEY_SIZE_BYTES)
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != KEY_SIZE_BYTES || signature.size != 64) return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    fun androidKeystorePublicKeyOrNull(alias: String = DEVICE_KEY_ALIAS): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val publicKey = if (keyStore.containsAlias(alias)) {
                keyStore.getCertificate(alias).publicKey
            } else {
                val generator = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
                generator.initialize(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    ).build(),
                )
                generator.generateKeyPair().public
            }
            SubjectPublicKeyInfo.getInstance(publicKey.encoded).publicKeyData.bytes
        }.getOrNull()
    }

    fun signWithAndroidKeystoreOrNull(message: ByteArray, alias: String = DEVICE_KEY_ALIAS): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val privateKey = keyStore.getKey(alias, null) as PrivateKey
            Signature.getInstance("Ed25519").run {
                initSign(privateKey)
                update(message)
                sign()
            }
        }.getOrNull()
    }
}
