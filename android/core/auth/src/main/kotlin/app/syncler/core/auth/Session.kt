package app.syncler.core.auth

import android.util.Base64
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
import org.json.JSONObject

data class SessionState(
    val token: String?,
    val masterKey: ByteArray?,
) {
    val isUnlocked: Boolean = !token.isNullOrBlank() && masterKey != null
}

@Singleton
class Session @Inject constructor(
    private val tokenStore: TokenStore,
) : AuthTokenProvider {
    private val state = MutableStateFlow(SessionState(token = tokenStore.readToken(), masterKey = null))

    val sessionState: StateFlow<SessionState> = state.asStateFlow()
    val isUnlocked = sessionState.map { it.isUnlocked }

    override fun currentToken(): String? = state.value.token

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
            val padded = segments[1].padEnd((segments[1].length + 3) / 4 * 4, '=')
            val body = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
            JSONObject(String(body, Charsets.UTF_8)).optString("sub").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun authenticate(token: String, masterKey: ByteArray) {
        tokenStore.writeToken(token)
        state.value = SessionState(token = token, masterKey = masterKey.copyOf())
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
    suspend fun logout() {
        state.value.masterKey?.fill(0)
        tokenStore.clearToken()
        state.value = SessionState(token = null, masterKey = null)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionBindings {
    @Binds
    abstract fun bindTokenStore(store: SessionStore): TokenStore

    @Binds
    @IntoSet
    abstract fun bindAuthTokenProvider(session: Session): AuthTokenProvider

    @Binds
    abstract fun bindDevicePublicKeyProvider(provider: DeviceKeyProvider): DevicePublicKeyProvider

    @Binds
    abstract fun bindDeviceIdentityStore(store: SecureDeviceIdentityStore): DeviceIdentityStore
}
