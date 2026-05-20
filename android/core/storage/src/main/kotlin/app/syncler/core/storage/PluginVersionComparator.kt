package app.syncler.core.storage

/**
 * Semver-lite comparator (MAJOR.MINOR.PATCH[-prerelease]) matching the
 * server's app/services/plugins.py logic so client + server agree on
 * "newer."
 */
object PluginVersionComparator {

    /**
     * Returns true if [installed] is strictly older than [latest].
     * Throws on malformed versions.
     */
    fun isOlder(installed: String, latest: String): Boolean =
        compare(installed, latest) < 0

    fun compare(a: String, b: String): Int {
        val (am, an, ap, apre) = parse(a)
        val (bm, bn, bp, bpre) = parse(b)
        when {
            am != bm -> return am.compareTo(bm)
            an != bn -> return an.compareTo(bn)
            ap != bp -> return ap.compareTo(bp)
        }
        // Prerelease comparison: empty prerelease ("~" sentinel) sorts
        // AFTER any actual prerelease string (1.0.0 > 1.0.0-rc1).
        return apre.compareTo(bpre)
    }

    private data class Parsed(val major: Int, val minor: Int, val patch: Int, val pre: String)

    private operator fun Parsed.component1() = major
    private operator fun Parsed.component2() = minor
    private operator fun Parsed.component3() = patch
    private operator fun Parsed.component4() = pre

    private fun parse(version: String): Parsed {
        val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([A-Za-z0-9.\\-]+))?$").matchEntire(version)
            ?: throw IllegalArgumentException("invalid version: $version")
        val (maj, min, pat) = match.destructured.toList().take(3).map { it.toInt() }
        val pre = match.groups[4]?.value ?: "~"  // "~" > any pre-release in ASCII
        return Parsed(maj, min, pat, pre)
    }
}
