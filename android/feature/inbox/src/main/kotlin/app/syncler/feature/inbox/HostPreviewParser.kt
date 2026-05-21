package app.syncler.feature.inbox

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Pure parser/validator for the optional `hostPreview` block in a decrypted
 * message payload. Mirrors the validation in `sdk-plugin/src/preview.ts` and
 * `sdk-python/syncler/preview.py` — same byte caps, same shape rejection
 * rules. Three deliberate differences from the sender-side validators:
 *
 * 1. Returns null instead of throwing on any violation. The inbox must never
 *    crash on a malformed payload from a foreign sender; falling back to the
 *    generic row is the safe move.
 * 2. Logs every violation at warn level via Timber so the diagnostic chain
 *    is visible without surfacing alarm UI.
 * 3. Drops empty `searchText` tokens silently when assembling the list (the
 *    SDKs accept them at send time; the host doesn't want to index them).
 *
 * Caps and field semantics are documented in `docs/integration-guide.md` §2.
 */
internal object HostPreviewParser {
    private const val TAG = "HostPreviewParser"
    const val TITLE_MAX_BYTES = 80
    const val SUBTITLE_MAX_BYTES = 120
    const val SUMMARY_MAX_BYTES = 240
    const val SEARCH_TEXT_MAX_ENTRIES = 16
    const val SEARCH_TEXT_ENTRY_MAX_BYTES = 64
    const val TOTAL_MAX_BYTES = 2048

    /**
     * Parses the `hostPreview` block from a decrypted payload JSON string.
     * Returns null if the block is missing entirely OR if it fails any
     * validation rule.
     */
    fun parse(plaintextJson: String): HostPreview? = runCatching {
        val root = JSONObject(plaintextJson)
        if (!root.has("hostPreview")) return@runCatching null
        val rawHostPreview = root.get("hostPreview")
        if (rawHostPreview !is JSONObject) {
            warn("hostPreview is not a JSON object (got %s); falling back",
                rawHostPreview.javaClass.simpleName)
            return@runCatching null
        }
        val obj = rawHostPreview

        // Total-size cap defends against unknown extension fields that bypass
        // per-field validators. Mirrors sdk-python and sdk-plugin.
        val totalBytes = obj.toString().toByteArray(Charsets.UTF_8).size
        if (totalBytes > TOTAL_MAX_BYTES) {
            warn("hostPreview is %d bytes; max %d", totalBytes, TOTAL_MAX_BYTES)
            return@runCatching null
        }

        val title = readRequiredString(obj, "title", TITLE_MAX_BYTES, allowBlank = false)
            ?: return@runCatching null

        val subtitle = if (obj.has("subtitle"))
            readRequiredString(obj, "subtitle", SUBTITLE_MAX_BYTES, allowBlank = true)
                ?: return@runCatching null
        else null

        val summary = if (obj.has("summary"))
            readRequiredString(obj, "summary", SUMMARY_MAX_BYTES, allowBlank = true)
                ?: return@runCatching null
        else null

        val searchText = mutableListOf<String>()
        if (obj.has("searchText")) {
            val raw = obj.get("searchText")
            if (raw !is JSONArray) {
                warn("hostPreview.searchText is not an array; falling back")
                return@runCatching null
            }
            if (raw.length() > SEARCH_TEXT_MAX_ENTRIES) {
                warn("hostPreview.searchText has %d entries; max %d", raw.length(), SEARCH_TEXT_MAX_ENTRIES)
                return@runCatching null
            }
            for (i in 0 until raw.length()) {
                val token = raw.get(i)
                if (token !is String) {
                    warn("hostPreview.searchText[%d] is not a string; falling back", i)
                    return@runCatching null
                }
                if (token.toByteArray(Charsets.UTF_8).size > SEARCH_TEXT_ENTRY_MAX_BYTES) {
                    warn("hostPreview.searchText[%d] exceeds %d bytes; falling back",
                        i, SEARCH_TEXT_ENTRY_MAX_BYTES)
                    return@runCatching null
                }
                // Empty tokens add no index value — drop silently rather than
                // reject the whole block over them. SDKs also accept empties.
                if (token.isNotEmpty()) searchText += token
            }
        }

        HostPreview(
            title = title,
            subtitle = subtitle?.takeIf { it.isNotEmpty() },
            summary = summary?.takeIf { it.isNotEmpty() },
            searchText = searchText,
        )
    }.onFailure { warn(it, "hostPreview parse failed; falling back") }.getOrNull()

    /**
     * Reads a string field, validating both type and byte cap. Returns null
     * on type mismatch or over-cap. Callers should treat null as "this block
     * is invalid, fall back" rather than "absent" — gate the call site with
     * an `obj.has(key)` check for optional fields.
     */
    private fun readRequiredString(
        obj: JSONObject,
        key: String,
        maxBytes: Int,
        allowBlank: Boolean,
    ): String? {
        val raw = obj.get(key)
        if (raw !is String) {
            warn("hostPreview.%s is not a string; falling back", key)
            return null
        }
        if (!allowBlank && raw.isBlank()) {
            warn("hostPreview.%s must be a non-empty string; falling back", key)
            return null
        }
        if (raw.toByteArray(Charsets.UTF_8).size > maxBytes) {
            warn("hostPreview.%s exceeds %d UTF-8 bytes; falling back", key, maxBytes)
            return null
        }
        return raw
    }

    // Wrappers so Timber calls are visible in unit tests without a real tree.
    private fun warn(message: String, vararg args: Any?) {
        Timber.tag(TAG).w(message, *args)
    }
    private fun warn(throwable: Throwable, message: String, vararg args: Any?) {
        Timber.tag(TAG).w(throwable, message, *args)
    }
}
