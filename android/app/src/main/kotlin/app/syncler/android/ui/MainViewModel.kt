package app.syncler.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.syncler.core.auth.AuthRepository
import app.syncler.core.auth.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val isUnlocked = session.isUnlocked

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
