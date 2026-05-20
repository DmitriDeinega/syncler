package app.syncler.android.pluginhost

import app.syncler.core.crypto.Signing
import app.syncler.core.crypto.hexToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSignatureVerifierTest {
    private val verifier = PluginSignatureVerifier()

    @Test
    fun fixedVectorVerifies() {
        val canonical = verifier.canonicalManifestForSigning(vectorManifest)

        assertArrayEquals(CANONICAL_MANIFEST_HEX.hexToBytes(), canonical)
        assertTrue(verifier.verify(vectorManifest, ED25519_PUBLIC_KEY_HEX.hexToBytes()).isSuccess)
    }

    @Test
    fun tamperedManifestFails() {
        val tampered = vectorManifest + ("version" to "1.0.1")

        assertFalse(verifier.verify(tampered, ED25519_PUBLIC_KEY_HEX.hexToBytes()).isSuccess)
    }

    @Test
    fun tamperedSignatureFails() {
        val tampered = vectorManifest + ("signature" to ED25519_SIGNATURE_HEX.dropLast(2) + "00")

        assertFalse(verifier.verify(tampered, ED25519_PUBLIC_KEY_HEX.hexToBytes()).isSuccess)
    }

    @Test
    fun tamperedBundleHashFails() {
        val tampered = vectorManifest + ("bundleHash" to "00".repeat(32))

        assertFalse(verifier.verify(tampered, ED25519_PUBLIC_KEY_HEX.hexToBytes()).isSuccess)
    }

    companion object {
        private const val CANONICAL_MANIFEST_HEX =
            "7b2262756e646c6548617368223a223966383664303831383834633764363539613266656161306335356164303135" +
                "6133626634663162326230623832326364313564366331356230663030613038222c226e616d65223a225765617468" +
                "657220506c7567696e222c22706c7567696e4964223a22706c7567696e2e77656174686572222c2276657273696f" +
                "6e223a22312e302e30227d9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        private const val ED25519_PUBLIC_KEY_HEX = "712651f450ba05b63898b99ef5f7ba45632e8e2527f7f715cd671ec4024cc51e"
        private const val ED25519_SIGNATURE_HEX =
            "3d3a4963d6390f4392b36dac13938cadf015da019c6d0b2004e701656f544f6b" +
                "336bb9da81ef4fde0b392f3ac33884c7dbb40dcd6f0ac30f1bbc06a464e68a06"
        private val vectorManifest = mapOf(
            "name" to "Weather Plugin",
            "pluginId" to "plugin.weather",
            "version" to "1.0.0",
            "bundleHash" to "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            "signature" to ED25519_SIGNATURE_HEX,
        )
    }
}
