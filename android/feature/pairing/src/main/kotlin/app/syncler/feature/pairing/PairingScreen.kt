package app.syncler.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Minimal V1 pairing UI: user pastes the broker URL the sender displayed,
 * we POST /v1/pairing/complete, surface the fingerprint + sender name for
 * the user to confirm matches what the sender displayed, lock the
 * PairedSender record on confirm.
 *
 * QR scanning is queued for M11 polish (CameraX + MLKit). The flow shape
 * is the same; only the input mechanism differs.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: PairingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PairingState.Idle as PairingState)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    fun pair(brokerUrl: String) {
        val candidate = repository.parseBrokerUrl(brokerUrl)
        if (candidate == null) {
            _state.value = PairingState.Error("Invalid pairing URL — must be https://…?token=…")
            return
        }
        _state.value = PairingState.Connecting
        viewModelScope.launch {
            // V1: encrypted_initial_state is a small opaque blob; the actual
            // client-side payload (per-sender pairing key + state) lands in
            // M7's multi-device sync layer. For now we send a placeholder
            // so the contract is exercised.
            val placeholder = "syncler-pairing-bootstrap-v1".toByteArray()
            repository.complete(candidate, placeholder).fold(
                onSuccess = { _state.value = PairingState.Success(it) },
                onFailure = { _state.value = PairingState.Error(it.message ?: "Pairing failed") },
            )
        }
    }

    fun reset() {
        _state.value = PairingState.Idle
    }
}

sealed interface PairingState {
    data object Idle : PairingState
    data object Connecting : PairingState
    data class Success(val sender: app.syncler.core.storage.PairedSender) : PairingState
    data class Error(val message: String) : PairingState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onDone: () -> Unit = {},
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var url by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("Pair a sender") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Paste the broker URL the sender displayed. You will be asked to confirm the sender's fingerprint before pairing completes.",
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Broker URL") },
                modifier = Modifier.fillMaxSize().height(112.dp),
            )
            Button(
                onClick = { viewModel.pair(url) },
                enabled = state is PairingState.Idle && url.isNotBlank(),
            ) {
                Text("Pair")
            }
            Spacer(Modifier.height(8.dp))
            when (val s = state) {
                PairingState.Connecting -> Text("Connecting…")
                is PairingState.Error -> Text("Error: ${s.message}")
                is PairingState.Success -> FingerprintConfirmation(s.sender, onDone = {
                    viewModel.reset()
                    onDone()
                })
                PairingState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun FingerprintConfirmation(
    sender: app.syncler.core.storage.PairedSender,
    onDone: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Verify ${sender.senderName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fingerprint:")
                Text(sender.fingerprint)
                Text("Confirm this matches what the sender displayed before tapping OK.")
            }
        },
        confirmButton = { Button(onClick = onDone) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Dismiss") } },
    )
}
