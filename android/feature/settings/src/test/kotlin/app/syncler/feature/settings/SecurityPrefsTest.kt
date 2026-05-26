package app.syncler.feature.settings

import android.content.SharedPreferences
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * V4 #18 — SecurityPrefs banner timeout invariant.
 * codex 149 #3 FIX: measured in DAYS, not launches.
 */
class SecurityPrefsTest {

    @Test
    fun `unset marker reads null`() {
        val (prefs, _) = build(Clock.fixed(Instant.parse("2026-05-26T10:00:00Z"), ZoneId.of("UTC")))
        assertNull(prefs.revokedWithoutRotationActiveSinceMs())
    }

    @Test
    fun `marker set just now reads back`() {
        val now = Instant.parse("2026-05-26T10:00:00Z")
        val (prefs, _) = build(Clock.fixed(now, ZoneId.of("UTC")))
        prefs.setRevokedWithoutRotationAtNow()
        assertEquals(now.toEpochMilli(), prefs.revokedWithoutRotationActiveSinceMs())
    }

    @Test
    fun `marker within 30 day window still active`() {
        val setAt = Instant.parse("2026-05-01T10:00:00Z")
        val now = setAt.plusSeconds(SecurityPrefs.MS_PER_DAY / 1000 * 29)
        val backing = InMemoryPrefs()
        val prefsSet = SecurityPrefs(backing, Clock.fixed(setAt, ZoneId.of("UTC")))
        prefsSet.setRevokedWithoutRotationAtNow()

        val prefsRead = SecurityPrefs(backing, Clock.fixed(now, ZoneId.of("UTC")))
        assertNotNull(prefsRead.revokedWithoutRotationActiveSinceMs())
    }

    @Test
    fun `marker past 30 day TTL auto-clears on read`() {
        val setAt = Instant.parse("2026-05-01T10:00:00Z")
        val now = setAt.plusSeconds(SecurityPrefs.MS_PER_DAY / 1000 * 31)
        val backing = InMemoryPrefs()
        val prefsSet = SecurityPrefs(backing, Clock.fixed(setAt, ZoneId.of("UTC")))
        prefsSet.setRevokedWithoutRotationAtNow()

        val prefsRead = SecurityPrefs(backing, Clock.fixed(now, ZoneId.of("UTC")))
        assertNull(prefsRead.revokedWithoutRotationActiveSinceMs())
        // Auto-cleared, so a fresh read also returns null without
        // re-stamping.
        assertNull(prefsRead.revokedWithoutRotationActiveSinceMs())
    }

    @Test
    fun `clear removes the marker`() {
        val now = Instant.parse("2026-05-26T10:00:00Z")
        val (prefs, _) = build(Clock.fixed(now, ZoneId.of("UTC")))
        prefs.setRevokedWithoutRotationAtNow()
        prefs.clearRevokedWithoutRotationAt()
        assertNull(prefs.revokedWithoutRotationActiveSinceMs())
    }

    private fun build(clock: Clock): Pair<SecurityPrefs, InMemoryPrefs> {
        val backing = InMemoryPrefs()
        return SecurityPrefs(backing, clock) to backing
    }
}


/**
 * Minimal in-memory SharedPreferences used so the SecurityPrefs
 * test can run without Robolectric / instrumented setup. Only
 * implements the methods SecurityPrefs uses.
 */
private class InMemoryPrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()
    override fun getAll(): Map<String, *> = map.toMap()
    override fun getString(key: String?, defValue: String?): String? =
        (map[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        (map[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int =
        (map[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long =
        (map[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float =
        (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (map[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {}
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap(map)
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value; return this
        }
        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            if (key != null) pending[key] = values; return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) pending[key] = value; return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) pending[key] = value; return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) pending[key] = value; return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) pending[key] = value; return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) pending.remove(key); return this
        }
        override fun clear(): SharedPreferences.Editor {
            pending.clear(); return this
        }
        override fun commit(): Boolean {
            map.clear(); map.putAll(pending); return true
        }
        override fun apply() {
            commit()
        }
    }
}
