package app.syncler.core.auth

import app.syncler.core.network.AuthTokenProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

private val SUB_CLAIM_REGEX = Regex("""\"sub\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""")

data class SessionState(
    val token: String?,
    val masterKey: ByteArray?,
    /**
     * Phase 8 (docs/crypto-spec.md §10): the master-key generation
     * currently in use. Bumps on root_* rotations; stays on
     * password_rewrap. Defaults to 1 for users who never rotated
     * and for pre-Phase-8 server responses.
     */
    val keyGeneration: Int = 1,
    /**
     * Phase 8 — the auth_salt + wrapped MK from the most recent
     * login, kept in-memory so the "Change password" flow can re-
     * derive Argon2 + re-wrap WITHOUT bouncing through pre-login →
     * login again (which would issue a stray fresh token and
     * confuse the session observers). These are NOT persisted —
     * they re-populate on every login.
     *
     * `authSalt` is the 16-byte client-side Argon2 salt for the
     * CURRENT password. `encryptedMasterKey` is the AES-GCM blob
     * the server returned (nonce || ciphertext || tag).
     */
    val authSalt: ByteArray? = null,
    val encryptedMasterKey: ByteArray? = null,
) {
    val isUnlocked: Boolean = !token.isNullOrBlank() && masterKey != null
}

@Singleton
class Session @Inject constructor(
    private val tokenStore: TokenStore,
    private val masterKeyStore: app.syncler.core.storage.MasterKeyStore =
        app.syncler.core.storage.NoOpMasterKeyStore,
) : AuthTokenProvider {
    /**
     * V4 #20 — cold-start restore. If both the JWT and the persisted
     * master key survived the last process death, the app boots into
     * an unlocked session WITHOUT prompting for a password. Either
     * side missing → locked → AuthScreen on launch (matches pre-V4
     * #20 behaviour for clean installs).
     */
    private val state = MutableStateFlow(
        run {
            val token = tokenStore.readToken()
            val persistedMasterKey = if (token != null) masterKeyStore.read() else null
            SessionState(
                token = token,
                masterKey = persistedMasterKey,
                keyGeneration = 1,
            )
        }
    )

    val sessionState: StateFlow<SessionState> = state.asStateFlow()
    val isUnlocked = sessionState.map { it.isUnlocked }

    override fun currentToken(): String? = state.value.token

    /**
     * Phase 8 — current master-key generation for the active session.
     * Repositories that PUT key_generation-tagged blobs read this when
     * building requests; rotation flow updates it on success.
     */
    fun currentKeyGeneration(): Int = state.value.keyGeneration

    /**
     * Returns the `sub` claim from the current JWT (the user UUID) or null if
     * no session is active or the token is malformed. Used by dev affordances
     * that need to surface the user's identity (e.g. pairing-key copy screen).
     */
    fun currentUserId(): String? {
        val token = state.value.token ?: return null
        val segments = token.split('.')
        if (segments.size < 2) return null
        return runCatching {
            // Use java.util.Base64 so unit tests (which don't ship the
            // android.util.Base64 / org.json stubs) work the same as
            // the production runtime. The middle JWT segment is
            // base64-url without padding.
            val raw = segments[1]
            val padded = raw.padEnd((raw.length + 3) / 4 * 4, '=')
            val body = String(
                java.util.Base64.getUrlDecoder().decode(padded),
                Charsets.UTF_8,
            )
            // We don't pull a full JSON parser for this — the JWT
            // body is well-formed canonical JSON from the server's
            // pyjwt encoder. Extract `sub` via a defensive regex
            // that requires the key and a quoted string value, and
            // handles whitespace + escape sequences inside the value.
            SUB_CLAIM_REGEX.find(body)
                ?.groupValues
                ?.get(1)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun authenticate(
        token: String,
        masterKey: ByteArray,
        keyGeneration: Int = 1,
        authSalt: ByteArray? = null,
        encryptedMasterKey: ByteArray? = null,
    ) {
        tokenStore.writeToken(token)
        // V4 #20 — persist the unwrapped master key under the Keystore-
        // protected SecurePrefs so the next cold start does not need a
        // password prompt. Wiped by [logout] / Sign out.
        masterKeyStore.write(masterKey)
        state.value = SessionState(
            token = token,
            masterKey = masterKey.copyOf(),
            keyGeneration = keyGeneration,
            authSalt = authSalt?.copyOf(),
            encryptedMasterKey = encryptedMasterKey?.copyOf(),
        )
    }

    /**
     * Phase 8 — update the in-memory master key + key_generation
     * without re-authenticating. Used by the rotation flow when the
     * server returns the new generation post-rotate. For
     * `root_compromise_rotation`, the server invalidates ALL device
     * sessions (including ours) so callers should NOT use this —
     * trigger a fresh login instead.
     *
     * For `password_rewrap` callers also pass [newAuthSalt] +
     * [newEncryptedMasterKey] so a subsequent "Change password"
     * flow can re-rewrap without bouncing through login.
     */
    fun updateAfterRotation(
        newMasterKey: ByteArray,
        newKeyGeneration: Int,
        newAuthSalt: ByteArray? = null,
        newEncryptedMasterKey: ByteArray? = null,
    ) {
        val previous = state.value
        require(previous.isUnlocked) { "cannot update an unlocked session" }
        // Copy the new bytes FIRST, BEFORE zeroing the previous arrays —
        // password_rewrap passes ``newMasterKey = previous.masterKey``
        // (same reference), so zeroing first would also zero the
        // bytes we're about to copy into the next state.
        val nextMasterKey = newMasterKey.copyOf()
        val nextAuthSalt = newAuthSalt?.copyOf() ?: previous.authSalt?.copyOf()
        val nextEncryptedMasterKey =
            newEncryptedMasterKey?.copyOf() ?: previous.encryptedMasterKey?.copyOf()
        previous.masterKey?.fill(0)
        previous.authSalt?.fill(0)
        // encryptedMasterKey is rest-encrypted so it's safe to drop
        // without zeroing — the wrap key is what protects it.
        state.value = previous.copy(
            masterKey = nextMasterKey,
            keyGeneration = newKeyGeneration,
            authSalt = nextAuthSalt,
            encryptedMasterKey = nextEncryptedMasterKey,
        )
    }

    /**
     * Wipes the in-memory session. User-scoped singletons (read marks,
     * paired-sender store, archives, ...) observe [sessionState] and
     * react to the transition to a locked state by clearing themselves —
     * see [app.syncler.feature.inbox.UserStateRepository] for the
     * canonical pattern. The observer model avoids the Hilt dep cycle
     * that a clearable multibinding would introduce (clearables need the
     * Session for the master key; Session would need the clearable Set).
     */
    /**
     * Phase 8e — clear ONLY the persisted token (not the in-memory
     * masterKey). Used by `root_compromise_rotation` after the
     * server confirms the rotation: the server has revoked our
     * session, so we wipe SessionStore immediately to remove the
     * dangling-revoked-token window if the app is killed before
     * the UI fires its full logout. The in-memory state stays
     * intact so the post-success "Sign out" dialog can render
     * cleanly; the surrounding UI's onLogout callback eventually
     * calls [logout] to wipe the rest.
     */
    fun clearPersistedTokenForCompromise() {
        tokenStore.clearToken()
        // V4 #20 — root_compromise_rotation invalidates the session
        // on the server; wipe both the persisted JWT AND the persisted
        // master key so the next cold start cannot resurrect an
        // already-revoked session.
        masterKeyStore.clear()
    }

    suspend fun logout() {
        val previous = state.value
        previous.masterKey?.fill(0)
        previous.authSalt?.fill(0)
        tokenStore.clearToken()
        // V4 #20 — full logout wipes the persisted master key too.
        // Codex 166 + gemini 166 BOTH flagged "MasterKeyStore lands
        // before sign-out wipe semantics" as the riskiest partial
        // state; this MUST stay paired with the persistence call in
        // [authenticate] in the same change set.
        masterKeyStore.clear()
        state.value = SessionState(
            token = null,
            masterKey = null,
            keyGeneration = 1,
            authSalt = null,
            encryptedMasterKey = null,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionBindings {
    @Binds
    abstract fun bindTokenStore(store: SessionStore): TokenStore

    @Binds
    abstract fun bindKeyGenerationStore(store: SecurePrefsKeyGenerationStore): KeyGenerationStore

    @Binds
    @IntoSet
    abstract fun bindAuthTokenProvider(session: Session): AuthTokenProvider

    @Binds
    abstract fun bindDevicePublicKeyProvider(provider: DeviceKeyProvider): DevicePublicKeyProvider

    @Binds
    abstract fun bindDeviceIdentityStore(store: SecureDeviceIdentityStore): DeviceIdentityStore

    /**
     * SSE 401 handler. [AuthRepository] clears the session so the UI
     * routes back to AuthScreen when the EventStreamManager sees a
     * terminal auth failure (Codex consultation 56 RED #12).
     */
    @Binds
    abstract fun bindAuthFailureHandler(
        repository: AuthRepository,
    ): app.syncler.core.network.AuthFailureHandler
}
