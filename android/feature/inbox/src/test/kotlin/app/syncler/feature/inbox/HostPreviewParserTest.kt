package app.syncler.feature.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the Android parser to the same shape rules as sdk-plugin/preview.ts
 * and sdk-python/preview.py. Verifies the Codex-flagged rejection cases that
 * `optString` / `optJSONArray` would have silently accepted in the original
 * implementation.
 *
 * Tests build raw JSON strings (rather than constructing `JSONObject`) so the
 * parser exercises the real Android JSON implementation on the host side.
 * Android unit tests stub `JSONObject.put(...)` by default; we avoid the stub
 * by handing the parser a string.
 */
class HostPreviewParserTest {

    /** Wraps a hostPreview JSON snippet (without the outer braces) into a full payload. */
    private fun preview(hostPreviewBody: String): String =
        """{"hostPreview":{$hostPreviewBody},"body":"detail content"}"""

    @Test
    fun `valid block parses`() {
        val parsed = HostPreviewParser.parse(
            preview(
                """"title":"Lottery ticket entered",""" +
                """"subtitle":"Mega Millions","summary":"5 lines for the May 22 drawing.",""" +
                """"searchText":["mega millions","ticket 8KQ-193"]""",
            ),
        )
        assertNotNull(parsed)
        assertEquals("Lottery ticket entered", parsed!!.title)
        assertEquals("Mega Millions", parsed.subtitle)
        assertEquals(listOf("mega millions", "ticket 8KQ-193"), parsed.searchText)
    }

    @Test
    fun `missing block returns null`() {
        assertNull(HostPreviewParser.parse("""{"body":"x"}"""))
    }

    @Test
    fun `block is not a json object - returns null`() {
        assertNull(HostPreviewParser.parse("""{"hostPreview":"not-an-object","body":"x"}"""))
    }

    @Test
    fun `missing title - returns null`() {
        assertNull(HostPreviewParser.parse(preview(""""subtitle":"no title"""")))
    }

    @Test
    fun `non-string title - returns null`() {
        // Codex's flagged case: optString would coerce 42 to "42".
        assertNull(HostPreviewParser.parse(preview(""""title":42""")))
    }

    @Test
    fun `blank title - returns null`() {
        assertNull(HostPreviewParser.parse(preview(""""title":"   """")))
    }

    @Test
    fun `title over 80 utf8 bytes - returns null`() {
        val ascii = "a".repeat(81)
        assertNull(HostPreviewParser.parse(preview(""""title":"$ascii"""")))
    }

    @Test
    fun `title with multi-byte glyphs respects UTF-8 byte cap`() {
        // 21 × 4-byte emoji = 84 bytes > 80
        val emoji = "\\uD83C\\uDFB0".repeat(21)
        assertNull(HostPreviewParser.parse(preview(""""title":"$emoji"""")))
    }

    @Test
    fun `non-string subtitle - returns null`() {
        assertNull(HostPreviewParser.parse(preview(""""title":"ok","subtitle":12345""")))
    }

    @Test
    fun `searchText as string - returns null`() {
        // The case Codex specifically flagged: SDK validators reject, original
        // Android (optJSONArray) silently accepted with empty searchText.
        assertNull(HostPreviewParser.parse(preview(""""title":"ok","searchText":"a, b, c"""")))
    }

    @Test
    fun `searchText with non-string entry - returns null`() {
        assertNull(HostPreviewParser.parse(preview(""""title":"ok","searchText":["good",42,"x"]""")))
    }

    @Test
    fun `searchText over 16 entries - returns null`() {
        val tokens = (1..17).joinToString(",") { "\"t$it\"" }
        assertNull(HostPreviewParser.parse(preview(""""title":"ok","searchText":[$tokens]""")))
    }

    @Test
    fun `searchText entry over 64 bytes - returns null`() {
        val tooBig = "a".repeat(65)
        assertNull(HostPreviewParser.parse(preview(""""title":"ok","searchText":["$tooBig"]""")))
    }

    @Test
    fun `empty searchText tokens are silently dropped`() {
        val parsed = HostPreviewParser.parse(
            preview(""""title":"ok","searchText":["real","","also-real"]"""),
        )
        assertNotNull(parsed)
        assertEquals(listOf("real", "also-real"), parsed!!.searchText)
    }

    @Test
    fun `total size cap rejects unknown-field bloat`() {
        val extra = "x".repeat(2100)
        assertNull(HostPreviewParser.parse(preview(""""title":"ok","extra":"$extra"""")))
    }

    @Test
    fun `omitted optional fields render as nulls`() {
        val parsed = HostPreviewParser.parse(preview(""""title":"only required""""))
        assertNotNull(parsed)
        assertNull(parsed!!.subtitle)
        assertNull(parsed.summary)
        assertEquals(emptyList<String>(), parsed.searchText)
    }
}
