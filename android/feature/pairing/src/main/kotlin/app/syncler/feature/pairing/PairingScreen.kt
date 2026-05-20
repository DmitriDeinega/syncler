package app.syncler.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import app.syncler.core.network.PairingPreviewResponseDto
import app.syncler.core.storage.PairedSender
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: PairingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    fun startPair(brokerUrl: String) {
        val candidate = repository.parseBrokerUrl(brokerUrl)
        if (candidate == null) {
            _state.value = PairingState.Error("Invalid pairing URL — must be https://…?token=…")
            return
        }
        _state.value = PairingState.PreviewLoading
        viewModelScope.launch {
            repository.preview(candidate).fold(
                onSuccess = { _state.value = PairingState.PreviewReady(candidate, it) },
                onFailure = { _state.value = PairingState.Error(it.message ?: "Failed to load preview") },
            )
        }
    }

    fun confirm() {
        val current = _state.value as? PairingState.PreviewReady ?: return
        _state.value = PairingState.Confirming(current.preview)
        viewModelScope.launch {
            // M7 wires the real per-sender pairing-key bootstrap. For now we
            // send an opaque placeholder; server treats it as encrypted state.
            val placeholder = "syncler-pairing-bootstrap-v1".toByteArray()
            repository.confirm(current.candidate, current.preview, placeholder).fold(
                onSuccess = { _state.value = PairingState.Success(it) },
                onFailure = { _state.value = PairingState.Error(it.message ?: "Pairing failed") },
            )
        }
    }

    fun cancel() {
        // Local-only cancel: the server token is left un-consumed and will
        // expire on its TTL. No PairedSender was written locally yet, so
        // there's nothing to clean up.
        _state.value = PairingState.Idle
    }

    fun reset() {
        _state.value = PairingState.Idle
    }
}

sealed interface PairingState {
    data object Idle : PairingState
    data object PreviewLoading : PairingState
    data class PreviewReady(
        val candidate: PairingCandidate,
        val preview: PairingPreviewResponseDto,
    ) : PairingState
    data class Confirming(val preview: PairingPreviewResponseDto) : PairingState
    data class Success(val sender: PairedSender) : PairingState
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
                "Paste the broker URL the sender displayed. You will confirm the sender's fingerprint BEFORE pairing is finalized.",
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Broker URL") },
                modifier = Modifier.fillMaxSize().height(112.dp),
            )
            Button(
                onClick = { viewModel.startPair(url) },
                enabled = (state is PairingState.Idle || state is PairingState.Error) && url.isNotBlank(),
            ) {
                Text("Preview")
            }
            Spacer(Modifier.height(8.dp))
            when (val s = state) {
                PairingState.Idle -> Unit
                PairingState.PreviewLoading -> Text("Loading sender preview…")
                is PairingState.PreviewReady -> FingerprintConfirmation(
                    preview = s.preview,
                    onConfirm = viewModel::confirm,
                    onCancel = viewModel::cancel,
                )
                is PairingState.Confirming -> Text("Pairing with ${s.preview.senderName}…")
                is PairingState.Success -> {
                    Text("Paired with ${s.sender.senderName} ✓")
                    Button(onClick = { viewModel.reset(); onDone() }) { Text("Done") }
                }
                is PairingState.Error -> Text("Error: ${s.message}")
            }
        }
    }
}

@Composable
private fun FingerprintConfirmation(
    preview: PairingPreviewResponseDto,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Verify ${preview.senderName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fingerprint:")
                Text(preview.senderPublicKeyFingerprint)
                Text(
                    "Confirm this matches what the sender displayed on their side. If it doesn't, tap Cancel — no pairing is created on the server until you confirm.",
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
