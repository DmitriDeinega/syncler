package app.syncler.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.syncler.core.auth.AuthRepository
import app.syncler.core.auth.DeviceIdentityStore
import app.syncler.core.network.DeviceItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DevicesUiState(
    val loading: Boolean = false,
    val devices: List<DeviceItem> = emptyList(),
    /** Server-issued id of THIS device (for the "This device" badge). */
    val currentDeviceId: String? = null,
    val error: String? = null,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val deviceIdentityStore: DeviceIdentityStore,
) : ViewModel() {
    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val currentId = deviceIdentityStore.read()
            _state.value = authRepository.listDevices().fold(
                onSuccess = { DevicesUiState(devices = it, currentDeviceId = currentId) },
                onFailure = {
                    DevicesUiState(
                        currentDeviceId = currentId,
                        error = it.message ?: "Unable to load devices.",
                    )
                },
            )
        }
    }

    fun revoke(id: String) {
        viewModelScope.launch {
            authRepository.revokeDevice(id).onFailure {
                _state.value = _state.value.copy(error = it.message ?: "Unable to revoke device.")
            }
            refresh()
        }
    }

    fun revokeAllOthers() {
        val state = _state.value
        val currentId = state.currentDeviceId ?: return
        val others = state.devices.filter { it.id != currentId && it.revokedAt == null }
        if (others.isEmpty()) return
        viewModelScope.launch {
            others.forEach { device ->
                authRepository.revokeDevice(device.id).onFailure {
                    _state.value = _state.value.copy(error = it.message ?: "Unable to revoke device.")
                }
            }
            refresh()
        }
    }
}
