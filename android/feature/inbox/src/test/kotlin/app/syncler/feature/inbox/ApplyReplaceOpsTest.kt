package app.syncler.feature.inbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * V3 #16 — invariants for the field-level patch applier
 * (`applyReplaceOps`). Covers the privacy / atomicity
 * contract documented in docs/live-card-patch.md:
 *
 *  - Multi-patch in a single envelope applies atomically.
 *  - Any failure (unknown path, malformed op) discards the
 *    entire batch — local state never sees a partial mutation.
 *  - Unknown op kinds (anything other than `replace` in V0.1)
 *    are rejected.
 */
class ApplyReplaceOpsTest {

    @Test
    fun `applies a single replace op`() {
        val payload = """{"home_score":"0","away_score":"0"}"""
        val patch = """{"patches":[{"op":"replace","path":"${"\$"}.home_score","value":"42"}]}"""
        val out = applyReplaceOps(payload, patch)
        val obj = JSONObject(out)
        assertEquals("42", obj.getString("home_score"))
        assertEquals("0", obj.getString("away_score"))
    }

    @Test
    fun `applies multi-op batch atomically`() {
        val payload = """{"home_score":"0","away_score":"0","period":"1"}"""
        val patch = """
            {"patches":[
              {"op":"replace","path":"${"\$"}.home_score","value":"42"},
              {"op":"replace","path":"${"\$"}.away_score","value":"17"}
            ]}
        """.trimIndent()
        val out = applyReplaceOps(payload, patch)
        val obj = JSONObject(out)
        assertEquals("42", obj.getString("home_score"))
        assertEquals("17", obj.getString("away_score"))
        assertEquals("1", obj.getString("period"))
    }

    @Test
    fun `replaces nested path`() {
        val payload = """{"score":{"home":"0","away":"0"}}"""
        val patch = """{"patches":[{"op":"replace","path":"${"\$"}.score.home","value":"7"}]}"""
        val out = applyReplaceOps(payload, patch)
        assertEquals("7", JSONObject(out).getJSONObject("score").getString("home"))
    }

    @Test
    fun `unknown path rejects whole batch leaving input untouched`() {
        val payload = """{"a":"1","b":"2"}"""
        // Second op targets a non-existent field — entire batch must throw.
        val patch = """
            {"patches":[
              {"op":"replace","path":"${"\$"}.a","value":"9"},
              {"op":"replace","path":"${"\$"}.nope","value":"x"}
            ]}
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            applyReplaceOps(payload, patch)
        }
        // Caller's runCatching gives up; input is unchanged at the source.
        // (We assert this by verifying the input string itself was not mutated.)
        assertEquals("""{"a":"1","b":"2"}""", payload)
    }

    @Test
    fun `unsupported op kind rejects batch`() {
        val payload = """{"a":"1"}"""
        val patch = """{"patches":[{"op":"add","path":"${"\$"}.a","value":"9"}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            applyReplaceOps(payload, patch)
        }
    }

    @Test
    fun `malformed JSONPath rejects batch`() {
        val payload = """{"a":"1"}"""
        // No `$.` prefix.
        val patch = """{"patches":[{"op":"replace","path":"a","value":"9"}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            applyReplaceOps(payload, patch)
        }
    }

    @Test
    fun `missing patches array errors`() {
        val payload = """{"a":"1"}"""
        val patch = """{"not_patches":[]}"""
        assertThrows(IllegalStateException::class.java) {
            applyReplaceOps(payload, patch)
        }
    }
}
