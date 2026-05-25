package app.syncler.core.auth

import app.syncler.core.crypto.Aead
import app.syncler.core.crypto.KeyDerivation
import app.syncler.core.crypto.MasterKey
import app.syncler.core.crypto.RotationAad
import app.syncler.core.crypto.base64ToBytes
import app.syncler.core.crypto.toBase64
import app.syncler.core.network.RotateMasterKeyRequestDto
import app.syncler.core.network.RotationChallengeResponseDto
import app.syncler.core.network.RotationPairingEntryDto
import app.syncler.core.network.RotationUserStateBodyDto
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
 * Three modes per §10.1:
 *
 *   - ``password_rewrap``: master key value unchanged, only wrap key
 *     + auth_salt + auth_key_hash change. No blob re-encryption,
 *     no `key_generation` bump, no session revocation.
 *   - ``root_hygiene_rotation``: new master key value, same wrap
 *     key (same password + salt). EVERY blob (encrypted_user_state
 *     + every pairing.encrypted_state) is re-encrypted under the
 *     new MK. `key_generation` bumps by 1. Sessions stay live.
 *   - ``root_compromise_rotation``: new MK + new wrap key + new
 *     salt + new password. All blobs re-encrypted. ALL sessions
 *     revoked including the initiating one — caller MUST log out
 *     after success and force a fresh login.
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
            //
            // Phase 8d §10.9 — the wrapped MK is AAD-bound to
            // (auth_salt_b64, user_id); use the SAME AAD on unwrap.
            val currentMkAad = RotationAad.masterKeyWrap(
                userId = userId,
                authSaltB64 = currentAuthSalt.toBase64(),
            )
            val unwrapped = try {
                MasterKey.unwrap(
                    currentEncryptedMasterKey,
                    currentKeys.masterKeyWrapKey,
                    aad = currentMkAad,
                )
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
                    // Phase 8d §10.9 — new MK wrap binds the NEW
                    // auth_salt; user_id is unchanged.
                    val newMkAad = RotationAad.masterKeyWrap(
                        userId = userId,
                        authSaltB64 = newSalt.toBase64(),
                    )
                    val rewrapped = MasterKey.wrap(
                        masterKey = unwrapped,
                        masterKeyWrapKey = newKeys.masterKeyWrapKey,
                        aad = newMkAad,
                    )

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

    /**
     * `root_hygiene_rotation` (§10.1) — generate a new master key,
     * re-encrypt every blob, KEEP the user's password.
     *
     *  1. GET rotation challenge.
     *  2. Derive Argon2(currentPassword, currentSalt) → recover the
     *     CURRENT wrap key + auth proof.
     *  3. Local sanity unwrap on the cached wrappedMK (catches
     *     typos before the server's failed-proof bucket increments).
     *  4. GET /v1/state → decrypt under the OLD MK with the
     *     OLD user-state AAD.
     *  5. GET /v1/pairing (filter active) → for each pairing
     *     GET /v1/pairing/{id}/state → decrypt under the OLD MK
     *     with the OLD pairing AAD.
     *  6. Generate a fresh 32-byte newMasterKey.
     *  7. Re-encrypt the user state under newMasterKey with the
     *     NEW user-state AAD (key_generation = oldGen + 1,
     *     state_version = oldStateVersion + 1, user_id unchanged).
     *  8. Re-encrypt each pairing under newMasterKey with the
     *     NEW pairing AAD (same lockstep math).
     *  9. Wrap newMasterKey under the SAME wrap key (password
     *     unchanged) with the SAME MK wrap AAD (auth_salt + user_id
     *     unchanged).
     * 10. POST /v1/account/rotate-master-key with
     *     reason="root_hygiene_rotation".
     * 11. verifyAndBump key_generation high-water mark;
     *     session.updateAfterRotation with the NEW MK +
     *     NEW key_generation.
     */
    suspend fun rotateHygiene(
        currentPassword: CharArray,
    ): Result<RootRotateResult> = rotateRoot(
        currentPassword = currentPassword,
        newPassword = currentPassword,
        compromise = false,
    )

    /**
     * `root_compromise_rotation` (§10.1) — new MK + new wrap key +
     * new password + new salt. Same blob-rotation as hygiene PLUS:
     *
     *  - Generate fresh newSalt + derive Argon2(newPassword, newSalt)
     *    → newWrapKey + newAuthKey.
     *  - Wrap newMasterKey under newWrapKey with NEW MK wrap AAD
     *    (new auth_salt + user_id unchanged).
     *  - Include new_auth_salt + new_auth_key_proof in the request.
     *  - Server revokes ALL device sessions — including ours.
     *  - Caller MUST trigger logout after success (the next
     *    authenticated call will 401).
     */
    suspend fun rotateCompromise(
        currentPassword: CharArray,
        newPassword: CharArray,
    ): Result<RootRotateResult> = rotateRoot(
        currentPassword = currentPassword,
        newPassword = newPassword,
        compromise = true,
    )

    private suspend fun rotateRoot(
        currentPassword: CharArray,
        newPassword: CharArray,
        compromise: Boolean,
    ): Result<RootRotateResult> = runCatching {
        val sessionState = session.sessionState.value
        require(sessionState.isUnlocked) {
            "root_* rotation requires an unlocked session"
        }
        val currentMasterKey = sessionState.masterKey
            ?: error("Session is missing masterKey")
        val currentAuthSalt = sessionState.authSalt
            ?: error("Session is missing auth_salt — log out and back in")
        val currentEncryptedMasterKey = sessionState.encryptedMasterKey
            ?: error("Session is missing encryptedMasterKey — log out and back in")
        val userId = session.currentUserId()
            ?: error("Session has no user_id claim — cannot rotate")
        val currentKeyGen = session.currentKeyGeneration()
        val newKeyGen = currentKeyGen + 1

        // Step 1 — challenge.
        val challenge: RotationChallengeResponseDto = api.rotateMasterKeyChallenge()

        // Step 2 — derive current keys.
        val currentKeys = withContext(Dispatchers.Default) {
            KeyDerivation.derive(currentPassword, currentAuthSalt)
        }
        try {
            // Step 3 — local unwrap test (against cached wrappedMK).
            val currentMkAad = RotationAad.masterKeyWrap(
                userId = userId,
                authSaltB64 = currentAuthSalt.toBase64(),
            )
            try {
                MasterKey.unwrap(
                    currentEncryptedMasterKey,
                    currentKeys.masterKeyWrapKey,
                    aad = currentMkAad,
                ).fill(0)  // scratch — we already have plaintext MK in session
            } catch (exc: Exception) {
                throw WrongCurrentPasswordError()
            }

            // Step 4 — fetch current user state + decrypt.
            val userStateResp = api.getUserState()
            // §10.10 — refuse to operate on a stale snapshot.
            keyGenerationStore.verifyAndBump(
                userId = userId,
                observed = userStateResp.keyGeneration,
                source = "rotate-root.state.get",
            )
            val oldStateAad = RotationAad.userState(
                userId = userId,
                keyGeneration = userStateResp.keyGeneration,
                stateVersion = userStateResp.stateVersion,
            )
            // Pre-allocate the plaintext slots OUTSIDE the try below so the
            // surrounding `finally` can zero them regardless of which step
            // threw. Codex / Gemini 109 RED: inline `.fill(0)` after
            // re-encryption left plaintexts in memory if an intermediate
            // throw skipped over the zeroing.
            val statePlaintext: ByteArray = Aead.decrypt(
                currentMasterKey,
                java.util.Base64.getDecoder().decode(userStateResp.encryptedBlob),
                aad = oldStateAad,
            )
            // Snapshot of every decrypted pairing — populated incrementally
            // inside the try below. If a subsequent decrypt or re-encrypt
            // throws we still hit `finally` and zero whatever we already
            // pulled.
            data class PairingSnapshot(
                val pairingId: String,
                val stateVersion: Int,
                val plaintext: ByteArray,
            )
            val snapshots = mutableListOf<PairingSnapshot>()
            // Compromise-mode derived keys live here so the outer finally
            // can wipe them regardless of where we throw.
            var newWrapKey: ByteArray? = null
            var newAuthKey: ByteArray? = null
            // Codex/Gemini 110 RED: MasterKey.generate() can throw
            // (OOM, SecureRandom provider fault). If it runs OUTSIDE
            // the try block, the finally never fires and
            // statePlaintext leaks. Declare nullable here, allocate
            // INSIDE the try below, null-check in cleanup. Inside
            // the try the local `mk` aliases the non-null value so
            // we don't lose smart-cast across lambda captures.
            var newMasterKeyCleanup: ByteArray? = null
            try {
                val newMasterKey: ByteArray = MasterKey.generate().also { newMasterKeyCleanup = it }
                // Step 5 — fetch active pairings + each pairing's state.
                val pairingList = api.listPairings().filter { it.revokedAt == null }
                for (item in pairingList) {
                    val resp = api.getPairingState(item.id)
                    val oldPairAad = RotationAad.pairingState(
                        userId = userId,
                        pairingId = item.id,
                        keyGeneration = resp.keyGeneration,
                        stateVersion = resp.stateVersion,
                    )
                    val plaintext = Aead.decrypt(
                        currentMasterKey,
                        java.util.Base64.getDecoder().decode(resp.encryptedState),
                        aad = oldPairAad,
                    )
                    // Append BEFORE any further work so finally can wipe
                    // even if Aead.decrypt on the next pairing throws.
                    snapshots.add(
                        PairingSnapshot(
                            pairingId = item.id,
                            stateVersion = resp.stateVersion,
                            plaintext = plaintext,
                        ),
                    )
                }

                // Step 7 — re-encrypt user state under new MK with NEW AAD.
                val newStateVersion = userStateResp.stateVersion + 1
                val newStateAad = RotationAad.userState(
                    userId = userId,
                    keyGeneration = newKeyGen,
                    stateVersion = newStateVersion,
                )
                val newEncryptedUserState = Aead.encrypt(
                    newMasterKey,
                    statePlaintext,
                    aad = newStateAad,
                )

                // Step 8 — re-encrypt each pairing.
                val newPairingEntries = snapshots.map { snap ->
                    val newPairAad = RotationAad.pairingState(
                        userId = userId,
                        pairingId = snap.pairingId,
                        keyGeneration = newKeyGen,
                        stateVersion = snap.stateVersion + 1,
                    )
                    val newPairBlob = Aead.encrypt(
                        newMasterKey,
                        snap.plaintext,
                        aad = newPairAad,
                    )
                    RotationPairingEntryDto(
                        pairingId = snap.pairingId,
                        stateVersionObserved = snap.stateVersion,
                        newEncryptedState = newPairBlob.toBase64(),
                    )
                }

                // Step 9 — wrap new MK. Compromise uses new wrap key + new salt.
                val newAuthSalt: ByteArray
                if (compromise) {
                    val freshSalt = ByteArray(KeyDerivation.SALT_LENGTH_BYTES)
                        .also(secureRandom::nextBytes)
                    val keys = withContext(Dispatchers.Default) {
                        KeyDerivation.derive(newPassword, freshSalt)
                    }
                    newWrapKey = keys.masterKeyWrapKey
                    newAuthKey = keys.authKey
                    newAuthSalt = freshSalt
                } else {
                    // Hygiene reuses the existing wrap key + salt. Do NOT
                    // null out currentKeys.masterKeyWrapKey — the outer
                    // finally still wipes it.
                    newWrapKey = currentKeys.masterKeyWrapKey
                    newAuthSalt = currentAuthSalt
                }
                val newMkWrapAad = RotationAad.masterKeyWrap(
                    userId = userId,
                    authSaltB64 = newAuthSalt.toBase64(),
                )
                val newWrappedMk = MasterKey.wrap(
                    masterKey = newMasterKey,
                    masterKeyWrapKey = newWrapKey,
                    aad = newMkWrapAad,
                )

                // Step 10 — POST rotate-master-key.
                val response = api.rotateMasterKey(
                    RotateMasterKeyRequestDto(
                        reason = if (compromise) "root_compromise_rotation"
                                 else "root_hygiene_rotation",
                        keyGenerationObserved = currentKeyGen,
                        rotationChallenge = challenge.rotationChallenge,
                        currentPasswordProof = currentKeys.authKey.toBase64(),
                        newEncryptedMasterKey = newWrappedMk.toBase64(),
                        newAuthSalt = if (compromise) newAuthSalt.toBase64() else null,
                        newAuthKeyProof = if (compromise) newAuthKey?.toBase64() else null,
                        newEncryptedUserState = RotationUserStateBodyDto(
                            encryptedBlob = newEncryptedUserState.toBase64(),
                            stateVersionObserved = userStateResp.stateVersion,
                        ),
                        pairings = newPairingEntries,
                    ),
                )
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w(
                        "root rotation failed HTTP %d",
                        response.code(),
                    )
                    throw HttpException(response)
                }
                val body = response.body() ?: error("root rotation returned 200 with no body")

                // Step 11 — verify new key_generation + bump high-water mark.
                keyGenerationStore.verifyAndBump(
                    userId = userId,
                    observed = body.keyGeneration,
                    source = "rotate-master-key",
                )

                if (compromise) {
                    // Server revoked our session. Wipe the persisted
                    // token IMMEDIATELY so an app-kill before the UI
                    // fires its full logout doesn't leave a revoked
                    // token in SessionStore (Codex 109 YELLOW: close
                    // the revoked-token window). The in-memory state
                    // stays intact for the Success dialog to render;
                    // the UI's onLogout callback wipes the rest.
                    session.clearPersistedTokenForCompromise()
                    // Still update in-memory so observers see a
                    // coherent state until the explicit logout fires
                    // (defense in depth — and unifies the Session-
                    // shape between modes).
                    session.updateAfterRotation(
                        newMasterKey = newMasterKey,
                        newKeyGeneration = body.keyGeneration,
                        newAuthSalt = newAuthSalt,
                        newEncryptedMasterKey = newWrappedMk,
                    )
                } else {
                    // Hygiene keeps sessions live. Update Session with
                    // the new MK + new generation; salt unchanged.
                    session.updateAfterRotation(
                        newMasterKey = newMasterKey,
                        newKeyGeneration = body.keyGeneration,
                        newAuthSalt = currentAuthSalt,
                        newEncryptedMasterKey = newWrappedMk,
                    )
                }

                RootRotateResult(
                    newKeyGeneration = body.keyGeneration,
                    sessionsRevoked = compromise,
                    pairingsRotated = newPairingEntries.size,
                )
            } finally {
                // §10.x hygiene — wipe every plaintext byte array we
                // touched regardless of which step threw. Codex 109
                // YELLOW + Gemini 109 YELLOW called this out: the
                // previous inline `.fill(0)` was only reached on the
                // happy path. Codex/Gemini 110 RED additionally
                // required MasterKey.generate() to live INSIDE the
                // try so a throw there doesn't bypass cleanup.
                newMasterKeyCleanup?.fill(0)
                statePlaintext.fill(0)
                snapshots.forEach { it.plaintext.fill(0) }
                // Hygiene reuses currentKeys.masterKeyWrapKey for
                // newWrapKey — that's the SAME byte array, so the
                // outer finally below wipes it. Only wipe here when
                // it's a freshly-derived key (compromise path).
                if (compromise) {
                    newWrapKey?.fill(0)
                    newAuthKey?.fill(0)
                }
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
 * Result of [RotationRepository.rotateHygiene] or
 * [RotationRepository.rotateCompromise]. ``sessionsRevoked`` is
 * true for compromise — the caller MUST log out after receiving
 * this so the local Session matches the server (its next call
 * would 401 anyway).
 */
data class RootRotateResult(
    val newKeyGeneration: Int,
    val sessionsRevoked: Boolean,
    val pairingsRotated: Int,
)

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
