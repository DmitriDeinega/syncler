package app.syncler.feature.pluginnativesandbox

/**
 * Phase 11 forbidden DEX class-name prefixes. A plugin MUST NOT
 * declare any class whose binary name starts with one of these —
 * otherwise it could shadow the SDK runtime contract or override
 * stdlib types in a way the sandbox can't reason about.
 *
 * The host's PluginRegistry scans the DEX header at staging time
 * and rejects with `forbidden_package_prefix`; the sandbox repeats
 * the scan as defense-in-depth right before instantiating the
 * ClassLoader. (Spec: docs/plugin-host-native-kotlin.md "Bundle
 * format" / "Per-plugin lifecycle".)
 */
val FORBIDDEN_DEX_PREFIXES: List<String> = listOf(
    "app.syncler.",
    "android.",
    "androidx.",
    "kotlin.",
    "kotlinx.",
    "java.",
    "javax.",
)

/**
 * Returns the offending prefix if any class name in [classNames]
 * starts with one of [FORBIDDEN_DEX_PREFIXES]; null otherwise.
 *
 * Class names should be binary names with dots (`com.example.Foo`),
 * not the JVM internal slash form (`com/example/Foo`).
 */
fun firstForbiddenPrefix(classNames: Iterable<String>): String? {
    for (name in classNames) {
        for (prefix in FORBIDDEN_DEX_PREFIXES) {
            if (name.startsWith(prefix)) return prefix
        }
    }
    return null
}
