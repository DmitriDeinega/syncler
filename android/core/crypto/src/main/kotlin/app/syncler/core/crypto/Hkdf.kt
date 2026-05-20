package app.syncler.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hkdf {
    private const val HMAC_SHA256 = "HmacSHA256"
    private val LABEL_PAIRING_KEY = "syncler-v1-pairing-key:".encodeToByteArray()

    fun derivePairingKey(masterKey: ByteArray, senderId: ByteArray): ByteArray {
        require(masterKey.size == KEY_SIZE_BYTES)
        val info = LABEL_PAIRING_KEY + senderId
        return deriveSha256(ikm = masterKey, salt = senderId, info = info, length = KEY_SIZE_BYTES)
    }

    fun deriveSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * KEY_SIZE_BYTES)
        val prk = hmac(salt, ikm)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var generated = 0
        var counter = 1
        while (generated < length) {
            val block = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val bytesToCopy = minOf(block.size, length - generated)
            block.copyInto(output, destinationOffset = generated, endIndex = bytesToCopy)
            generated += bytesToCopy
            previous = block
            counter += 1
        }
        return output
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray =
        Mac.getInstance(HMAC_SHA256).run {
            init(SecretKeySpec(key, HMAC_SHA256))
            doFinal(input)
        }
}
