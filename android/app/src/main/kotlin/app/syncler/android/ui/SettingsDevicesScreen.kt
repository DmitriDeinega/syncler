package app.syncler.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.syncler.core.auth.AuthRepository
import app.syncler.core.network.DeviceItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DevicesUiState(
    val loading: Boolean = false,
    val devices: List<DeviceItem> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            _state.value = authRepository.listDevices().fold(
                onSuccess = { DevicesUiState(devices = it) },
                onFailure = { DevicesUiState(error = it.message ?: "Unable to load devices.") },
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
}

@Composable
fun SettingsDevicesScreen(viewModel: DevicesViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Devices", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
        }
        Spacer(Modifier.height(16.dp))
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
        }
        if (state.loading) {
            Text("Loading")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.devices, key = { it.id }) { device ->
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(device.id, style = MaterialTheme.typography.titleMedium)
                        Text("Created: ${device.createdAt ?: "unknown"}")
                        Text("Last seen: ${device.lastSeen ?: "never"}")
                        Text("Revoked: ${device.revokedAt ?: "no"}")
                        Spacer(Modifier.height(8.dp))
                        Button(
                            enabled = device.revokedAt == null,
                            onClick = { viewModel.revoke(device.id) },
                        ) {
                            Text("Revoke")
                        }
                    }
                }
            }
        }
    }
}
