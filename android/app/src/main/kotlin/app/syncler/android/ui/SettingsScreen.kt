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
import app.syncler.feature.settings.SecurityCard

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
        // V4 #20 fix: the screen was a Column with a weight(1f)
        // LazyColumn for devices, then a stack of cards beneath it
        // that got pushed off-screen with no way to scroll. Now the
        // whole screen IS the LazyColumn so every section
        // participates in one scroll surface.
        val activeDevices = state.devices.filter { it.revokedAt == null }
        val hasOthers = activeDevices.any { it.id != state.currentDeviceId }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("Devices", style = MaterialTheme.typography.titleLarge)
            }
            state.error?.let { errMsg ->
                item { Text(errMsg, color = MaterialTheme.colorScheme.error) }
            }
            if (state.loading) {
                item { Text("Loading…", style = MaterialTheme.typography.bodySmall) }
            }
            if (hasOthers && state.currentDeviceId != null) {
                item {
                    OutlinedButton(
                        onClick = { devicesViewModel.revokeAllOthers() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Revoke all other devices") }
                }
            }
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
            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Account", style = MaterialTheme.typography.titleLarge)
            }
            // V4 #20 — SensitiveActionGate surface. "Show my user ID"
            // demonstrates the biometric prompt; "Lock sensitive
            // actions" clears the in-memory unlock without wiping the
            // persisted master key (that's the Sign out button below).
            item { SecurityCard(modifier = Modifier.fillMaxWidth()) }
            // Phase 8c — `password_rewrap` rotation.
            item { ChangePasswordCard(modifier = Modifier.fillMaxWidth()) }
            // Phase 8e — root_hygiene + root_compromise.
            item {
                RotateMasterKeyCard(
                    modifier = Modifier.fillMaxWidth(),
                    onLogout = onLogout,
                )
            }
            // V2 closeout triad 142 #1 — per-plugin capability list.
            item { PluginPermissionsCard(modifier = Modifier.fillMaxWidth()) }
            item {
                Button(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Log out") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
