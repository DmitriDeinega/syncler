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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.fragment.app.FragmentActivity

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: PairingRepository,
    private val session: Session,
    pairedSenderStore: PairedSenderStore,
    private val muteStore: app.syncler.core.storage.MuteStore,
    /**
     * V4 #20 triad 167 must-fix: the post-pairing "success card"
     * exposed user_id + pairing_key_hex in plaintext as soon as the
     * pairing completed. Codex called this out as a sensitive
     * platform reveal we agreed to gate in 166 but missed in 167.
     * The screen now hides both values behind a biometric prompt.
     */
    val sensitiveActionGate: app.syncler.core.auth.SensitiveActionGate,
    val biometricUnlocker: app.syncler.core.auth.BiometricUnlocker,
) : ViewModel() {

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()
    val pairedSenders: StateFlow<List<PairedSender>> = pairedSenderStore.pairedSenders
    val mutedSenderIds: StateFlow<Set<String>> = muteStore.mutedSenderIds

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

        // V1.5: classify automated metadata BEFORE /complete.
        // HardError indicates substitution-attack risk: refuse to
        // pair at all (do NOT silently fall back to manual).
        val classification = repository.classifyBootstrap(current.preview)
        if (classification is BootstrapClassification.HardError) {
            _state.value = PairingState.BootstrapHardError(
                preview = current.preview,
                message = classification.message,
            )
            return
        }

        // If automated metadata is well-formed, verify the
        // bootstrap_key signature against the sender's Ed25519 pub
        // key. If verify fails: HARD error (same posture as malformed
        // metadata — could be syncler-server substitution).
        if (classification is BootstrapClassification.Automated) {
            // The senderPublicKey field comes from /preview as a
            // base64 string; the upstream /complete identity-match
            // assertion guarantees it survives unchanged through
            // confirmation. Decode defensively so a malformed value
            // becomes a HardError state, not a crash (Codex 89 RED).
            val senderEdPub = runCatching {
                android.util.Base64.decode(current.preview.senderPublicKey, android.util.Base64.NO_WRAP)
            }.getOrNull()
            if (senderEdPub == null || senderEdPub.size != 32) {
                _state.value = PairingState.BootstrapHardError(
                    preview = current.preview,
                    message = "preview senderPublicKey is not a valid 32-byte base64 Ed25519 key",
                )
                return
            }
            val verified = repository.verifyBootstrapKeySignature(
                senderPublicKeyEd25519Raw = senderEdPub,
                bootstrapKeyRaw = classification.bootstrapKeyRaw,
                bootstrapKeySignatureRaw = classification.bootstrapKeySignatureRaw,
            )
            if (!verified) {
                _state.value = PairingState.BootstrapHardError(
                    preview = current.preview,
                    message = "bootstrap_key_signature verification failed — refusing automated pairing",
                )
                return
            }
        }

        _state.value = PairingState.Confirming(current.preview)
        viewModelScope.launch {
            val pairingKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val placeholder = "syncler-pairing-bootstrap-v1".toByteArray()
            val confirmResult = repository.confirm(
                current.candidate, current.preview, pairingKey, placeholder,
            )
            confirmResult.fold(
                onSuccess = { sender ->
                    // Local PairedSender is persisted at this point regardless
                    // of what happens with the broker POST below (Codex 87 RED).
                    if (classification is BootstrapClassification.Automated) {
                        val userId = session.currentUserId()
                        if (userId == null) {
                            // Shouldn't happen — user must be signed in to pair.
                            _state.value = PairingState.BootstrapFailedFallback(
                                sender = sender,
                                reason = "current user_id is null; cannot build bootstrap envelope",
                            )
                            return@fold
                        }
                        _state.value = PairingState.BootstrapPosting(sender)
                        val envelope = repository.buildEnvelopeDto(
                            automated = classification,
                            pairingId = sender.pairingId,
                            senderId = sender.senderId,
                            userId = userId,
                            pairingKey = pairingKey,
                        )
                        val postResult = repository.postBootstrapEnvelope(
                            senderBrokerUrl = classification.senderBrokerUrl,
                            envelope = envelope,
                        )
                        _state.value = postResult.fold(
                            onSuccess = { PairingState.BootstrapSucceeded(sender) },
                            onFailure = { err ->
                                PairingState.BootstrapFailedFallback(
                                    sender = sender,
                                    reason = err.message ?: err.javaClass.simpleName,
                                )
                            },
                        )
                    } else {
                        _state.value = PairingState.Success(sender)
                    }
                },
                onFailure = { _state.value = PairingState.Error(it.message ?: "Pairing failed") },
            )
        }
    }

    fun revoke(pairingId: String) {
        viewModelScope.launch { repository.revoke(pairingId) }
    }

    fun reset() {
        _state.value = PairingState.Idle
    }

    fun cancel() {
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
    /** V1 manual flow success — show the user_id + pairing_key_hex to copy. */
    data class Success(val sender: PairedSender) : PairingState
    /** V1.5 automated path: posting envelope to sender broker. */
    data class BootstrapPosting(val sender: PairedSender) : PairingState
    /** V1.5 automated path: broker accepted the envelope. Pairing complete on both sides. */
    data class BootstrapSucceeded(val sender: PairedSender) : PairingState
    /** V1.5 automated path: broker POST failed; show fallback manual copy UI. */
    data class BootstrapFailedFallback(val sender: PairedSender, val reason: String) : PairingState
    /**
     * V1.5 automated path: metadata malformed or signature invalid.
     * Hard refusal — pairing was NOT finalized. User cannot fall back
     * to manual either, because incomplete metadata is a substitution-
     * attack indicator.
     */
    data class BootstrapHardError(val preview: PairingPreviewResponseDto, val message: String) : PairingState
    data class Error(val message: String) : PairingState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onDone: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val pairedSenders by viewModel.pairedSenders.collectAsState()
    val mutedSenderIds by viewModel.mutedSenderIds.collectAsState()
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            url = contents
            viewModel.startPair(contents)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Senders") }) },
    ) { padding ->
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
                    viewModel = viewModel,
                )
                is PairingState.BootstrapPosting -> Text("Notifying ${s.sender.senderName}…")
                is PairingState.BootstrapSucceeded -> BootstrapSuccessCard(
                    sender = s.sender,
                    onDone = { viewModel.reset(); onDone() },
                )
                is PairingState.BootstrapFailedFallback -> {
                    Text(
                        "Automatic pairing failed (${s.reason}). Pairing is still complete on this device — copy the values below into your sender to finish the catch-up.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    PairingSuccessCard(
                        sender = s.sender,
                        userId = viewModel.currentUserId(),
                        onCopy = { label, value -> copyToClipboard(context, label, value) },
                        onDone = { viewModel.reset(); onDone() },
                        viewModel = viewModel,
                    )
                }
                is PairingState.BootstrapHardError -> Text(
                    "Refusing to pair: ${s.message}. The sender's automated pairing metadata is malformed or has an invalid signature; pairing was NOT created.",
                    color = MaterialTheme.colorScheme.error,
                )
                is PairingState.Error -> Text("Error: ${s.message}", color = MaterialTheme.colorScheme.error)
            }

            if (pairedSenders.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Text("Paired senders", style = MaterialTheme.typography.titleMedium)
                pairedSenders.forEach { sender ->
                    PairedSenderRow(
                        sender = sender,
                        isMuted = sender.senderId in mutedSenderIds,
                        onRevoke = { viewModel.revoke(sender.pairingId) }
                    )
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
private fun BootstrapSuccessCard(
    sender: PairedSender,
    onDone: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Paired automatically with ${sender.senderName}", style = MaterialTheme.typography.titleMedium)
            Text(
                "The sender's broker received the bootstrap envelope and now has the pairing key. No manual copy required.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun PairingSuccessCard(
    sender: PairedSender,
    userId: String?,
    onCopy: (label: String, value: String) -> Unit,
    onDone: () -> Unit,
    viewModel: PairingViewModel,
) {
    val pairingKeyHex = remember(sender) { sender.pairingKey.toHex() }
    // V4 #20 triad 167 must-fix: gate the user_id + pairing_key_hex
    // reveals behind a biometric prompt. Previously these rendered
    // as plaintext as soon as pairing completed — that's a
    // sensitive surface that should consult SensitiveActionGate.
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val gateState by viewModel.sensitiveActionGate.isUnlockedFlow.collectAsState()
    val unlocked = gateState != null && viewModel.sensitiveActionGate.isUnlocked()
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
            if (unlocked) {
                CopyableField(label = "user_id", value = userId ?: "(no session)", onCopy = onCopy)
                CopyableField(label = "pairing_key_hex", value = pairingKeyHex, onCopy = onCopy)
            } else {
                Text(
                    text = "user_id and pairing_key_hex are sensitive. Confirm it's you to reveal them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        val act = activity ?: return@OutlinedButton
                        scope.launch {
                            viewModel.biometricUnlocker.promptForUnlock(
                                activity = act,
                                title = "Reveal pairing secrets",
                                subtitle = "Confirm it's you to view user_id and pairing key",
                            )
                            // Successful unlock flips the gate; the
                            // Composable re-collects gateState and
                            // shows the CopyableFields.
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reveal pairing secrets") }
            }
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
private fun PairedSenderRow(
    sender: PairedSender,
    isMuted: Boolean,
    onRevoke: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(sender.senderName, style = MaterialTheme.typography.titleSmall)
                if (isMuted) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Filled.NotificationsOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Muted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
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
