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
    /**
     * Returns the persisted (master-key, key-generation) pair, or null
     * if nothing is stored. Key generation is part of the stored
     * record because it MUST match the in-memory key it was unwrapped
     * with — restoring a key under the wrong generation would let
     * post-rotation cold starts silently operate against a stale
     * server-side state. Triad 167 codex must-fix.
     */
    fun read(): Stored?

    /**
     * Persist (master key, key generation). Overwrites any prior
     * value. Triad 167 codex must-fix: rotation must persist both
     * the new key AND its generation atomically.
     */
    fun write(masterKey: ByteArray, keyGeneration: Int)

    /**
     * Wipe the persisted master key. Idempotent; safe to call when
     * nothing was stored. MUST be called as part of every full-logout
     * flow so the persisted key never outlives the in-memory session.
     */
    fun clear()

    data class Stored(val masterKey: ByteArray, val keyGeneration: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Stored) return false
            return masterKey.contentEquals(other.masterKey) &&
                keyGeneration == other.keyGeneration
        }
        override fun hashCode(): Int =
            31 * masterKey.contentHashCode() + keyGeneration
    }
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
    override fun read(): MasterKeyStore.Stored? = null
    override fun write(masterKey: ByteArray, keyGeneration: Int) = Unit
    override fun clear() = Unit
}

/**
 * In-memory MasterKeyStore for unit tests that need to assert
 * persistence semantics (write -> read round-trip, clear wipes).
 * Not used in production code.
 */
class InMemoryMasterKeyStore : MasterKeyStore {
    @Volatile private var stored: MasterKeyStore.Stored? = null
    override fun read(): MasterKeyStore.Stored? = stored?.let {
        MasterKeyStore.Stored(it.masterKey.copyOf(), it.keyGeneration)
    }
    override fun write(masterKey: ByteArray, keyGeneration: Int) {
        stored = MasterKeyStore.Stored(masterKey.copyOf(), keyGeneration)
    }
    override fun clear() { stored = null }
}

@Singleton
class SecurePrefsMasterKeyStore @Inject constructor(
    private val securePrefs: SecurePrefs,
) : MasterKeyStore {

    override fun read(): MasterKeyStore.Stored? {
        val encoded = securePrefs.getString(KEY) ?: return null
        val decoded = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }
            .getOrNull()
            ?.takeIf { it.size == MASTER_KEY_BYTES }
            ?: return null
        // Triad 167 codex must-fix: persist + restore key_generation
        // alongside the master key. Reading just the key was the bug
        // — after a rotation we'd cold-start under generation 1
        // while the server expected the rotated value, silently
        // operating on a stale snapshot.
        val storedGen = securePrefs.getInt(KEY_GEN, FALLBACK_KEY_GENERATION)
        return MasterKeyStore.Stored(decoded, storedGen)
    }

    override fun write(masterKey: ByteArray, keyGeneration: Int) {
        require(masterKey.size == MASTER_KEY_BYTES) {
            "master key must be exactly $MASTER_KEY_BYTES bytes (got ${masterKey.size})"
        }
        require(keyGeneration >= 1) {
            "key_generation must be >= 1 (got $keyGeneration)"
        }
        securePrefs.putString(KEY, Base64.encodeToString(masterKey, Base64.NO_WRAP))
        securePrefs.putInt(KEY_GEN, keyGeneration)
    }

    override fun clear() {
        securePrefs.remove(KEY)
        securePrefs.remove(KEY_GEN)
    }

    private companion object {
        const val KEY = "syncler.v4.master_key_persisted"
        const val KEY_GEN = "syncler.v4.master_key_generation"
        const val MASTER_KEY_BYTES = 32
        // Fallback for installs that wrote a master key under the
        // pre-fix schema (which only stored the key, not the
        // generation). Reading these as gen=1 matches the
        // pre-rotation default and is safe for any user who has
        // never rotated.
        const val FALLBACK_KEY_GENERATION = 1
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MasterKeyStoreModule {
    @Binds
    @Singleton
    abstract fun bindMasterKeyStore(impl: SecurePrefsMasterKeyStore): MasterKeyStore
}
