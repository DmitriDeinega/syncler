package app.syncler.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * V1.5 automated pairing bootstrap envelope.
 *
 * Implements the HPKE-style envelope from `docs/crypto-spec.md §9`:
 *
 *  1. ephemeral X25519 keypair
 *  2. ECDH against the sender's bootstrap public key
 *  3. HKDF-SHA256 with `salt = eph_pub || sender_bootstrap_pub` and
 *     `info = "syncler-v1-bootstrap-aead"` to derive a 32-byte AES key
 *  4. AES-256-GCM with a random 12-byte nonce and the canonical AAD
 *
 * The plaintext is `{"user_id": "...", "pairing_key": "<base64 32>"}`.
 * The sender's broker reconstructs the same `aead_key` via its own
 * private bootstrap key and the [ephemeralPubkey] from the wire
 * envelope, then AES-GCM-decrypts.
 *
 * Test vectors live alongside [SpecVectorsTest]; the canonical
 * Python reference is `sdk-python/syncler/bootstrap.py`. Cross-impl
 * byte-equivalence is asserted in CI.
 */
object BootstrapEnvelope {
    private const val GCM_TAG_BITS = 128
    private const val GCM_NONCE_BYTES = 12
    private const val X25519_KEY_BYTES = 32
    private const val AEAD_KEY_BYTES = 32
    private const val BOOTSTRAP_KEY_ID_BYTES = 16
    private val HKDF_INFO = "syncler-v1-bootstrap-aead".encodeToByteArray()

    /**
     * Build the bootstrap envelope for one pairing.
     *
     * Caller is responsible for sourcing the inputs from the trusted
     * `pairing/preview` response (which echoes them from the sender-
     * signed `pairing/initiate` envelope, so the syncler server can't
     * substitute them). The bootstrap key's Ed25519 signature MUST
     * be verified by the caller BEFORE invoking this method —
     * substitution is the threat we're guarding against.
     *
     * @param senderBootstrapPub the sender's raw 32-byte X25519 pub
     *   key (verified-and-trusted at the call site).
     * @param bootstrapKeyId the `SHA-256(senderBootstrapPub)[:16]`
     *   identifier the broker uses to key its decryption state.
     *   Computed locally to avoid an extra trust hop.
     * @param senderBrokerUrl the URL the sender supplied to
     *   `pairing/initiate`. Echoed by the server in `pairing/preview`
     *   under the sender's Ed25519 signature.
     * @param expIso the envelope's expiration as ISO8601 UTC with Z
     *   suffix. Caller picks the value (typically `now + 60s`); broker
     *   accepts a ±5min window. Must use the Z suffix, not `+00:00`.
     * @param plaintext canonical JSON for `{"user_id": "<uuid>",
     *   "pairing_key": "<base64 32 bytes>"}`. Caller builds it.
     * @param secureRandom injectable for deterministic tests; defaults
     *   to platform `SecureRandom`.
     */
    fun build(
        senderBootstrapPub: ByteArray,
        pairingId: String,
        senderId: String,
        senderBrokerUrl: String,
        expIso: String,
        plaintext: ByteArray,
        secureRandom: SecureRandom = SecureRandom(),
    ): Envelope {
        require(senderBootstrapPub.size == X25519_KEY_BYTES) {
            "senderBootstrapPub must be 32 bytes, got ${senderBootstrapPub.size}"
        }

        // 1. Ephemeral X25519 keypair.
        val ephSeed = ByteArray(X25519_KEY_BYTES).also(secureRandom::nextBytes)
        val ephPriv = X25519PrivateKeyParameters(ephSeed, 0)
        val ephPub = ephPriv.generatePublicKey().encoded

        // 2. ECDH against the sender's bootstrap pub.
        val sharedSecret = ByteArray(X25519_KEY_BYTES)
        ephPriv.generateSecret(
            X25519PublicKeyParameters(senderBootstrapPub, 0),
            sharedSecret,
            0,
        )

        // 3. HKDF-SHA256 → aead_key (32 bytes).
        val salt = ephPub + senderBootstrapPub
        val aeadKey = Hkdf.deriveSha256(
            ikm = sharedSecret,
            salt = salt,
            info = HKDF_INFO,
            length = AEAD_KEY_BYTES,
        )

        // 4. Canonical AAD JSON (sort_keys, ensure_ascii, compact
        //    separators — same encoder as message AAD §4).
        val bootstrapKeyId = MessageDigest.getInstance("SHA-256")
            .digest(senderBootstrapPub)
            .copyOf(BOOTSTRAP_KEY_ID_BYTES)
        val bootstrapKeyIdB64 = bootstrapKeyId.toBase64Std()
        val aadJson = buildBootstrapAadJson(
            bootstrapKeyIdB64 = bootstrapKeyIdB64,
            expIso = expIso,
            pairingId = pairingId,
            senderBrokerUrl = senderBrokerUrl,
            senderId = senderId,
        )

        // 5. AES-256-GCM encrypt.
        val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(aeadKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(aadJson)
        val ciphertext = cipher.doFinal(plaintext)

        return Envelope(
            ephemeralPubkey = ephPub,
            nonce = nonce,
            ciphertext = ciphertext,
            aadJson = aadJson,
            bootstrapKeyIdB64 = bootstrapKeyIdB64,
        )
    }

    /**
     * The canonical AAD JSON bytes. Visible for tests + the wire DTO
     * builder. Keys MUST be in this alphabetical order; `protocol_version`
     * is emitted as a JSON integer literal (no quotes); `expIso` must
     * use the Z suffix. Cross-impl byte-equivalence asserted in
     * `SpecVectorsTest` against `docs/crypto-spec.md §9.4`.
     */
    internal fun buildBootstrapAadJson(
        bootstrapKeyIdB64: String,
        expIso: String,
        pairingId: String,
        senderBrokerUrl: String,
        senderId: String,
    ): ByteArray = canonicalJsonBytes(
        mapOf(
            "bootstrap_key_id" to bootstrapKeyIdB64,
            "exp" to expIso,
            "pairing_id" to pairingId,
            "protocol_version" to 1,
            "sender_broker_url" to senderBrokerUrl,
            "sender_id" to senderId,
        ),
    )

    /**
     * Output of [build] — the four fields the wire envelope carries on
     * the POST body, plus [aadJson] (echoed by sender for AAD
     * reconstruction sanity) and [bootstrapKeyIdB64] (the broker
     * lookup key).
     */
    data class Envelope(
        val ephemeralPubkey: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val aadJson: ByteArray,
        val bootstrapKeyIdB64: String,
    )
}

private fun ByteArray.toBase64Std(): String =
    android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
