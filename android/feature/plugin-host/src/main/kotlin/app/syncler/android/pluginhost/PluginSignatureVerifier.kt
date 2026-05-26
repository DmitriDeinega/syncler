package app.syncler.android.pluginhost

import app.syncler.core.crypto.Signing
import app.syncler.core.crypto.hexToBytes
import java.nio.charset.StandardCharsets

class PluginSignatureVerifier(
    private val auditLogger: AuditLogger = AuditLogger(),
) {
    fun verify(rawManifest: Map<String, Any?>, expectedSenderPublicKey: ByteArray): Result<ByteArray> =
        runCatching {
            val signatureHex = rawManifest["signature"] as? String
                ?: throw IllegalArgumentException("manifest signature is missing")
            val canonical = canonicalManifestForSigning(rawManifest)
            val signature = signatureHex.hexToBytes()
            if (!Signing.verify(expectedSenderPublicKey, canonical, signature)) {
                auditLogger.record(rawManifest.pluginIdForAudit(), "signature_invalid")
                throw SecurityException("plugin signature is invalid")
            }
            canonical
        }.onFailure {
            auditLogger.record(rawManifest.pluginIdForAudit(), "signature_verification_failed", it.message)
        }

    fun canonicalManifestForSigning(rawManifest: Map<String, Any?>): ByteArray {
        val withoutSignature = rawManifest
            .filterKeys { it != "signature" }
        val bundleHash = (withoutSignature["bundleHash"] as? String)
            ?: throw IllegalArgumentException("manifest bundleHash is missing")
        val canonicalJson = canonicalJson(withoutSignature)
        return canonicalJson.toByteArray(StandardCharsets.UTF_8) + bundleHash.hexToBytes()
    }

    private fun Map<String, Any?>.pluginIdForAudit(): String? =
        this["id"] as? String ?: this["pluginId"] as? String

    private fun canonicalJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Number -> numberToJson(value)
        is Map<*, *> -> value.entries
            .map { (key, _) -> key as? String ?: throw IllegalArgumentException("JSON object key must be a string") }
            .sorted()
            .joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                "${quote(key)}:${canonicalJson(value[key])}"
            }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { canonicalJson(it) }
        else -> throw IllegalArgumentException("Unsupported manifest JSON value: ${value::class.java.name}")
    }

    private fun numberToJson(value: Number): String {
        val doubleValue = value.toDouble()
        require(!doubleValue.isNaN() && !doubleValue.isInfinite()) { "JSON numbers must be finite" }
        return when (value) {
            is Byte, is Short, is Int, is Long -> value.toLong().toString()
            is Float, is Double -> {
                val asLong = doubleValue.toLong()
                if (doubleValue == asLong.toDouble()) asLong.toString() else value.toString()
            }
            else -> value.toString()
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20 || char.code > 0x7E) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }
}
