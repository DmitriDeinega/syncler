package app.syncler.feature.pluginnativesandbox

/**
 * Hand-rolled JSON helpers for [BridgePluginContext] (Phase 11).
 *
 * Extracted as top-level functions so the unit test surface is
 * directly callable without instantiating a [BridgePluginContext]
 * (which requires a Binder runtime).
 *
 * Triad 136 hardened these against control-char emission (escape),
 * escaped-quote handling (pickString), and comma-in-string
 * shredding (decodeStringList).
 */

private const val FORM_FEED = ''

/**
 * JSON-encode a plugin-supplied string. Escapes:
 *  - `"` `\` → `\"`, `\\`
 *  - `\n` `\r` `\t` `\b` `\f` → corresponding short escapes
 *  - any other ASCII control char (U+0000..U+001F) → `\uXXXX`
 *
 * Non-ASCII (≥ U+0080) is emitted verbatim. JSON permits this;
 * the host's parser handles it.
 */
internal fun jsonEscape(s: String): String {
    val out = StringBuilder(s.length + 2)
    out.append('"')
    for (c in s) when (c) {
        '"' -> out.append("\\\"")
        '\\' -> out.append("\\\\")
        '\n' -> out.append("\\n")
        '\r' -> out.append("\\r")
        '\t' -> out.append("\\t")
        '\b' -> out.append("\\b")
        FORM_FEED -> out.append("\\f")
        else -> if (c.code < 0x20) {
            out.append("\\u").append("%04x".format(c.code))
        } else {
            out.append(c)
        }
    }
    out.append('"')
    return out.toString()
}

/**
 * Extract the string value of [key] from [json]. Respects JSON
 * escapes within the value. Returns null if the key is missing
 * or the value is unterminated.
 *
 * Scoped to the host's canonical output shape; not a general
 * JSON parser.
 */
internal fun jsonPickString(json: String, key: String): String? {
    val k = "\"$key\":\""
    val start = json.indexOf(k)
    if (start < 0) return null
    var i = start + k.length
    val out = StringBuilder()
    while (i < json.length) {
        val c = json[i]
        if (c == '"') return out.toString()
        if (c == '\\' && i + 1 < json.length) {
            when (val n = json[i + 1]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append(FORM_FEED)
                else -> {
                    out.append(c); out.append(n)
                }
            }
            i += 2
            continue
        }
        out.append(c)
        i++
    }
    return null
}

/**
 * Decode the array under the `"items":[...]` key. Walks each
 * element respecting JSON string escapes so commas / quotes
 * inside strings don't corrupt the parse.
 */
internal fun jsonDecodeStringList(json: String): List<String> {
    val k = "\"items\":["
    val start = json.indexOf(k)
    if (start < 0) return emptyList()
    var i = start + k.length
    val items = mutableListOf<String>()
    while (i < json.length) {
        while (i < json.length && (json[i] == ' ' || json[i] == ',')) i++
        if (i < json.length && json[i] == ']') return items
        if (i >= json.length || json[i] != '"') return items
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '"') {
                items.add(sb.toString())
                i++
                break
            }
            if (c == '\\' && i + 1 < json.length) {
                when (val n = json[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append(FORM_FEED)
                    else -> {
                        sb.append(c); sb.append(n)
                    }
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
    }
    return items
}
