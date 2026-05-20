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

enum class TopLevelScreen { Inbox, Devices, Pairing }

@HiltViewModel
class MainViewModel @Inject constructor(
    session: Session,
    private val authRepository: AuthRepository,
) : ViewModel() {
    val isUnlocked = session.isUnlocked

    private val _screen = MutableStateFlow(TopLevelScreen.Inbox)
    val screen: StateFlow<TopLevelScreen> = _screen

    fun showInbox() { _screen.value = TopLevelScreen.Inbox }
    fun showDevices() { _screen.value = TopLevelScreen.Devices }
    fun showPairing() { _screen.value = TopLevelScreen.Pairing }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _screen.value = TopLevelScreen.Inbox
        }
    }
}
