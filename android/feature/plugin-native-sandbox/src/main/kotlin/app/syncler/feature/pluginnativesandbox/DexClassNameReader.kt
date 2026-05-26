package app.syncler.feature.pluginnativesandbox

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal DEX (Dalvik Executable) parser — extracts the list of
 * class binary names declared in a single classes.dex blob.
 *
 * Phase 11 uses this for the sandbox-side defense-in-depth scan of
 * forbidden package prefixes (host scans at staging, sandbox
 * repeats inside its isolated process so a compromised host can't
 * skip the check).
 *
 * Format reference: https://source.android.com/devices/tech/dalvik/dex-format
 *
 * Scope: parses just enough of the header + string_ids +
 * type_ids + class_defs sections to recover the class type
 * strings. Does NOT validate the rest of the DEX — that's
 * `InMemoryDexClassLoader`'s job at load time.
 */
internal object DexClassNameReader {

    /** Magic bytes "dex\n" followed by a 3-digit version + null. */
    private const val DEX_MAGIC_PREFIX = "dex\n"

    /**
     * Parse [dex] and return the binary class names declared in
     * `class_defs`. Throws [IllegalArgumentException] if the blob
     * isn't a recognizable DEX file.
     */
    @Throws(IllegalArgumentException::class)
    fun classNames(dex: ByteArray): List<String> {
        require(dex.size >= HEADER_SIZE) { "dex too short: ${dex.size}" }
        val magic = String(dex, 0, 4, Charsets.US_ASCII)
        require(magic == DEX_MAGIC_PREFIX) { "not a DEX file: magic=$magic" }

        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)

        // header.string_ids_size @ 0x38, header.string_ids_off @ 0x3C
        val stringIdsSize = buf.getInt(0x38)
        val stringIdsOff = buf.getInt(0x3C)
        // header.type_ids_size @ 0x40, type_ids_off @ 0x44
        val typeIdsSize = buf.getInt(0x40)
        val typeIdsOff = buf.getInt(0x44)
        // header.class_defs_size @ 0x60, class_defs_off @ 0x64
        val classDefsSize = buf.getInt(0x60)
        val classDefsOff = buf.getInt(0x64)

        require(stringIdsSize >= 0 && typeIdsSize >= 0 && classDefsSize >= 0) {
            "negative section sizes — DEX corrupted"
        }

        // 1. Read string_ids table → string data offsets.
        val stringOffsets = IntArray(stringIdsSize)
        var cursor = stringIdsOff
        for (i in 0 until stringIdsSize) {
            stringOffsets[i] = buf.getInt(cursor)
            cursor += 4
        }

        // 2. Read type_ids table → string_id indexes.
        val typeStringIdx = IntArray(typeIdsSize)
        cursor = typeIdsOff
        for (i in 0 until typeIdsSize) {
            typeStringIdx[i] = buf.getInt(cursor)
            cursor += 4
        }

        // 3. Iterate class_defs; each is 32 bytes; class_idx @ +0
        //    refers into type_ids → string_ids → string data.
        val names = ArrayList<String>(classDefsSize)
        cursor = classDefsOff
        for (i in 0 until classDefsSize) {
            val classIdx = buf.getInt(cursor)
            require(classIdx in 0 until typeIdsSize) {
                "class_def[$i].class_idx=$classIdx out of range"
            }
            val stringIdx = typeStringIdx[classIdx]
            require(stringIdx in 0 until stringIdsSize) {
                "type_ids[$classIdx]=$stringIdx out of range"
            }
            val descriptor = readMUtf8String(dex, stringOffsets[stringIdx])
            names.add(descriptorToBinaryName(descriptor))
            cursor += CLASS_DEF_SIZE
        }
        return names
    }

    /**
     * DEX strings: ULEB128 length prefix then MUTF-8 bytes
     * (modified UTF-8). For ASCII the bytes match UTF-8 exactly,
     * which is what every published Kotlin class name will be.
     */
    private fun readMUtf8String(dex: ByteArray, offset: Int): String {
        // Skip ULEB128 length — we just read until null terminator.
        var p = offset
        while (p < dex.size) {
            val b = dex[p].toInt() and 0xFF
            if (b and 0x80 == 0) {
                p++
                break
            }
            p++
        }
        // Read up to null terminator.
        var end = p
        while (end < dex.size && dex[end] != 0.toByte()) end++
        return String(dex, p, end - p, Charsets.UTF_8)
    }

    /**
     * Class descriptor "Lcom/example/Foo;" → "com.example.Foo".
     * Anything else (arrays, primitives) is returned as-is so the
     * forbidden-prefix scan can simply skip it (it won't match
     * `app.syncler.` etc.).
     */
    private fun descriptorToBinaryName(descriptor: String): String {
        if (descriptor.length < 3 || descriptor[0] != 'L' ||
            descriptor[descriptor.length - 1] != ';'
        ) {
            return descriptor
        }
        return descriptor.substring(1, descriptor.length - 1).replace('/', '.')
    }

    /** DEX v035 header size is 0x70 bytes. */
    private const val HEADER_SIZE = 0x70

    /** Each `class_def_item` is exactly 32 bytes (8 × u32). */
    private const val CLASS_DEF_SIZE = 32
}
