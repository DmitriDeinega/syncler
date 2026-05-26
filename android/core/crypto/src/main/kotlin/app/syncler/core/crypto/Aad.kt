package app.syncler.core.crypto

/**
 * AAD (AES-GCM additional authenticated data).
 *
 * Protocol context bound to the ciphertext but NOT including the ciphertext
 * itself. All fields are sender-known at signing time. Server-generated
 * metadata (message_id, created_at, schema_version) is NOT part of AAD —
 * replay protection is handled by the server's per-sender nonce registry.
 */
data class MessageAad(
    val senderId: String,
    val userId: String,
    val pluginId: String,
    val minPluginVersion: String,
    /** ISO-8601 UTC, must be a future instant ≤ 30d from now. */
    val expiresAt: String,
)

fun MessageAad.toCanonicalJsonBytes(): ByteArray =
    canonicalJsonBytes(
        mapOf(
            "expires_at" to expiresAt,
            "min_plugin_version" to minPluginVersion,
            "plugin_id" to pluginId,
            "sender_id" to senderId,
            "user_id" to userId,
        ),
    )

/**
 * Envelope (Ed25519 signing input) — AAD plus base64 ciphertext and nonce.
 */
data class MessageEnvelope(
    val senderId: String,
    val userId: String,
    val pluginId: String,
    val minPluginVersion: String,
    val expiresAt: String,
    /** Base64 of the AES-GCM ciphertext_with_tag (no nonce prefix). */
    val encryptedBody: String,
    /** Base64 of the 12-byte nonce. */
    val nonce: String,
)

fun MessageEnvelope.toCanonicalJsonBytes(): ByteArray =
    canonicalJsonBytes(
        mapOf(
            "encrypted_body" to encryptedBody,
            "expires_at" to expiresAt,
            "min_plugin_version" to minPluginVersion,
            "nonce" to nonce,
            "plugin_id" to pluginId,
            "sender_id" to senderId,
            "user_id" to userId,
        ),
    )

/**
 * AAD for persistent live cards (Phase 3b).
 */
data class LiveCardAad(
    val senderId: String,
    val userId: String,
    val pluginId: String,
    val cardKey: String,
    val sequenceNumber: Long,
    val expiresAt: String,
)

fun LiveCardAad.toCanonicalJsonBytes(): ByteArray =
    canonicalJsonBytes(
        mapOf(
            "card_key" to cardKey,
            "card_type" to "live",
            "expires_at" to expiresAt,
            "plugin_id" to pluginId,
            "sequence_number" to sequenceNumber,
            "sender_id" to senderId,
            "user_id" to userId,
        ),
    )

/**
 * Phase 8d AADs (docs/crypto-spec.md §10.9). All emit canonical JSON
 * bytes: keys sorted lexicographically, `(",", ":")` separators, no
 * trailing whitespace, ASCII (server uses pyjwt-style
 * `ensure_ascii=True`). UUIDs are formatted lowercase no-brace —
 * `java.util.UUID.toString()` already produces that shape.
 */
object RotationAad {
    /**
     * `users.encrypted_master_key` AAD per §10.9. NOT bound to
     * `key_generation` — the wrapped MK is the chicken-and-egg root.
     */
    fun masterKeyWrap(userId: String, authSaltB64: String): ByteArray =
        canonicalJsonBytes(
            mapOf(
                "auth_salt_b64" to authSaltB64,
                "user_id" to userId,
            ),
        )

    /**
     * `encrypted_user_state.encrypted_blob` AAD per §10.9.
     * `stateVersion` is the POST-write value (the version the row
     * will carry AFTER the server's CAS). §10.4 lockstep contract.
     */
    fun userState(userId: String, keyGeneration: Int, stateVersion: Int): ByteArray =
        canonicalJsonBytes(
            mapOf(
                "key_generation" to keyGeneration,
                "state_version" to stateVersion,
                "user_id" to userId,
            ),
        )

    /**
     * `pairings.encrypted_state` AAD per §10.9. Same lockstep rule
     * for `stateVersion`. `pairingId` lowercase no-brace UUID.
     */
    fun pairingState(
        userId: String,
        pairingId: String,
        keyGeneration: Int,
        stateVersion: Int,
    ): ByteArray =
        canonicalJsonBytes(
            mapOf(
                "key_generation" to keyGeneration,
                "pairing_id" to pairingId,
                "state_version" to stateVersion,
                "user_id" to userId,
            ),
        )
}

/**
 * Phase 9b V2 canonical bytes (docs/crypto-spec.md §11.3, §11.7).
 * `aad` and `info` builders for the per-device envelope path.
 * Cross-platform invariant: byte-identical to the server's
 * `app/services/envelopes_v2.py` + SDK's `crypto.py`.
 */
object V2Aad {
    /** Event payload AAD (AES-GCM) per spec §11.7. */
    fun eventPayloadAad(
        senderId: String,
        userId: String,
        pluginId: String,
        expiresAt: String,
        minPluginVersion: String,
    ): ByteArray = canonicalJsonBytes(
        mapOf(
            "envelope_kind" to "event",
            "expires_at" to expiresAt,
            "min_plugin_version" to minPluginVersion,
            "plugin_id" to pluginId,
            "protocol_version" to 2,
            "sender_id" to senderId,
            "user_id" to userId,
        ),
    )

    /** Live-card upsert payload AAD per spec §11.7. */
    fun liveCardPayloadAad(
        senderId: String,
        userId: String,
        pluginId: String,
        cardKey: String,
        cardType: String,
        sequenceNumber: Long,
        expiresAt: String,
        minPluginVersion: String,
    ): ByteArray = canonicalJsonBytes(
        mapOf(
            "card_key" to cardKey,
            "card_type" to cardType,
            "envelope_kind" to "live_card_upsert",
            "expires_at" to expiresAt,
            "min_plugin_version" to minPluginVersion,
            "plugin_id" to pluginId,
            "protocol_version" to 2,
            "sender_id" to senderId,
            "sequence_number" to sequenceNumber,
            "user_id" to userId,
        ),
    )

    /** Per-recipient HPKE info for event publish per spec §11.3. */
    fun eventHpkeInfo(
        senderId: String,
        userId: String,
        pluginId: String,
        expiresAt: String,
        minPluginVersion: String,
        payloadNonceB64: String,
        payloadCiphertextSha256Hex: String,
        deviceId: String,
    ): ByteArray = canonicalJsonBytes(
        mapOf(
            "device_id" to deviceId,
            "envelope_kind" to "event",
            "expires_at" to expiresAt,
            "min_plugin_version" to minPluginVersion,
            "payload_ciphertext_sha256" to payloadCiphertextSha256Hex,
            "payload_nonce" to payloadNonceB64,
            "plugin_id" to pluginId,
            "protocol_version" to 2,
            "sender_id" to senderId,
            "user_id" to userId,
        ),
    )

    /**
     * Phase 9b §11.8: canonical bytes the sender signed for an event
     * publish. The Android client reconstructs this from the V2 inbox
     * DTO and verifies the Ed25519 signature against the trusted
     * paired sender's public key BEFORE displaying the decrypted
     * payload — without this verification a malicious server could
     * forge envelopes using the device's public encryption key.
     *
     * `recipient_envelopes` MUST be sorted by lowercase device_id
     * (the same order the server's `_serialize_recipient_envelopes`
     * produces); the client passes the already-sorted list.
     */
    fun eventSignedEnvelopeBytes(
        senderId: String,
        userId: String,
        pluginId: String,
        expiresAt: String,
        minPluginVersion: String,
        payloadNonceB64: String,
        payloadCiphertextB64: String,
        recipientEnvelopesSerialized: List<Map<String, String>>,
        recipientDirectoryVersion: Long,
    ): ByteArray = canonicalJsonBytes(
        mapOf(
            "envelope_kind" to "event",
            "expires_at" to expiresAt,
            "min_plugin_version" to minPluginVersion,
            "payload_ciphertext" to payloadCiphertextB64,
            "payload_nonce" to payloadNonceB64,
            "plugin_id" to pluginId,
            "protocol_version" to 2,
            "recipient_directory_version" to recipientDirectoryVersion,
            "recipient_envelopes" to recipientEnvelopesSerialized,
            "sender_id" to senderId,
            "user_id" to userId,
        ),
    )

    /** Spec §11.8: canonical bytes for live_card_upsert. */
    fun liveCardUpsertSignedEnvelopeBytes(
        senderId: String,
        userId: String,
        pluginId: String,
        cardKey: String,
        cardType: String,
        sequenceNumber: Long,
        expiresAt: String,
        minPluginVersion: String,
        payloadNonceB64: String,
        payloadCiphertextB64: String,
        recipientEnvelopesSerialized: List<Map<String, String>>,
        recipientDirectoryVersion: Long,
    ): ByteArray = canonicalJsonBytes(
        mapOf(
            "card_key" to cardKey,
            "card_type" to cardType,
            "envelope_kind" to "live_card_upsert",
            "expires_at" to expiresAt,
            "min_plugin_version" to minPluginVersion,
            "payload_ciphertext" to payloadCiphertextB64,
            "payload_nonce" to payloadNonceB64,
            "plugin_id" to pluginId,
            "protocol_version" to 2,
            "recipient_directory_version" to recipientDirectoryVersion,
            "recipient_envelopes" to recipientEnvelopesSerialized,
            "sender_id" to senderId,
            "sequence_number" to sequenceNumber,
            "user_id" to userId,
        ),
    )

    /**
     * V3 #16 — payload AAD for envelope_kind="card_patch".
     * Binds the encrypted patches list to (card_id, base_seq,
     * patch_seq) instead of expires_at/min_plugin_version; the
     * patch inherits the parent card's lifetime so no per-patch
     * TTL is on the envelope.
     */
    fun cardPatchPayloadAad(
        senderId: String,
        userId: String,
        pluginId: String,
        cardId: String,
        baseSeq: Long,
        patchSeq: Long,
    ): ByteArray = canonicalJsonBytes(
        mapOf(
            "base_seq" to baseSeq,
            "card_id" to cardId,
            "envelope_kind" to "card_patch",
            "patch_seq" to patchSeq,
            "plugin_id" to pluginId,
            "protocol_version" to 2,
            "sender_id" to senderId,
            "user_id" to userId,
        ),
    )

    /** V3 #16 — per-recipient HPKE info for envelope_kind="card_patch". */
    fun cardPatchHpkeInfo(
        senderId: String,
        userId: String,
        pluginId: String,
        cardId: String,
        baseSeq: Long,
        patchSeq: Long,
        payloadNonceB64: String,
        payloadCiphertextSha256Hex: String,
        deviceId: String,
    ): ByteArray = canonicalJsonBytes(
        mapOf(
            "base_seq" to baseSeq,
            "card_id" to cardId,
            "device_id" to deviceId,
            "envelope_kind" to "card_patch",
            "patch_seq" to patchSeq,
            "payload_ciphertext_sha256" to payloadCiphertextSha256Hex,
            "payload_nonce" to payloadNonceB64,
            "plugin_id" to pluginId,
            "protocol_version" to 2,
            "sender_id" to senderId,
            "user_id" to userId,
        ),
    )

    /**
     * V3 #16 — canonical Ed25519-signed bytes for card_patch.
     * Mirrors server's build_card_patch_envelope_bytes in
     * app/services/envelopes_v2.py. recipient_envelopes MUST
     * be sorted by lowercase device_id.
     */
    fun cardPatchSignedEnvelopeBytes(
        senderId: String,
        userId: String,
        pluginId: String,
        cardId: String,
        baseSeq: Long,
        patchSeq: Long,
        payloadNonceB64: String,
        payloadCiphertextB64: String,
        recipientEnvelopesSerialized: List<Map<String, String>>,
        recipientDirectoryVersion: Long,
    ): ByteArray = canonicalJsonBytes(
        mapOf(
            "base_seq" to baseSeq,
            "card_id" to cardId,
            "envelope_kind" to "card_patch",
            "patch_seq" to patchSeq,
            "payload_ciphertext" to payloadCiphertextB64,
            "payload_nonce" to payloadNonceB64,
            "plugin_id" to pluginId,
            "protocol_version" to 2,
            "recipient_directory_version" to recipientDirectoryVersion,
            "recipient_envelopes" to recipientEnvelopesSerialized,
            "sender_id" to senderId,
            "user_id" to userId,
        ),
    )

    /** Per-recipient HPKE info for live_card_upsert per spec §11.3. */
    fun liveCardHpkeInfo(
        senderId: String,
        userId: String,
        pluginId: String,
        cardKey: String,
        cardType: String,
        sequenceNumber: Long,
        expiresAt: String,
        minPluginVersion: String,
        payloadNonceB64: String,
        payloadCiphertextSha256Hex: String,
        deviceId: String,
    ): ByteArray = canonicalJsonBytes(
        mapOf(
            "card_key" to cardKey,
            "card_type" to cardType,
            "device_id" to deviceId,
            "envelope_kind" to "live_card_upsert",
            "expires_at" to expiresAt,
            "min_plugin_version" to minPluginVersion,
            "payload_ciphertext_sha256" to payloadCiphertextSha256Hex,
            "payload_nonce" to payloadNonceB64,
            "plugin_id" to pluginId,
            "protocol_version" to 2,
            "sender_id" to senderId,
            "sequence_number" to sequenceNumber,
            "user_id" to userId,
        ),
    )
}

internal fun canonicalJsonBytes(fields: Map<String, Any>): ByteArray =
    encodeCanonicalValue(fields).encodeToByteArray()

private fun encodeCanonicalValue(value: Any): String = when (value) {
    is Int -> value.toString()
    is Long -> value.toString()
    is String -> "\"${value.jsonEscape()}\""
    is Map<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        val map = value as Map<String, Any>
        map.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"$k\":${encodeCanonicalValue(v)}"
        }
    }
    is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { item ->
        encodeCanonicalValue(item ?: error("null in canonical list"))
    }
    else -> error("unsupported canonical JSON value type: ${value::class.simpleName}")
}

private fun String.jsonEscape(): String = buildString {
    for (char in this@jsonEscape) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20 || char.code > 0x7E) {
                append("\\u%04x".format(char.code))
            } else {
                append(char)
            }
        }
    }
}
