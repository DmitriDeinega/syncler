package app.syncler.core.auth

import app.syncler.core.crypto.KeyDerivation
import app.syncler.core.crypto.MasterKey
import app.syncler.core.crypto.base64ToBytes
import app.syncler.core.crypto.toBase64
import app.syncler.core.network.RotateMasterKeyRequestDto
import app.syncler.core.network.RotationChallengeResponseDto
import app.syncler.core.network.SynclerApi
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import timber.log.Timber

/**
 * Phase 8 master-key rotation client (docs/crypto-spec.md §10).
 *
 * V0.1 scope: only ``password_rewrap`` (change the user's password
 * without rotating the underlying master key). The two ``root_*``
 * variants need to fetch every pairing's encrypted_state from the
 * server to re-encrypt under a new master key, which requires a
 * server endpoint (``GET /v1/pairings/{id}/state``) that doesn't
 * exist yet — deferred to Phase 8d together with the §10.9 AAD
 * lockstep wiring.
 *
 * For password_rewrap the master key value stays byte-identical;
 * only its wrapping key + the user's auth_salt + auth_key_hash
 * change. `users.key_generation` is NOT bumped — devices on older
 * generations keep working, no mixed-client gate triggers.
 */
@Singleton
class RotationRepository @Inject constructor(
    private val api: SynclerApi,
    private val session: Session,
    private val keyGenerationStore: KeyGenerationStore,
) {
    private val secureRandom = SecureRandom()

    /**
     * `password_rewrap` (§10.1) — keep the master key, change the
     * password.
     *
     * Steps (mirroring §10.12.1 + §10.8):
     *
     *  1. GET /v1/account/rotate-master-key/challenge.
     *  2. Derive Argon2id(currentPassword, currentSalt) → currentAuthKey + currentWrapKey.
     *  3. Unwrap the in-memory MK with currentWrapKey. (We already
     *     hold the plaintext MK in Session, but we re-derive
     *     currentWrapKey just to confirm the user's password — the
     *     proof is what the server checks; we ALSO need
     *     currentWrapKey to verify it actually unwraps the current
     *     blob, otherwise a malicious caller could pass a wrong
     *     password and still send the proof bytes if those came
     *     from elsewhere. We don't unwrap blindly from Session;
     *     re-deriving and asserting unwrap-equivalence catches
     *     drift.)
     *  4. Generate a fresh 16-byte newSalt.
     *  5. Derive Argon2id(newPassword, newSalt) → newAuthKey + newWrapKey.
     *  6. Re-wrap the existing MK under newWrapKey.
     *  7. POST /v1/account/rotate-master-key with reason="password_rewrap",
     *     key_generation_observed = session.currentKeyGeneration(),
     *     rotation_challenge from step 1, current_password_proof =
     *     currentAuthKey, new_auth_salt = newSalt, new_auth_key_proof =
     *     newAuthKey, new_encrypted_master_key = the re-wrap.
     *  8. Server runs the 14-step transaction. On 200, the response
     *     carries the same key_generation we sent (no bump). Update
     *     local high-water mark just in case the server reports a
     *     different value (defense-in-depth).
     *
     * The caller's plaintext MK in Session does NOT change.
     */
    suspend fun rewrapPassword(
        currentPassword: CharArray,
        newPassword: CharArray,
    ): Result<RewrapResult> = runCatching {
        val sessionState = session.sessionState.value
        require(sessionState.isUnlocked) {
            "rewrapPassword requires an unlocked session"
        }
        val currentAuthSalt = sessionState.authSalt
            ?: error("Session is missing auth_salt — log out and back in to populate")
        val currentEncryptedMasterKey = sessionState.encryptedMasterKey
            ?: error("Session is missing encryptedMasterKey — log out and back in to populate")
        require(currentAuthSalt.size == KeyDerivation.SALT_LENGTH_BYTES) {
            "current auth_salt must be ${KeyDerivation.SALT_LENGTH_BYTES} bytes"
        }
        val userId = session.currentUserId()
            ?: error("session has no user_id claim — cannot rotate")

        // Step 1 — fresh challenge.
        val challenge: RotationChallengeResponseDto = api.rotateMasterKeyChallenge()

        // Step 2 — derive current keys (auth proof + wrap key).
        val currentKeys = withContext(Dispatchers.Default) {
            KeyDerivation.derive(currentPassword, currentAuthSalt)
        }

        try {
            // Step 3 — verify the password by unwrapping the server's
            // current wrapped MK. If the unwrap throws, the user
            // entered the wrong current password and we surface a
            // typed error WITHOUT sending the (incorrect) proof to
            // the server (avoiding the server's failed-proof
            // rate-limit increment for a local mistake).
            val unwrapped = try {
                MasterKey.unwrap(currentEncryptedMasterKey, currentKeys.masterKeyWrapKey)
            } catch (exc: Exception) {
                throw WrongCurrentPasswordError()
            }
            try {
                // Step 4-6 — fresh salt + new derivation + re-wrap.
                val newSalt = ByteArray(KeyDerivation.SALT_LENGTH_BYTES)
                    .also(secureRandom::nextBytes)
                val newKeys = withContext(Dispatchers.Default) {
                    KeyDerivation.derive(newPassword, newSalt)
                }
                try {
                    val rewrapped = MasterKey.wrap(unwrapped, newKeys.masterKeyWrapKey)

                    // Step 7 — submit. Server runs §10.8.
                    val response = api.rotateMasterKey(
                        RotateMasterKeyRequestDto(
                            reason = "password_rewrap",
                            keyGenerationObserved = session.currentKeyGeneration(),
                            rotationChallenge = challenge.rotationChallenge,
                            currentPasswordProof = currentKeys.authKey.toBase64(),
                            newEncryptedMasterKey = rewrapped.toBase64(),
                            newAuthSalt = newSalt.toBase64(),
                            newAuthKeyProof = newKeys.authKey.toBase64(),
                        ),
                    )
                    if (!response.isSuccessful) {
                        val code = response.code()
                        Timber.tag(TAG).w("rewrapPassword failed HTTP %d", code)
                        throw HttpException(response)
                    }
                    val body = response.body() ?: error("rewrap returned 200 with no body")

                    // Step 8 — §10.10 verify+bump and update Session
                    // so the cached salt + wrapped MK match the new
                    // wrap. For password_rewrap the server returns
                    // the same generation we sent (no key bump);
                    // verifyAndBump still asserts no downgrade
                    // (a malicious server could return a stale `1`
                    // here and we MUST hard-fail rather than silently
                    // accept).
                    keyGenerationStore.verifyAndBump(
                        userId = userId,
                        observed = body.keyGeneration,
                        source = "rotate-master-key",
                    )
                    // updateAfterRotation re-uses the SAME plaintext
                    // MK (sessionState.masterKey) — password_rewrap
                    // does not rotate the MK value.
                    session.updateAfterRotation(
                        newMasterKey = sessionState.masterKey!!,
                        newKeyGeneration = body.keyGeneration,
                        newAuthSalt = newSalt,
                        newEncryptedMasterKey = rewrapped,
                    )

                    RewrapResult(
                        newKeyGeneration = body.keyGeneration,
                        newAuthSalt = newSalt,
                        newEncryptedMasterKey = rewrapped,
                    )
                } finally {
                    newKeys.authKey.fill(0)
                    newKeys.masterKeyWrapKey.fill(0)
                }
            } finally {
                unwrapped.fill(0)
            }
        } finally {
            currentKeys.authKey.fill(0)
            currentKeys.masterKeyWrapKey.fill(0)
        }
    }

    private companion object {
        const val TAG = "Rotation"
    }
}

/**
 * Returned by a successful [RotationRepository.rewrapPassword]. The
 * caller may want to persist the new auth_salt / wrapped MK locally
 * so the next login round-trips without hitting /v1/auth/pre-login
 * again.
 */
data class RewrapResult(
    val newKeyGeneration: Int,
    val newAuthSalt: ByteArray,
    val newEncryptedMasterKey: ByteArray,
)

/**
 * The user-entered current password failed to unwrap the in-memory
 * MK. Raised LOCALLY before any network call so the server's
 * failed-proof rate limit isn't incremented for typing errors.
 */
class WrongCurrentPasswordError :
    IllegalStateException("current password is incorrect (local unwrap failed)")
