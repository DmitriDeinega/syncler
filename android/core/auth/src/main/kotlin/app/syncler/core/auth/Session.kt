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

    fun authenticate(token: String, masterKey: ByteArray) {
        tokenStore.writeToken(token)
        state.value = SessionState(token = token, masterKey = masterKey.copyOf())
    }

    fun logout() {
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
}
