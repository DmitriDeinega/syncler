package app.syncler.core.storage

import android.util.Base64
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * V4 #20: Keystore-protected persistence for the 32-byte master key.
 *
 * The master key is derived client-side from the user's password +
 * auth_salt at signup, then used to wrap user-state, paired-sender,
 * and per-card secrets. Pre-V4 #20 it lived in RAM only; the user had
 * to re-enter their password on every cold start.
 *
 * Persistence model — triad 166 agreement:
 *
 * - Stored in [SecurePrefs] (EncryptedSharedPreferences wrapped under
 *   the Android Keystore-resident AES master key). On hardware-backed
 *   devices the wrap key never leaves the secure element.
 * - Written exactly once per session by [AuthRepository.login] right
 *   after a successful login decrypts ``encrypted_master_key`` with
 *   the password-derived KEK.
 * - Read at app start by [Session]; if present, [Session.isUnlocked]
 *   becomes true without prompting for a password.
 * - [clear] wipes the row. Called by ``Session.logout()`` (which is
 *   the "Sign out" action) and from a future "Stay signed in: OFF"
 *   toggle in Settings.
 * - Critically — atomic with the [Session] wipe so the persisted key
 *   never outlives the in-memory session. Codex 166 flagged the
 *   "MasterKeyStore lands before sign-out wipe" sequence as the
 *   riskiest partial state; this commit ships both together.
 *
 * What this does NOT protect against:
 * - A phone the attacker holds while the OS lock screen is unlocked.
 *   The Keystore wrap key is available to the app, so anything the
 *   app itself can do, an attacker as the unlocked user can do too.
 *   Per-action gating ([SensitiveActionGate]) is what raises the bar
 *   on individual sensitive operations.
 * - OS-level malware that escalates to the app's UID. Standard
 *   Android trust model applies; not in scope for v0.x.
 */
interface MasterKeyStore {
    /** Returns the persisted master key bytes, or null if none. */
    fun read(): ByteArray?

    /** Persist the master key. Overwrites any prior value. */
    fun write(masterKey: ByteArray)

    /**
     * Wipe the persisted master key. Idempotent; safe to call when
     * nothing was stored. MUST be called as part of every full-logout
     * flow so the persisted key never outlives the in-memory session.
     */
    fun clear()
}

/**
 * In-memory MasterKeyStore — production-safe NoOp DEFAULT used as the
 * Session constructor's parameter default so unit tests that build
 * Session without DI don't need to thread a fake through. Hilt's
 * @Inject path overrides this with [SecurePrefsMasterKeyStore] in the
 * real app. Tests that exercise persistence semantics specifically
 * (e.g. SessionTest cold-start path) can construct an
 * [InMemoryMasterKeyStore] directly.
 */
object NoOpMasterKeyStore : MasterKeyStore {
    override fun read(): ByteArray? = null
    override fun write(masterKey: ByteArray) = Unit
    override fun clear() = Unit
}

/**
 * In-memory MasterKeyStore for unit tests that need to assert
 * persistence semantics (write -> read round-trip, clear wipes).
 * Not used in production code.
 */
class InMemoryMasterKeyStore : MasterKeyStore {
    @Volatile private var stored: ByteArray? = null
    override fun read(): ByteArray? = stored?.copyOf()
    override fun write(masterKey: ByteArray) { stored = masterKey.copyOf() }
    override fun clear() { stored = null }
}

@Singleton
class SecurePrefsMasterKeyStore @Inject constructor(
    private val securePrefs: SecurePrefs,
) : MasterKeyStore {

    override fun read(): ByteArray? {
        val encoded = securePrefs.getString(KEY) ?: return null
        return runCatching { Base64.decode(encoded, Base64.NO_WRAP) }
            .getOrNull()
            ?.takeIf { it.size == MASTER_KEY_BYTES }
    }

    override fun write(masterKey: ByteArray) {
        require(masterKey.size == MASTER_KEY_BYTES) {
            "master key must be exactly $MASTER_KEY_BYTES bytes (got ${masterKey.size})"
        }
        securePrefs.putString(KEY, Base64.encodeToString(masterKey, Base64.NO_WRAP))
    }

    override fun clear() {
        securePrefs.remove(KEY)
    }

    private companion object {
        const val KEY = "syncler.v4.master_key_persisted"
        const val MASTER_KEY_BYTES = 32
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MasterKeyStoreModule {
    @Binds
    @Singleton
    abstract fun bindMasterKeyStore(impl: SecurePrefsMasterKeyStore): MasterKeyStore
}
