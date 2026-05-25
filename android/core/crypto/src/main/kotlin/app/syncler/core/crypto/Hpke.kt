package app.syncler.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * Phase 9b per-device envelope encryption (docs/crypto-spec.md §11).
 *
 * HPKE Suite: RFC 9180 DHKEM(X25519, HKDF-SHA256), HKDF-SHA256,
 * AES-256-GCM (IDs 0x0020 / 0x0001 / 0x0002).
 *
 * Cross-platform invariant: this module produces byte-identical
 * `payload_ciphertext` + `hpke_kem_output||hpke_ciphertext` to the
 * server's PyCA-backed implementation in
 * `server/app/crypto/hpke.py` and the SDK's
 * `sdk-python/syncler/crypto.py:seal_v2_envelopes`, given the same
 * canonical info bytes.
 *
 * `aad` is empty in this module to match PyCA's single-shot API
 * which does NOT expose RFC 9180's separate aad parameter. All
 * per-recipient authenticated context goes into HPKE `info`.
 */
object Hpke {

    private const val KEM_ID = HPKE.kem_X25519_SHA256.toInt()
    private const val KDF_ID = HPKE.kdf_HKDF_SHA256.toInt()
    private const val AEAD_ID = HPKE.aead_AES_GCM256.toInt()

    const val HPKE_KEM_OUTPUT_BYTES: Int = 32
    const val CEK_BYTES: Int = 32
    const val HPKE_WRAP_BYTES: Int = CEK_BYTES + 16
    const val HPKE_OUTPUT_BYTES: Int = HPKE_KEM_OUTPUT_BYTES + HPKE_WRAP_BYTES
    const val X25519_PUBLIC_KEY_BYTES: Int = 32
    const val X25519_PRIVATE_KEY_BYTES: Int = 32

    private val random = SecureRandom()

    /**
     * Generate a fresh X25519 keypair. Returns (privateRaw32, publicRaw32).
     */
    fun generateX25519Keypair(): Pair<ByteArray, ByteArray> {
        val sk = ByteArray(X25519_PRIVATE_KEY_BYTES)
        random.nextBytes(sk)
        val skParams = X25519PrivateKeyParameters(sk, 0)
        val pkParams = skParams.generatePublicKey()
        return sk to pkParams.encoded
    }

    /**
     * HPKE-open: recover the 32-byte CEK that the sender wrapped to
     * this device's X25519 keypair. Spec §11.4 / §11.5.
     *
     * Throws if the wrap is invalid or the AEAD tag fails — both
     * indicate either corruption or a server-substituted key.
     */
    fun openCekForDevice(
        privateKeyRaw: ByteArray,
        hpkeKemOutput: ByteArray,
        hpkeCiphertext: ByteArray,
        info: ByteArray,
    ): ByteArray {
        require(privateKeyRaw.size == X25519_PRIVATE_KEY_BYTES) {
            "X25519 private key must be $X25519_PRIVATE_KEY_BYTES bytes, got ${privateKeyRaw.size}"
        }
        require(hpkeKemOutput.size == HPKE_KEM_OUTPUT_BYTES) {
            "hpke_kem_output must be $HPKE_KEM_OUTPUT_BYTES bytes, got ${hpkeKemOutput.size}"
        }
        require(hpkeCiphertext.size == HPKE_WRAP_BYTES) {
            "hpke_ciphertext must be $HPKE_WRAP_BYTES bytes, got ${hpkeCiphertext.size}"
        }
        val hpke = HPKE(HPKE.mode_base.toByte(), KEM_ID.toShort(), KDF_ID.toShort(), AEAD_ID.toShort())
        val skParams = X25519PrivateKeyParameters(privateKeyRaw, 0)
        val pkParams = skParams.generatePublicKey()
        val keyPair = AsymmetricCipherKeyPair(pkParams, skParams)
        // SetupBaseR sets up the receiver context. seal/open run inside
        // the context. We explicitly pass aad=empty to match the PyCA
        // single-shot API which doesn't expose aad.
        val ctx = hpke.setupBaseR(hpkeKemOutput, keyPair, info)
        val cek = ctx.open(EMPTY_BYTES, hpkeCiphertext)
        check(cek.size == CEK_BYTES) {
            "opened CEK unexpected size: ${cek.size}"
        }
        return cek
    }

    /**
     * Lowercase hex SHA-256. Used in HPKE info to bind the CEK wrap
     * to a specific payload (spec §11.3 `payload_ciphertext_sha256`).
     */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (byte in digest) {
                append(HEX_CHARS[(byte.toInt() ushr 4) and 0x0f])
                append(HEX_CHARS[byte.toInt() and 0x0f])
            }
        }
    }

    private val EMPTY_BYTES = ByteArray(0)
    private const val HEX_CHARS = "0123456789abcdef"
}
