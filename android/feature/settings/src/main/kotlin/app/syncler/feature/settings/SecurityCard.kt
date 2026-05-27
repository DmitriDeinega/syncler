package app.syncler.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.syncler.core.auth.BiometricUnlocker
import app.syncler.core.auth.SensitiveActionGate
import app.syncler.core.auth.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * V4 #20 — surfaces the SensitiveActionGate to the user.
 *
 * Two affordances per triad 166 (codex + gemini both demanded the
 * separation):
 *
 * - "Lock sensitive actions" — clears [SensitiveActionGate] only.
 *   Next sensitive action re-prompts; master key + JWT survive.
 * - "Show my user ID" — proof surface. Demonstrates the biometric
 *   prompt + gate-unlock cycle on a real sensitive surface. The
 *   user_id reveal is itself behind the gate.
 *
 * Sign out (which wipes the persisted master key) stays on the
 * main SettingsScreen as a separate button. Conflating the two
 * was the exact ambiguity both reviewers warned against.
 */
@HiltViewModel
class SecurityCardViewModel @Inject constructor(
    val session: Session,
    val gate: SensitiveActionGate,
    val unlocker: BiometricUnlocker,
) : ViewModel() {
    private val _revealedUserId = MutableStateFlow<String?>(null)
    val revealedUserId: StateFlow<String?> = _revealedUserId.asStateFlow()

    fun revealUserId() {
        _revealedUserId.value = session.currentUserId()
    }

    fun hideUserId() {
        _revealedUserId.value = null
    }
}

@Composable
fun SecurityCard(
    modifier: Modifier = Modifier,
    viewModel: SecurityCardViewModel = hiltViewModel(),
) {
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val revealedUserId by viewModel.revealedUserId.collectAsState()
    val gateUnlockedAt by viewModel.gate.isUnlockedFlow.collectAsState()
    var pendingError by remember { mutableStateOf<String?>(null) }

    // Re-locking the gate from elsewhere (e.g. Lock Now button)
    // should also hide any user_id surface that was being shown.
    LaunchedEffect(gateUnlockedAt) {
        if (gateUnlockedAt == null) viewModel.hideUserId()
    }

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(16.dp)) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null)
                Text(
                    text = "Security",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your account stays signed in across app restarts. " +
                    "Sensitive actions (revealing identifiers, opening " +
                    "plugins marked sensitive) still require your fingerprint, " +
                    "face, or device PIN.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // Show / Hide user_id.
            val currentlyRevealed = revealedUserId
            if (currentlyRevealed != null) {
                Text(
                    text = "User ID:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = currentlyRevealed,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.hideUserId() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Hide user ID") }
            } else {
                OutlinedButton(
                    onClick = {
                        val act = activity ?: run {
                            pendingError = "internal: missing host activity"
                            return@OutlinedButton
                        }
                        if (viewModel.gate.isUnlocked()) {
                            viewModel.revealUserId()
                            viewModel.gate.touchFromForegroundSensitiveView()
                        } else {
                            scope.launch {
                                when (
                                    val result = viewModel.unlocker.promptForUnlock(
                                        activity = act,
                                        title = "Confirm it's you",
                                        subtitle = "Reveal your user ID",
                                    )
                                ) {
                                    BiometricUnlocker.Result.Success ->
                                        viewModel.revealUserId()
                                    BiometricUnlocker.Result.Cancelled -> Unit
                                    is BiometricUnlocker.Result.Failed ->
                                        pendingError = result.message
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Show my user ID") }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.gate.lockNow() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Lock sensitive actions") }

            pendingError?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
