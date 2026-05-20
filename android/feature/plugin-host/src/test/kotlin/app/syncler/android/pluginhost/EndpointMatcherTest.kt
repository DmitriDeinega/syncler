package app.syncler.android.pluginhost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointMatcherTest {
    @Test
    fun exactMatch() {
        assertTrue(EndpointMatcher.matches("https://api.example.com/v1/item", listOf("https://api.example.com/v1/item")))
    }

    @Test
    fun wildcardPathMatchesOneSegment() {
        val pattern = "https://api.example.com/v1/*"

        assertTrue(EndpointMatcher.matches("https://api.example.com/v1/item", listOf(pattern)))
        assertFalse(EndpointMatcher.matches("https://api.example.com/v1/item/child", listOf(pattern)))
    }

    @Test
    fun wildcardSubdomainMatchesOneHostSegment() {
        val pattern = "https://*.example.net/feed"

        assertTrue(EndpointMatcher.matches("https://news.example.net/feed", listOf(pattern)))
        assertFalse(EndpointMatcher.matches("https://a.b.example.net/feed", listOf(pattern)))
    }

    @Test
    fun mismatch() {
        assertFalse(EndpointMatcher.matches("https://evil.example.com/v1/item", listOf("https://api.example.com/v1/*")))
    }
}
