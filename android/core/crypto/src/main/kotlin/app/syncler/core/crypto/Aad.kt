package app.syncler.core.crypto

data class MessageAad(
    val messageId: String,
    val senderId: String,
    val userId: String,
    val pluginId: String,
    val minPluginVersion: Int,
    val createdAt: String,
    val schemaVersion: Int,
)

fun MessageAad.toCanonicalJsonBytes(): ByteArray {
    val fields = linkedMapOf(
        "created_at" to createdAt,
        "message_id" to messageId,
        "min_plugin_version" to minPluginVersion,
        "plugin_id" to pluginId,
        "schema_version" to schemaVersion,
        "sender_id" to senderId,
        "user_id" to userId,
    )
    return fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
        val encodedValue = when (value) {
            is Int -> value.toString()
            is String -> "\"${value.jsonEscape()}\""
            else -> error("unsupported AAD value")
        }
        "\"$key\":$encodedValue"
    }.encodeToByteArray()
}

private fun String.jsonEscape(): String = buildString {
    for (char in this@jsonEscape) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
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
