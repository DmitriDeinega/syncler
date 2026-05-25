package app.syncler.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Phase 8c — drop-in card that surfaces the "Change password" action.
 *
 * The app-level [SettingsScreen] embeds this card alongside its existing
 * Devices section, so the feature module owns the rotation UX without
 * needing its own top-level route.
 */
@Composable
fun ChangePasswordCard(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.changePasswordUiState.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null)
                Spacer(Modifier.height(0.dp).then(Modifier))
                Text(
                    text = "Change password",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Re-wrap your master key under a new password. Your encrypted " +
                    "data stays in place; only the wrapping key + auth salt change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Change password")
            }
        }
    }

    if (showDialog) {
        ChangePasswordDialog(
            uiState = uiState,
            onDismiss = {
                if (uiState !is ChangePasswordUiState.InFlight) {
                    showDialog = false
                    viewModel.acknowledgeChangePasswordResult()
                }
            },
            onSubmit = { current, new -> viewModel.changePassword(current, new) },
            onAcknowledgeResult = {
                showDialog = false
                viewModel.acknowledgeChangePasswordResult()
            },
        )
    }
}

/**
 * Dialog for the "Change password" flow.
 *
 * Surfaces the spec §10.2 MUST warning ("if you forget the new password
 * your account data is unrecoverable") BEFORE the user can submit. The
 * current password is verified locally (unwrap-test) before the proof
 * is sent so a typo doesn't burn the server's failed-proof rate limit.
 */
@Composable
private fun ChangePasswordDialog(
    uiState: ChangePasswordUiState,
    onDismiss: () -> Unit,
    onSubmit: (CharArray, CharArray) -> Unit,
    onAcknowledgeResult: () -> Unit,
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newPasswordConfirm by remember { mutableStateOf("") }
    var warningAcknowledged by remember { mutableStateOf(false) }

    when (uiState) {
        is ChangePasswordUiState.Success -> {
            AlertDialog(
                onDismissRequest = onAcknowledgeResult,
                title = { Text("Password changed") },
                text = {
                    Text(
                        "Your master key has been re-wrapped under the new password. " +
                            "The next login on this and other devices will use the new password.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = onAcknowledgeResult) { Text("OK") }
                },
            )
            return
        }
        is ChangePasswordUiState.Failure -> {
            AlertDialog(
                onDismissRequest = onAcknowledgeResult,
                title = { Text("Couldn't change password") },
                text = { Text(uiState.message) },
                confirmButton = {
                    TextButton(onClick = onAcknowledgeResult) { Text("OK") }
                },
            )
            return
        }
        else -> Unit
    }

    val inFlight = uiState is ChangePasswordUiState.InFlight
    val passwordsMatch = newPassword == newPasswordConfirm && newPassword.isNotBlank()
    val canSubmit = currentPassword.isNotBlank() &&
        passwordsMatch &&
        warningAcknowledged &&
        !inFlight

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password") },
        text = {
            Column {
                Text(
                    text = "⚠ If you forget the new password, your account data is " +
                        "unrecoverable. Syncler cannot reset it for you — your master " +
                        "key only exists encrypted under your password.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = warningAcknowledged,
                        onCheckedChange = { warningAcknowledged = it },
                        enabled = !inFlight,
                    )
                    Text(
                        text = "I have a backup or am willing to lose access",
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    label = { Text("Current password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !inFlight,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("New password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !inFlight,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPasswordConfirm,
                    onValueChange = { newPasswordConfirm = it },
                    label = { Text("Confirm new password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = newPasswordConfirm.isNotBlank() && !passwordsMatch,
                    enabled = !inFlight,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (inFlight) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSubmit,
                onClick = {
                    onSubmit(currentPassword.toCharArray(), newPassword.toCharArray())
                },
            ) {
                Text("Change")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) {
                Text("Cancel")
            }
        },
    )
}
