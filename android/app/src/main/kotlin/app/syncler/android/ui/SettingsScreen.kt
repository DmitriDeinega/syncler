package app.syncler.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.syncler.feature.settings.ChangePasswordCard
import app.syncler.feature.settings.PluginPermissionsCard
import app.syncler.feature.settings.RotateMasterKeyCard

/**
 * The Settings tab: device management + account actions (logout). Replaces
 * the previous standalone SettingsDevicesScreen which lived behind a button.
 * Account-level controls (delete account, change password) will move here
 * when M11 finishes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    devicesViewModel: DevicesViewModel,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by devicesViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { devicesViewModel.refresh() }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Text("Devices", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            if (state.loading) {
                Text("Loading…", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
            // Hide revoked devices. They pile up across re-signups during
            // dev (every fresh login enrolls a new device row server-side)
            // and the user has no useful action to take on a revoked one.
            // Server still records them; we just don't surface them.
            val activeDevices = state.devices.filter { it.revokedAt == null }
            val hasOthers = activeDevices.any { it.id != state.currentDeviceId }
            if (hasOthers && state.currentDeviceId != null) {
                OutlinedButton(
                    onClick = { devicesViewModel.revokeAllOthers() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Revoke all other devices") }
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(activeDevices, key = { it.id }) { device ->
                    val isCurrent = device.id == state.currentDeviceId
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                if (isCurrent) "${device.id} — This device" else device.id,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "Created: ${device.createdAt ?: "unknown"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Last seen: ${device.lastSeen ?: "never"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            if (!isCurrent) {
                                Button(onClick = { devicesViewModel.revoke(device.id) }) {
                                    Text("Revoke")
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Account", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            // Phase 8c — `password_rewrap` rotation. Lives in
            // feature/settings so the Hilt ViewModel + RotationRepository
            // dependency edge stays out of :app.
            ChangePasswordCard(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            // Phase 8e — root_hygiene + root_compromise. Compromise
            // flow's onLogout callback wipes the local session after
            // the server-side revoke completes.
            RotateMasterKeyCard(
                modifier = Modifier.fillMaxWidth(),
                onLogout = onLogout,
            )
            Spacer(Modifier.height(12.dp))
            // V2 closeout triad 142 #1 — per-plugin capability
            // grant list with revoke + last-used hint + privacy
            // tip on last-plugin OS perm revoke.
            PluginPermissionsCard(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Log out") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
