package app.syncler.feature.pluginnativesandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForbiddenPackagePrefixesTest {

    @Test
    fun `accepts ordinary plugin class names`() {
        assertNull(
            firstForbiddenPrefix(
                listOf(
                    "com.example.weather.WeatherPlugin",
                    "io.acme.tools.helper.UtilsKt",
                    "MyPlugin",
                    "com.example.weather.WeatherPlugin\$Companion",
                ),
            ),
        )
    }

    @Test
    fun `rejects app_syncler shadow attempts`() {
        // A plugin must NOT be able to declare a class in
        // app.syncler.* — that would let it shadow the SDK
        // runtime contract and have the sandbox load its
        // adversarial version of PluginContext.
        assertEquals(
            "app.syncler.",
            firstForbiddenPrefix(
                listOf("app.syncler.plugin.runtime.PluginContext"),
            ),
        )
    }

    @Test
    fun `rejects android framework shadow attempts`() {
        assertEquals(
            "android.",
            firstForbiddenPrefix(listOf("android.os.SystemPropertiesShadow")),
        )
        assertEquals(
            "androidx.",
            firstForbiddenPrefix(listOf("androidx.core.ContextShadow")),
        )
    }

    @Test
    fun `rejects kotlin stdlib shadow attempts`() {
        assertEquals(
            "kotlin.",
            firstForbiddenPrefix(listOf("kotlin.collections.MutableMapKt")),
        )
        assertEquals(
            "kotlinx.",
            firstForbiddenPrefix(listOf("kotlinx.coroutines.JobShadow")),
        )
    }

    @Test
    fun `rejects java javax shadow attempts`() {
        assertEquals(
            "java.",
            firstForbiddenPrefix(listOf("java.lang.StringShadow")),
        )
        assertEquals(
            "javax.",
            firstForbiddenPrefix(listOf("javax.crypto.CipherShadow")),
        )
    }

    @Test
    fun `returns first matching prefix wins on mixed input`() {
        // The scanner short-circuits on the first hit; order in
        // the input list is what matters, not the prefix list.
        assertEquals(
            "app.syncler.",
            firstForbiddenPrefix(
                listOf(
                    "com.example.Foo",
                    "app.syncler.plugin.runtime.PluginContext",
                    "java.lang.StringShadow",
                ),
            ),
        )
    }

    @Test
    fun `prefix match is strict prefix, not substring`() {
        // A class named "app.synclerish.Foo" must NOT match
        // "app.syncler." because that would forbid legitimate
        // names that happen to share a stem.
        assertNull(firstForbiddenPrefix(listOf("app.synclerish.Foo")))
        // But the dot anchors do their job:
        assertEquals(
            "app.syncler.",
            firstForbiddenPrefix(listOf("app.syncler.plugin.AnyClass")),
        )
    }

    @Test
    fun `forbidden list snapshot matches spec`() {
        // docs/plugin-host-native-kotlin.md "Bundle format" pins
        // these seven prefixes. The test guards drift against
        // the spec.
        assertEquals(
            listOf(
                "app.syncler.",
                "android.",
                "androidx.",
                "kotlin.",
                "kotlinx.",
                "java.",
                "javax.",
            ),
            FORBIDDEN_DEX_PREFIXES,
        )
    }
}
