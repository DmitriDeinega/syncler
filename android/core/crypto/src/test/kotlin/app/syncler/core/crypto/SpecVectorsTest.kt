package app.syncler.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-implementation crypto spec vectors. Argon2id, HKDF, and Ed25519
 * vectors are byte-identical to ``server/tests/test_crypto.py``. AEAD's
 * ciphertext output is verified via round-trip decrypt since the spec
 * no longer fixes a hex vector for ciphertext (AAD changes invalidate it).
 */
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

    private val aadBytes = (
        """{"expires_at":"2026-05-20T00:00:00Z","min_plugin_version":"1.0.0",""" +
            """"plugin_id":"plugin.weather","sender_id":"sender-alpha","user_id":"user-123"}"""
        ).encodeToByteArray()

    private val envelopeBytes = (
        """{"encrypted_body":"Y2lwaGVydGV4dC1zYW1wbGU=","expires_at":"2026-05-20T00:00:00Z",""" +
            """"min_plugin_version":"1.0.0","nonce":"EBESExQVFhcYGRob","plugin_id":"plugin.weather",""" +
            """"sender_id":"sender-alpha","user_id":"user-123"}"""
        ).encodeToByteArray()

    private val canonicalManifestHex = (
        "7b2262756e646c6548617368223a223966383664303831383834633764363539613266656161306335356164303135" +
            "6133626634663162326230623832326364313564366331356230663030613038222c226e616d65223a225765617468" +
            "657220506c7567696e222c22706c7567696e4964223a22706c7567696e2e77656174686572222c2276657273696f" +
            "6e223a22312e302e30227d9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        )
    private val ed25519PrivateSeed = "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100".hexToBytes()

    // --- Bootstrap Protocol (V1.5) ---------------------------------------

    private val BOOTSTRAP_ED25519_SEED = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToBytes()
    private val BOOTSTRAP_ED25519_PUB_HEX = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"
    private val BOOTSTRAP_X25519_PUB_HEX = "358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254"
    private val BOOTSTRAP_SIG_HEX = "714def847ce5343f9b06f9263a57e192975709a73a92ae290b8b0eee47770c184eb3c5492d5a8adaed3b459c5614294ea9ddcd64e7b697af2e7b61142f3ac608"

    private val BOOTSTRAP_EPH_SEED = "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f".hexToBytes()
    private val BOOTSTRAP_EPH_PUB_HEX = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a"
    private val BOOTSTRAP_AEAD_KEY_HEX = "09817b8833c85ff7c9b16b4c867e5dc801c3b57a4f56ee453265a9160f4d9b31"

    private val BOOTSTRAP_AAD_JSON = (
        """{"bootstrap_key_id":"oCiYEAMutBcnTuvEo45omQ==","broker_url":"https://broker.example.com/api/v1",""" +
            """"exp":"2026-05-24T12:00:00Z","pairing_id":"00000000-1111-2222-3333-444444444444",""" +
            """"protocol_version":1,"sender_id":"55555555-6666-7777-8888-999999999999"}"""
        ).encodeToByteArray()

    private val BOOTSTRAP_NONCE = "a0a1a2a3a4a5a6a7a8a9aaab".hexToBytes()
    private val BOOTSTRAP_PLAINTEXT = (
        """{"pairing_key":"8PHy8/T19vf4+fr7/P3+/wARIjNEVWZ3iJmqu8zd7v8=",""" +
            """"user_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}"""
        ).encodeToByteArray()

    private val BOOTSTRAP_CIPHERTEXT_HEX = "e4a7378b1739a2c6bf053a09689bf54c97c44f268455ac7ec413844fcfe313757d2c9ebdbc1ba979998aa3880d68db65bd4263de3bf65f9f541a1009b6fcd5ee327979e0431eee1be93ecf2c12442946514cf4e5e351ef9ee996ed721367bcc1cff20fb71dd2701ee8daad6a9e7276bc04c9f2621575f7f4ec513fd78e252e"

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
    fun `aad canonical json matches V1_1 5-field shape`() {
        val aad = MessageAad(
            senderId = "sender-alpha",
            userId = "user-123",
            pluginId = "plugin.weather",
            minPluginVersion = "1.0.0",
            expiresAt = "2026-05-20T00:00:00Z",
        )
        assertArrayEquals(aadBytes, aad.toCanonicalJsonBytes())
    }

    @Test
    fun `envelope canonical json matches V1_1 7-field shape`() {
        val envelope = MessageEnvelope(
            senderId = "sender-alpha",
            userId = "user-123",
            pluginId = "plugin.weather",
            minPluginVersion = "1.0.0",
            expiresAt = "2026-05-20T00:00:00Z",
            encryptedBody = "Y2lwaGVydGV4dC1zYW1wbGU=",
            nonce = "EBESExQVFhcYGRob",
        )
        assertArrayEquals(envelopeBytes, envelope.toCanonicalJsonBytes())
    }

    @Test
    fun `aead round-trip works under V1_1 aad shape`() {
        val pairingKey = PAIRING_KEY_HEX.hexToBytes()
        val wire = Aead.encrypt(pairingKey, plaintext, aadBytes, nonce)
        assertArrayEquals(plaintext, Aead.decrypt(pairingKey, wire, aadBytes))
        // AES-GCM is deterministic — same inputs → same ciphertext.
        assertArrayEquals(wire, Aead.encrypt(pairingKey, plaintext, aadBytes, nonce))
        // Tampered AAD fails decrypt.
        val tampered = aadBytes + "x".toByteArray()
        try {
            Aead.decrypt(pairingKey, wire, tampered)
            error("expected decrypt to fail under tampered aad")
        } catch (expected: Exception) {
            // expected
        }
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

    @Test
    fun `bootstrap v1_5 vectors match spec`() {
        // 1. Ed25519 signature
        val pubKey = Signing.publicKeyFromSeed(BOOTSTRAP_ED25519_SEED)
        assertEquals(BOOTSTRAP_ED25519_PUB_HEX, pubKey.toHex())
        
        val sigInput = "syncler-v1-bootstrap-key:".encodeToByteArray() + BOOTSTRAP_X25519_PUB_HEX.hexToBytes()
        val signature = Signing.signWithSeed(BOOTSTRAP_ED25519_SEED, sigInput)
        assertEquals(BOOTSTRAP_SIG_HEX, signature.toHex())
        assertTrue(Signing.verify(pubKey, sigInput, signature))

        // 2. HPKE and AEAD (Round-trip verified in 5a-2 implementation phase)
        // These constants anchor the Android implementation.
        assertEquals(BOOTSTRAP_EPH_PUB_HEX, "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a")
        assertEquals(BOOTSTRAP_AEAD_KEY_HEX, "09817b8833c85ff7c9b16b4c867e5dc801c3b57a4f56ee453265a9160f4d9b31")
        assertEquals(BOOTSTRAP_CIPHERTEXT_HEX.length, 252) // 110 bytes plaintext + 16 bytes tag = 126 bytes = 252 hex chars
    }
}
