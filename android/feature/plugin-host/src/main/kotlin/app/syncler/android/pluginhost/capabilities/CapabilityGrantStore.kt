package app.syncler.android.pluginhost.capabilities

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 12 (V2 #10) — wrapper around [PluginCapabilityGrantDao]
 * with an in-memory cache for the hot path.
 *
 * Bridge handlers call [hasGrant] before launching an
 * intent / service call. The cache means the common case
 * (plugin already granted) is a single map lookup; a miss
 * falls through to the SQLCipher-backed table.
 *
 * Cache invalidation: settings-sheet revoke calls [revoke]
 * which removes both the cache entry and the row.
 *
 * See `docs/plugin-capability-expansion.md` "Grant model".
 */
class CapabilityGrantStore(context: Context) {

    private val db: PluginCapabilityDb by lazy {
        PluginCapabilityDb.open(context.applicationContext)
    }

    /**
     * Cache key = `pluginRowId|capability`. Value = `grantedAtMs`
     * or [CACHE_MISS_SENTINEL] for "we checked, no grant exists".
     *
     * Triad 139 codex #2 fix: ConcurrentHashMap can't store null
     * values, so we use a sentinel for negative caching instead
     * of letting `cache[key] = null` throw NPE at runtime.
     */
    private val cache = ConcurrentHashMap<String, Long>()
    private val writeMutex = Mutex()

    suspend fun hasGrant(pluginRowId: String, capability: String): Boolean {
        val key = cacheKey(pluginRowId, capability)
        val cached = cache[key]
        if (cached != null) {
            return cached != CACHE_MISS_SENTINEL
        }
        // Cache miss — consult DB and populate.
        val row = db.grants().get(pluginRowId, capability)
        cache[key] = row?.grantedAtMs ?: CACHE_MISS_SENTINEL
        return row != null
    }

    /**
     * Records a fresh grant. Idempotent — re-inserting an
     * existing (plugin, capability) bumps `grantedAtMs`.
     * Phase 12's "Category A first-time pick" path calls this
     * AFTER successful staging; "Category B" calls it AFTER
     * OS permission grant.
     */
    suspend fun grant(pluginRowId: String, capability: String, atMs: Long): Unit = writeMutex.withLock {
        db.grants().upsert(
            PluginCapabilityGrantRow(
                pluginRowId = pluginRowId,
                capability = capability,
                grantedAtMs = atMs,
            ),
        )
        cache[cacheKey(pluginRowId, capability)] = atMs
    }

    /**
     * Settings-sheet revoke. Removes the row + cache entry.
     * Subsequent [hasGrant] calls re-fetch from DB and observe
     * the deletion.
     */
    suspend fun revoke(pluginRowId: String, capability: String): Unit = writeMutex.withLock {
        db.grants().delete(pluginRowId, capability)
        cache[cacheKey(pluginRowId, capability)] = CACHE_MISS_SENTINEL
    }

    /**
     * Mid-flight settings revoke check (mentioned in
     * `docs/plugin-capability-expansion.md` "Activity-result
     * plumbing"). Bridge implementations call this after the
     * picker / capture returns but BEFORE staging or returning
     * bytes. Refreshes the cache deliberately — if the user
     * just revoked, the in-memory cache might still be stale.
     */
    suspend fun reverifyGrant(pluginRowId: String, capability: String): Boolean {
        val row = db.grants().get(pluginRowId, capability)
        cache[cacheKey(pluginRowId, capability)] = row?.grantedAtMs ?: CACHE_MISS_SENTINEL
        return row != null
    }

    /**
     * Settings UI: list every (capability, grantedAt,
     * lastInvoked) for a given plugin row.
     */
    suspend fun forPlugin(pluginRowId: String): List<PluginCapabilityGrantRow> =
        db.grants().forPlugin(pluginRowId)

    /** Mark the grant as recently invoked so settings can show "last used". */
    suspend fun touch(pluginRowId: String, capability: String, atMs: Long) {
        db.grants().markInvoked(pluginRowId, capability, atMs)
    }

    /** Plugin uninstall: drop every capability row for that plugin. */
    suspend fun forget(pluginRowId: String): Unit = writeMutex.withLock {
        db.grants().deleteAllForPlugin(pluginRowId)
        val prefix = "$pluginRowId|"
        cache.keys.removeAll { it.startsWith(prefix) }
    }

    private fun cacheKey(pluginRowId: String, capability: String): String =
        "$pluginRowId|$capability"

    companion object {
        /**
         * Sentinel for "we checked the DB, no grant exists" — kept
         * in the value map because ConcurrentHashMap can't store
         * null. Negative-MAX so it can never collide with a real
         * grantedAtMs (which is a positive wall-clock time).
         */
        private const val CACHE_MISS_SENTINEL: Long = Long.MIN_VALUE
    }

    /** Test helper — for tests that want to bypass the SecurePrefs path. */
    internal fun auditDaoForTest(): PluginCapabilityAuditDao = db.audit()

    /** Test helper — for tests that want to inspect the grants DAO. */
    internal fun grantDaoForTest(): PluginCapabilityGrantDao = db.grants()
}

/** Convenience constant for grant-row timestamps. */
internal fun nowWallClockMs(): Long = System.currentTimeMillis()

/** Convenience constant for monotonic enforcement (TTLs, durations). */
internal fun nowElapsedMs(): Long = SystemClock.elapsedRealtime()
