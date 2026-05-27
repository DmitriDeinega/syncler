package app.syncler.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.syncler.core.auth.AuthRepository
import app.syncler.core.auth.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Three top-level destinations exposed via the [androidx.compose.material3.NavigationBar].
 * Sub-screens (Pairing, Archive, Devices detail, etc.) are surfaced as overlays on the
 * relevant tab by the tab's own ViewModel — they don't appear in the bottom nav.
 */
enum class TopLevelScreen { Inbox, Senders, Settings }

@HiltViewModel
class MainViewModel @Inject constructor(
    session: Session,
    private val authRepository: AuthRepository,
) : ViewModel() {
    /**
     * V4 #20 follow-up: turn this into a real StateFlow with the
     * session's CURRENT unlocked state seeded as initial value.
     *
     * Previously this was a `Flow<Boolean>` (Session.isUnlocked is a
     * `.map` on the SessionState StateFlow), which forced MainActivity
     * to call `collectAsStateWithLifecycle(initialValue = false)` —
     * causing one frame of AuthScreen on every cold start even when
     * the master key + token were already persisted. User saw it as
     * "login flashes then the inbox appears."
     *
     * StateIn'ing under viewModelScope with the synchronously-read
     * Session.sessionState.value as the initial value means the
     * MainActivity's first composition gets the correct value
     * directly. No more flash; no need for a tri-state Loading
     * splash either since the answer is known the moment Compose
     * starts.
     */
    val isUnlocked: StateFlow<Boolean> = session.sessionState
        .map { it.isUnlocked }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = session.sessionState.value.isUnlocked,
        )

    private val _screen = MutableStateFlow(TopLevelScreen.Inbox)
    val screen: StateFlow<TopLevelScreen> = _screen

    fun select(tab: TopLevelScreen) { _screen.value = tab }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _screen.value = TopLevelScreen.Inbox
        }
    }
}
