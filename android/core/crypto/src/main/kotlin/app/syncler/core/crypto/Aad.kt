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

internal fun canonicalJsonBytes(fields: Map<String, Any>): ByteArray =
    fields.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
        val encodedValue = when (value) {
            is Int -> value.toString()
            is String -> "\"${value.jsonEscape()}\""
            else -> error("unsupported AAD value")
        }
        "\"$key\":$encodedValue"
    }.encodeToByteArray()

private fun String.jsonEscape(): String = buildString {
    for (char in this@jsonEscape) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '' -> append("\\f")
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
