package app.syncler.feature.inbox

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.syncler.core.storage.UserStateMutator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * Minimal persistence interface for [UserStateRepository].
 *
 * Extracted so the repository's CAS retry semantics + dirty-flag bookkeeping
 * can be unit-tested without instantiating real Android EncryptedSharedPreferences
 * (which would require Robolectric or an instrumented test).
 */
interface UserStatePrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun clear()
}

/**
 * Production implementation backed by Jetpack's EncryptedSharedPreferences.
 * Same storage shape as before the interface was introduced — keys and file
 * name preserved so an in-place upgrade doesn't lose existing data.
 */
internal class EncryptedSharedPrefsBackend(
    private val prefs: SharedPreferences,
) : UserStatePrefs {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
    override fun clear() {
        prefs.edit().clear().apply()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object UserStatePrefsModule {
    /**
     * System UTC clock binding. UserStateRepository takes a Clock via the
     * constructor (so unit tests can pass a fixed clock); Dagger needs an
     * explicit @Provides because it doesn't honor Kotlin default values
     * (Codex consultation 51).
     */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun provideUserStatePrefs(@ApplicationContext context: Context): UserStatePrefs {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            "syncler.user_state.enc",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return EncryptedSharedPrefsBackend(prefs)
    }
}

/**
 * Cross-module mutator binding. `:core:storage` declares the interface;
 * `:feature:inbox` owns the implementation. This binding lets DI consumers
 * in either module inject `UserStateMutator` and reach the live repository.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UserStateMutatorModule {
    @Binds
    abstract fun bindMutator(repository: UserStateRepository): UserStateMutator
}
