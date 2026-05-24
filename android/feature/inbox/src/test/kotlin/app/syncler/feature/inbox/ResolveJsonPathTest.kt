package app.syncler.feature.inbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveJsonPathTest {

    @Test
    fun `resolves simple top-level string`() {
        val root = JSONObject("""{"title": "Hello"}""")
        assertEquals("Hello", resolveJsonPath(root, "$.title"))
    }

    @Test
    fun `resolves nested string`() {
        val root = JSONObject("""{"data": {"nested": "Value"}}""")
        assertEquals("Value", resolveJsonPath(root, "$.data.nested"))
    }

    @Test
    fun `resolves number as string`() {
        val root = JSONObject("""{"count": 42}""")
        assertEquals("42", resolveJsonPath(root, "$.count"))
    }

    @Test
    fun `resolves boolean as string`() {
        val root = JSONObject("""{"ok": true}""")
        assertEquals("true", resolveJsonPath(root, "$.ok"))
    }

    @Test
    fun `returns null for missing key`() {
        val root = JSONObject("""{"title": "Hello"}""")
        assertNull(resolveJsonPath(root, "$.subtitle"))
    }

    @Test
    fun `returns null for missing nested path`() {
        val root = JSONObject("""{"data": {}}""")
        assertNull(resolveJsonPath(root, "$.data.missing"))
    }

    @Test
    fun `returns null for non-object segment`() {
        val root = JSONObject("""{"data": "NotAnObject"}""")
        assertNull(resolveJsonPath(root, "$.data.field"))
    }

    @Test
    fun `returns null for object leaf`() {
        val root = JSONObject("""{"data": {"field": "val"}}""")
        assertNull(resolveJsonPath(root, "$.data"))
    }

    @Test
    fun `returns null for array leaf`() {
        val root = JSONObject("""{"items": [1, 2, 3]}""")
        assertNull(resolveJsonPath(root, "$.items"))
    }

    @Test
    fun `returns null for malformed path`() {
        val root = JSONObject("""{"title": "Hello"}""")
        assertNull(resolveJsonPath(root, "title"))
        assertNull(resolveJsonPath(root, "$"))
        assertNull(resolveJsonPath(root, "$. "))
    }
}
