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
 * Phase 8e — drop-in card that surfaces the two root_* rotation
 * actions. Hygiene = new MK under same password; Compromise = new MK
 * + new password + force logout.
 */
@Composable
fun RotateMasterKeyCard(
    modifier: Modifier = Modifier,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.rotateMkUiState.collectAsState()
    var openMode by remember { mutableStateOf<RotateMode?>(null) }

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null)
                Text(
                    text = "Rotate master key",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Generate a fresh master key and re-encrypt every blob " +
                    "stored on the server. Use hygiene rotation periodically. " +
                    "Use compromise rotation if you suspect this account was " +
                    "broken into — it also changes your password and signs you " +
                    "out of every device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { openMode = RotateMode.HYGIENE },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Rotate master key (hygiene)")
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = { openMode = RotateMode.COMPROMISE },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Rotate after compromise")
            }
        }
    }

    openMode?.let { mode ->
        RotateMasterKeyDialog(
            mode = mode,
            uiState = uiState,
            onDismiss = {
                if (uiState !is RotateMkUiState.InFlight) {
                    openMode = null
                    viewModel.acknowledgeRotateMkResult()
                }
            },
            onSubmit = { current, new ->
                when (mode) {
                    RotateMode.HYGIENE -> viewModel.rotateHygiene(current)
                    RotateMode.COMPROMISE -> viewModel.rotateCompromise(current, new!!)
                }
            },
            onAcknowledgeResult = {
                val state = uiState
                openMode = null
                viewModel.acknowledgeRotateMkResult()
                if (state is RotateMkUiState.Success && state.forceLogout) {
                    onLogout()
                }
            },
        )
    }
}

private enum class RotateMode { HYGIENE, COMPROMISE }

@Composable
private fun RotateMasterKeyDialog(
    mode: RotateMode,
    uiState: RotateMkUiState,
    onDismiss: () -> Unit,
    onSubmit: (CharArray, CharArray?) -> Unit,
    onAcknowledgeResult: () -> Unit,
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newPasswordConfirm by remember { mutableStateOf("") }
    var warningAcknowledged by remember { mutableStateOf(false) }

    when (uiState) {
        is RotateMkUiState.Success -> {
            AlertDialog(
                onDismissRequest = onAcknowledgeResult,
                title = { Text("Master key rotated") },
                text = {
                    Text(
                        "New key generation: ${uiState.newKeyGeneration}. " +
                            "${uiState.pairingsRotated} pairing(s) re-encrypted." +
                            if (uiState.forceLogout) {
                                " All sessions have been revoked — you will be " +
                                    "signed out now."
                            } else {
                                " All your devices stay signed in and will " +
                                    "decrypt under the new key."
                            },
                    )
                },
                confirmButton = {
                    TextButton(onClick = onAcknowledgeResult) {
                        Text(if (uiState.forceLogout) "Sign out" else "OK")
                    }
                },
            )
            return
        }
        is RotateMkUiState.Failure -> {
            AlertDialog(
                onDismissRequest = onAcknowledgeResult,
                title = { Text("Couldn't rotate master key") },
                text = { Text(uiState.message) },
                confirmButton = {
                    TextButton(onClick = onAcknowledgeResult) { Text("OK") }
                },
            )
            return
        }
        else -> Unit
    }

    val inFlight = uiState is RotateMkUiState.InFlight
    val passwordsMatch = newPassword == newPasswordConfirm && newPassword.isNotBlank()
    val canSubmit = currentPassword.isNotBlank() &&
        warningAcknowledged &&
        (mode == RotateMode.HYGIENE || passwordsMatch) &&
        !inFlight

    val title = when (mode) {
        RotateMode.HYGIENE -> "Rotate master key"
        RotateMode.COMPROMISE -> "Rotate after compromise"
    }
    val warning = when (mode) {
        RotateMode.HYGIENE ->
            "⚠ Every encrypted blob on the server will be re-encrypted under a " +
                "new master key. If the rotation fails mid-way you may have to " +
                "log out and back in. Your password stays the same."
        RotateMode.COMPROMISE ->
            "⚠ This will generate a new master key AND change your password AND " +
                "sign you out of every device, including this one. If you " +
                "forget the new password your account data is unrecoverable."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(warning, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = warningAcknowledged,
                        onCheckedChange = { warningAcknowledged = it },
                        enabled = !inFlight,
                    )
                    Text(
                        text = "I have a backup or am willing to lose access",
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
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
                if (mode == RotateMode.COMPROMISE) {
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
                }
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
                    val currentChars = currentPassword.toCharArray()
                    val newChars = if (mode == RotateMode.COMPROMISE) {
                        newPassword.toCharArray()
                    } else null
                    onSubmit(currentChars, newChars)
                },
            ) {
                Text("Rotate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) {
                Text("Cancel")
            }
        },
    )
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
