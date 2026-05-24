package app.syncler.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.syncler.core.storage.MuteStore
import app.syncler.core.storage.PairedSender
import kotlinx.coroutines.launch

/**
 * Phase 3c: Host-owned settings sheet for a plugin.
 * Allows muting (synced/local) and revoking the pairing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginSettingsSheet(
    item: InboxItem,
    pairedSender: PairedSender?,
    muteStore: MuteStore,
    onDismiss: () -> Unit,
    onRevoke: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    
    val syncedMuted = muteStore.isSyncedMuted(item.senderId)
    val localOverride = muteStore.getLocalOverride(item.senderId)
    
    var showAdvanced by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header: Plugin Info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        item.senderName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        item.pluginIdentifier,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    if (pairedSender != null) {
                        Text(
                            "Fingerprint: ${pairedSender.fingerprint}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "Paired: ${pairedSender.firstPairedAt}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            androidx.compose.material3.HorizontalDivider()

            // Mute Synced
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mute everywhere", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Hide cards from this sender on all your synced devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = syncedMuted,
                    onCheckedChange = { checked ->
                        scope.launch {
                            if (checked) muteStore.muteEverywhere(item.senderId)
                            else muteStore.unmuteEverywhere(item.senderId)
                        }
                    }
                )
            }

            // Advanced: Local Override
            Column {
                TextButton(
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier.padding(start = 0.dp)
                ) {
                    Text(if (showAdvanced) "Show less" else "More options...")
                }
                
                if (showAdvanced) {
                    Column(
                        modifier = Modifier.padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Mute on this device", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Override synced setting for this device only.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            val isLocalMuted = localOverride ?: syncedMuted
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (localOverride != null) {
                                    TextButton(onClick = {
                                        scope.launch { muteStore.setLocalOverride(item.senderId, null) }
                                    }) {
                                        Text("Reset")
                                    }
                                }
                                Switch(
                                    checked = isLocalMuted,
                                    onCheckedChange = { checked ->
                                        scope.launch { muteStore.setLocalOverride(item.senderId, checked) }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            androidx.compose.material3.HorizontalDivider()

            // Revoke
            TextButton(
                onClick = onRevoke,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.padding(start = 0.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Revoke pairing")
                }
            }
        }
    }
}
