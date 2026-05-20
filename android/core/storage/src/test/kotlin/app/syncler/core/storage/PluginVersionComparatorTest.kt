package app.syncler.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginVersionComparatorTest {

    @Test
    fun `simple greater-than ordering`() {
        assertTrue(PluginVersionComparator.isOlder("1.0.0", "1.0.1"))
        assertTrue(PluginVersionComparator.isOlder("1.0.0", "1.1.0"))
        assertTrue(PluginVersionComparator.isOlder("1.0.0", "2.0.0"))
        assertFalse(PluginVersionComparator.isOlder("1.0.1", "1.0.0"))
        assertFalse(PluginVersionComparator.isOlder("1.0.0", "1.0.0"))
    }

    @Test
    fun `prerelease sorts before release`() {
        // 1.0.0-rc1 < 1.0.0
        assertTrue(PluginVersionComparator.isOlder("1.0.0-rc1", "1.0.0"))
        assertFalse(PluginVersionComparator.isOlder("1.0.0", "1.0.0-rc1"))
    }

    @Test
    fun `compare returns sign appropriately`() {
        assertTrue(PluginVersionComparator.compare("1.0.0", "1.0.1") < 0)
        assertTrue(PluginVersionComparator.compare("2.0.0", "1.9.9") > 0)
        assertEquals(0, PluginVersionComparator.compare("1.2.3", "1.2.3"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed version throws`() {
        PluginVersionComparator.compare("1.0", "1.0.0")
    }
}
