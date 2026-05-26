package app.syncler.feature.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * V4 #18 — small encrypted prefs file for the lost-device
 * flow's "skipped rotation" marker. The home-screen banner
 * reads this to decide whether to nag the user about an
 * outstanding rotation step.
 *
 * Spec: docs/lost-device-flow.md. Timeout is days-based
 * (codex 149 #3 FIX — measure in days, not launches).
 */
@Singleton
class SecurityPrefs @Inject constructor(
    private val backing: SharedPreferences,
    private val clock: Clock,
) {

    /** Stamp "rotation skipped, was offered after revoke" at now. */
    fun setRevokedWithoutRotationAtNow() {
        backing.edit()
            .putLong(KEY_REVOKED_WITHOUT_ROTATION_AT_MS, clock.millis())
            .apply()
    }

    fun clearRevokedWithoutRotationAt() {
        backing.edit().remove(KEY_REVOKED_WITHOUT_ROTATION_AT_MS).apply()
    }

    /**
     * Returns the recorded timestamp ms iff the user
     * skipped rotation AND we're within the [BANNER_TTL_DAYS]
     * window. Older marks are auto-cleared on read so the
     * banner self-heals after the timeout.
     */
    fun revokedWithoutRotationActiveSinceMs(): Long? {
        val raw = backing.getLong(KEY_REVOKED_WITHOUT_ROTATION_AT_MS, -1L)
        if (raw < 0L) return null
        val ageMs = clock.millis() - raw
        // Triad 150 codex NIT — ">=" matches the "once 30 days
        // elapse" copy in the spec; ">" would leave the marker
        // active at exactly 30 days.
        if (ageMs >= BANNER_TTL_DAYS * MS_PER_DAY) {
            clearRevokedWithoutRotationAt()
            return null
        }
        return raw
    }

    companion object {
        private const val KEY_REVOKED_WITHOUT_ROTATION_AT_MS =
            "revoked_without_rotation_at_ms"
        const val BANNER_TTL_DAYS = 30L
        const val MS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}


@Module
@InstallIn(SingletonComponent::class)
object SecurityPrefsModule {

    @Provides
    @Singleton
    fun provideSecurityPrefs(
        @ApplicationContext context: Context,
        clock: Clock,
    ): SecurityPrefs {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            "syncler.security_prefs.enc",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return SecurityPrefs(prefs, clock)
    }
}
