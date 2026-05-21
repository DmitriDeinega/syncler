package app.syncler.feature.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.syncler.core.auth.Session
import app.syncler.core.network.PairingPreviewResponseDto
import app.syncler.core.storage.PairedSender
import app.syncler.core.storage.PairedSenderStore
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: PairingRepository,
    private val session: Session,
    pairedSenderStore: PairedSenderStore,
) : ViewModel() {

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()
    val pairedSenders: StateFlow<List<PairedSender>> = pairedSenderStore.pairedSenders

    fun currentUserId(): String? = session.currentUserId()

    fun startPair(brokerUrl: String) {
        val candidate = repository.parseBrokerUrl(brokerUrl)
        if (candidate == null) {
            _state.value = PairingState.Error("Invalid pairing URL — must be https://… (http:// allowed in debug)")
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
            // Generate a fresh 32-byte AES-256 key. The user copies the hex
            // form to the sender's CLI in V1 dev mode; future incoming
            // messages from this sender will be decrypted with this key.
            val pairingKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            // For V1 the initial state is an opaque placeholder; the server
            // doesn't read it and the bootstrap exchange that would carry the
            // key inside this blob is M11+ work.
            val placeholder = "syncler-pairing-bootstrap-v1".toByteArray()
            repository.confirm(current.candidate, current.preview, pairingKey, placeholder).fold(
                onSuccess = { _state.value = PairingState.Success(it) },
                onFailure = { _state.value = PairingState.Error(it.message ?: "Pairing failed") },
            )
        }
    }

    fun revoke(pairingId: String) {
        viewModelScope.launch { repository.revoke(pairingId) }
    }

    fun cancel() {
        // Local-only cancel: the server token is left un-consumed and will
        // expire on its TTL. No PairedSender was written locally yet.
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
    val pairedSenders by viewModel.pairedSenders.collectAsState()
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            url = contents
            viewModel.startPair(contents)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Pair a sender") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Scan the QR your sender printed, or paste the broker URL. You'll confirm the sender's fingerprint BEFORE pairing is finalized.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val opts = ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                        setPrompt("Aim at the pairing QR")
                    }
                    scanLauncher.launch(opts)
                }) { Text("Scan QR") }
                OutlinedButton(
                    onClick = { viewModel.startPair(url) },
                    enabled = (state is PairingState.Idle || state is PairingState.Error) && url.isNotBlank(),
                ) { Text("Use pasted URL") }
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Broker URL") },
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
            when (val s = state) {
                PairingState.Idle -> Unit
                PairingState.PreviewLoading -> Text("Loading sender preview…")
                is PairingState.PreviewReady -> FingerprintConfirmation(
                    preview = s.preview,
                    onConfirm = viewModel::confirm,
                    onCancel = viewModel::cancel,
                )
                is PairingState.Confirming -> Text("Pairing with ${s.preview.senderName}…")
                is PairingState.Success -> PairingSuccessCard(
                    sender = s.sender,
                    userId = viewModel.currentUserId(),
                    onCopy = { label, value -> copyToClipboard(context, label, value) },
                    onDone = { viewModel.reset(); onDone() },
                )
                is PairingState.Error -> Text("Error: ${s.message}", color = MaterialTheme.colorScheme.error)
            }

            if (pairedSenders.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Text("Paired senders", style = MaterialTheme.typography.titleMedium)
                pairedSenders.forEach { sender ->
                    PairedSenderRow(sender = sender, onRevoke = { viewModel.revoke(sender.pairingId) })
                }
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
                Text(preview.senderPublicKeyFingerprint, fontFamily = FontFamily.Monospace)
                Text(
                    "Confirm this matches what the sender displayed on their side. If it doesn't, tap Cancel — no pairing is created on the server until you confirm.",
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun PairingSuccessCard(
    sender: PairedSender,
    userId: String?,
    onCopy: (label: String, value: String) -> Unit,
    onDone: () -> Unit,
) {
    val pairingKeyHex = remember(sender) { sender.pairingKey.toHex() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Paired with ${sender.senderName}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Dev-mode handoff (V1): paste these into the sender's CLI so it can encrypt messages for you. " +
                    "In V1.5 the bootstrap exchange wires this automatically — this screen goes away.",
                style = MaterialTheme.typography.bodySmall,
            )
            CopyableField(label = "user_id", value = userId ?: "(no session)", onCopy = onCopy)
            CopyableField(label = "pairing_key_hex", value = pairingKeyHex, onCopy = onCopy)
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun CopyableField(
    label: String,
    value: String,
    onCopy: (label: String, value: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                value,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { onCopy(label, value) }) { Text("Copy") }
        }
    }
}

@Composable
private fun PairedSenderRow(sender: PairedSender, onRevoke: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sender.senderName, style = MaterialTheme.typography.titleSmall)
            Text(sender.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { confirming = true }) { Text("Revoke") }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Revoke ${sender.senderName}?") },
            text = { Text("Future messages from this sender will be rejected. You'll need to re-pair to receive again.") },
            confirmButton = {
                Button(onClick = { confirming = false; onRevoke() }) { Text("Revoke") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { b -> "%02x".format(b) }

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
