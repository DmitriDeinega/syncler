package app.syncler.core.auth

import app.syncler.core.storage.SecurePrefs
import javax.inject.Inject
import javax.inject.Singleton

interface TokenStore {
    fun readToken(): String?
    fun writeToken(token: String)
    fun clearToken()
}

class SessionStore @Inject constructor(
    private val securePrefs: SecurePrefs,
) : TokenStore {
    override fun readToken(): String? = securePrefs.getString(KEY_SESSION_TOKEN)

    override fun writeToken(token: String) {
        securePrefs.putString(KEY_SESSION_TOKEN, token)
    }

    override fun clearToken() {
        securePrefs.remove(KEY_SESSION_TOKEN)
    }

    private companion object {
        const val KEY_SESSION_TOKEN = "session_token"
    }
}

/**
 * Phase 8 §10.10 — per-user `highest_key_generation_seen`. Stored
 * per-user-id so logging into account B after A doesn't trigger a
 * false-positive downgrade rejection. The high-water mark survives
 * logout and is bumped (never reduced) on every authenticated
 * response carrying a generation.
 *
 * Interface-typed so unit tests can substitute an in-memory fake
 * without dragging the Android-only [SecurePrefs] (which needs a
 * Context) into pure-JVM test scopes.
 */
interface KeyGenerationStore {
    /** Read the recorded high-water mark for [userId], 0 if unseen. */
    fun read(userId: String): Int

    /**
     * Update the high-water mark for [userId] to the max of the
     * current value and [observed]. No-ops if [observed] is lower.
     * Returns the value AFTER the update (so callers can log it).
     */
    fun bump(userId: String, observed: Int): Int

    /**
     * §10.10 enforcement helper: assert [observed] is NOT a
     * downgrade against the local high-water mark, then bump.
     *
     * EVERY response carrying a `key_generation`-tagged value MUST
     * route through this — not just `/login`. The spec is explicit:
     * "Every device persists `highest_key_generation_seen` … Every
     * response carrying a `key_generation`-tagged value is checked".
     * Logging the check only at login would let a malicious server
     * serve a stale wrapped MK + state once authenticated and the
     * AEAD decrypts would all succeed (everything matches under the
     * old generation) — silently freezing the client on a snapshot.
     *
     * Throws [KeyGenerationDowngradeError] when `observed < high
     * water`. Equal or higher → bump + return the new mark.
     */
    fun verifyAndBump(userId: String, observed: Int, source: String): Int {
        val current = read(userId)
        if (observed < current) {
            throw KeyGenerationDowngradeError(
                userId = userId,
                serverReturned = observed,
                highestSeen = current,
                source = source,
            )
        }
        return bump(userId, observed)
    }
}

/**
 * Phase 8 §10.10 — the server's returned `key_generation` is LOWER
 * than the highest we've seen for this user on this device. Treat as
 * a possible downgrade attack and refuse to continue.
 *
 * The [source] string is opaque ("login", "state.get", "state.put",
 * "rotate-master-key") — used purely for log/UI surface so the user
 * can tell where the divergence was caught.
 */
class KeyGenerationDowngradeError(
    val userId: String,
    val serverReturned: Int,
    val highestSeen: Int,
    val source: String,
) : IllegalStateException(
    "server returned key_generation=$serverReturned at $source for user " +
        "$userId; local high-water mark is $highestSeen — refusing to continue",
)

@Singleton
class SecurePrefsKeyGenerationStore @Inject constructor(
    private val securePrefs: SecurePrefs,
) : KeyGenerationStore {
    override fun read(userId: String): Int = securePrefs.getInt(keyFor(userId), 0)

    override fun bump(userId: String, observed: Int): Int {
        val current = read(userId)
        if (observed > current) {
            securePrefs.putInt(keyFor(userId), observed)
            return observed
        }
        return current
    }

    private fun keyFor(userId: String): String = "$KEY_PREFIX$userId"

    private companion object {
        const val KEY_PREFIX = "highest_key_generation_seen_"
    }
}
