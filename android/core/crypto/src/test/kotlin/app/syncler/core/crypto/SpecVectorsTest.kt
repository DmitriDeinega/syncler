package app.syncler.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecVectorsTest {
    private val ARGON2_HASH_HEX = "e23ed7b136661e69f2424d8440777943827d9981e2fb409d69e48bce72dd7f82c8327524d69330d1993ba67e26a3576718d29f0602e44a881d924ca36836699c"
    private val AUTH_KEY_HEX = "e23ed7b136661e69f2424d8440777943827d9981e2fb409d69e48bce72dd7f82"
    private val WRAP_KEY_HEX = "c8327524d69330d1993ba67e26a3576718d29f0602e44a881d924ca36836699c"
    private val PAIRING_KEY_HEX = "f6ed649481dd8a5ffc57401b816803fba79556731c5c9ff53be49f7862f8cb8e"
    private val ED25519_PUBKEY_HEX = "712651f450ba05b63898b99ef5f7ba45632e8e2527f7f715cd671ec4024cc51e"
    private val ED25519_SIG_HEX = "3d3a4963d6390f4392b36dac13938cadf015da019c6d0b2004e701656f544f6b336bb9da81ef4fde0b392f3ac33884c7dbb40dcd6f0ac30f1bbc06a464e68a06"

    private val argon2Password = "syncler-test-password".toCharArray()
    private val argon2Salt = "00112233445566778899aabbccddeeff".hexToBytes()
    private val masterKey = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToBytes()
    private val senderId = "sender-alpha".encodeToByteArray()
    private val nonce = "101112131415161718191a1b".hexToBytes()
    private val plaintext = """{"temperature_c":21}""".encodeToByteArray()
    private val ciphertextWithTagHex = "3fefbb1a0238b24f6860563d1e3194c9bf47d1dfdb255e7b87ad60d5ab9b07573bbf55bc"
    private val wireHex = "101112131415161718191a1b3fefbb1a0238b24f6860563d1e3194c9bf47d1dfdb255e7b87ad60d5ab9b07573bbf55bc"
    private val aadBytes = (
        """{"created_at":"2026-05-20T00:00:00Z","message_id":"msg-001",""" +
            """"min_plugin_version":1,"plugin_id":"plugin.weather","schema_version":1,""" +
            """"sender_id":"sender-alpha","user_id":"user-123"}"""
        ).encodeToByteArray()
    private val canonicalManifestHex = (
        "7b2262756e646c6548617368223a223966383664303831383834633764363539613266656161306335356164303135" +
            "6133626634663162326230623832326364313564366331356230663030613038222c226e616d65223a225765617468" +
            "657220506c7567696e222c22706c7567696e4964223a22706c7567696e2e77656174686572222c2276657273696f" +
            "6e223a22312e302e30227d9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        )
    private val ed25519PrivateSeed = "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100".hexToBytes()

    @Test
    fun `argon2id derives spec vector`() {
        val keys = KeyDerivation.derive(argon2Password, argon2Salt)
        val derivedHash = keys.authKey + keys.masterKeyWrapKey

        assertEquals(ARGON2_HASH_HEX, derivedHash.toHex())
        assertEquals(AUTH_KEY_HEX, keys.authKey.toHex())
        assertEquals(WRAP_KEY_HEX, keys.masterKeyWrapKey.toHex())
        assertEquals(KeyDerivation.PARAMS_VERSION, 1)
    }

    @Test
    fun `hkdf pairing key matches spec`() {
        val pairingKey = Hkdf.derivePairingKey(masterKey, senderId)

        assertEquals(PAIRING_KEY_HEX, pairingKey.toHex())
        assertArrayEquals(pairingKey, Hkdf.derivePairingKey(masterKey, senderId))
        assertFalse(pairingKey.contentEquals(Hkdf.derivePairingKey(masterKey, "sender-beta".encodeToByteArray())))
    }

    @Test
    fun `aad canonical json sorts non alphabetical input`() {
        val fields = linkedMapOf<String, Any>(
            "message_id" to "msg-001",
            "sender_id" to "sender-alpha",
            "user_id" to "user-123",
            "plugin_id" to "plugin.weather",
            "min_plugin_version" to 1,
            "created_at" to "2026-05-20T00:00:00Z",
            "schema_version" to 1,
        )

        assertArrayEquals(aadBytes, canonicalJsonBytes(fields))
    }

    @Test
    fun `aead wire format roundtrip matches spec`() {
        val aad = canonicalJsonBytes(
            linkedMapOf(
                "message_id" to "msg-001",
                "sender_id" to "sender-alpha",
                "user_id" to "user-123",
                "plugin_id" to "plugin.weather",
                "min_plugin_version" to 1,
                "created_at" to "2026-05-20T00:00:00Z",
                "schema_version" to 1,
            ),
        )
        val wire = Aead.encrypt(PAIRING_KEY_HEX.hexToBytes(), plaintext, aad, nonce)

        assertEquals(ciphertextWithTagHex, wire.copyOfRange(12, wire.size).toHex())
        assertEquals(wireHex, wire.toHex())
        assertArrayEquals(plaintext, Aead.decrypt(PAIRING_KEY_HEX.hexToBytes(), wire, aad))
    }

    @Test
    fun `ed25519 verify accepts spec signature`() {
        val message = canonicalManifestHex.hexToBytes()
        val publicKey = Signing.publicKeyFromSeed(ed25519PrivateSeed)
        val signature = Signing.signWithSeed(ed25519PrivateSeed, message)

        assertEquals(ED25519_PUBKEY_HEX, publicKey.toHex())
        assertEquals(ED25519_SIG_HEX, signature.toHex())
        assertTrue(Signing.verify(publicKey, message, signature))
        assertFalse(Signing.verify(publicKey, message + byteArrayOf(0), signature))
    }
}
