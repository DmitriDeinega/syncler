package app.syncler.android.pluginhost

object EndpointMatcher {
    fun matches(url: String, patterns: List<String>): Boolean =
        patterns.any { pattern -> endpointPatternToRegex(pattern).matches(url) }

    private fun endpointPatternToRegex(pattern: String): Regex {
        val pathStart = findPathStart(pattern)
        val source = buildString {
            pattern.forEachIndexed { index, character ->
                if (character == '*') {
                    append(if (index < pathStart) "[^./]*" else "[^/]*")
                } else {
                    append(Regex.escape(character.toString()))
                }
            }
        }
        return Regex("^$source$")
    }

    private fun findPathStart(pattern: String): Int {
        val schemeEnd = pattern.indexOf("://")
        val searchFrom = if (schemeEnd == -1) 0 else schemeEnd + 3
        val slash = pattern.indexOf('/', searchFrom)
        return if (slash == -1) pattern.length else slash
    }
}
