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

@HiltViewModel
class MainViewModel @Inject constructor(
    session: Session,
    private val authRepository: AuthRepository,
) : ViewModel() {
    val isUnlocked = session.isUnlocked

    private val _showingDevices = MutableStateFlow(false)
    val showingDevices: StateFlow<Boolean> = _showingDevices

    fun showDevices() {
        _showingDevices.value = true
    }

    fun showInbox() {
        _showingDevices.value = false
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _showingDevices.value = false
        }
    }
}
