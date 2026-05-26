package app.syncler.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.syncler.android.pluginhost.PluginRegistry
import app.syncler.android.pluginhost.capabilities.CapabilityGrantStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * V2 closeout triad 142 #1 — Settings card surfacing the
 * stored capability grants with per-grant revoke + last-used
 * hints. The grant dialog promised "revoke in Settings"; this
 * is that screen.
 *
 * Per-plugin pivot (both reviewers OK): grants are grouped by
 * `pluginRowId` with the plugin's display name resolved via
 * `PluginRegistry.get(pluginRowId)?.manifest?.name`. When the
 * plugin isn't currently loaded, falls back to the row id
 * (codex 142 — Settings screen needs to list ALL stored
 * grants, even for unloaded plugins).
 *
 * Last-used hint (both reviewers OK): the row's
 * `lastInvokedAtMs` field is shown as "Last used 2h ago".
 * Omitted if null (plugin never invoked the capability).
 *
 * OS permission interaction (codex NIT, gemini DESIGN):
 * revoking the Syncler grant does NOT auto-revoke the OS-
 * level permission. When the user revokes the last grant for
 * an OS-backed capability, a Privacy Tip line invites them
 * to also disable the OS permission via App Info. We don't
 * launch ACTION_APP_DETAILS_SETTINGS automatically — too
 * jarring for v0.1.
 */

data class PluginPermissionsUi(
    val groups: List<PluginGrantGroup>,
    val loading: Boolean = false,
    val privacyTip: PrivacyTip? = null,
)

data class PluginGrantGroup(
    val pluginRowId: String,
    val displayName: String,
    val grants: List<GrantRow>,
)

data class GrantRow(
    val capability: String,
    val grantedAtMs: Long,
    val lastInvokedAtMs: Long?,
)

data class PrivacyTip(val capability: String, val osPermission: String)

@HiltViewModel
class PluginPermissionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // Triad 143 C4 FIX: resolve via the process-singleton so
    // a revoke here invalidates the cache that loaded bridges
    // also observe. Previously this VM and PluginLoader.android()
    // each held an independent wrapper with its own cache —
    // settings-side revokes were silently invisible to active
    // capability bridges.
    private val grantStore: CapabilityGrantStore by lazy {
        CapabilityGrantStore.shared(context.applicationContext)
    }

    private val _ui = MutableStateFlow(PluginPermissionsUi(groups = emptyList(), loading = true))
    val ui: StateFlow<PluginPermissionsUi> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val rows = grantStore.allGrants()
                val grouped = rows.groupBy { it.pluginRowId }
                val groups = grouped.entries.map { (rowId, list) ->
                    val displayName = PluginRegistry.get(rowId)?.manifest?.name
                        ?: PluginRegistry.get(rowId)?.manifest?.id
                        ?: rowId
                    PluginGrantGroup(
                        pluginRowId = rowId,
                        displayName = displayName,
                        grants = list.map {
                            GrantRow(
                                capability = it.capability,
                                grantedAtMs = it.grantedAtMs,
                                lastInvokedAtMs = it.lastInvokedAtMs,
                            )
                        }.sortedBy { it.capability },
                    )
                }.sortedBy { it.displayName.lowercase() }
                _ui.value = PluginPermissionsUi(groups = groups, loading = false)
            }.onFailure {
                Timber.tag(TAG).w(it, "loading capability grants failed")
                _ui.value = PluginPermissionsUi(groups = emptyList(), loading = false)
            }
        }
    }

    fun revoke(pluginRowId: String, capability: String) {
        viewModelScope.launch {
            runCatching {
                grantStore.revoke(pluginRowId, capability)
                // After revoke, check if any other plugin still
                // holds the same OS-backed capability. If not,
                // surface a Privacy Tip pointing the user at OS
                // settings (NOT auto-launch).
                val osPerm = osPermissionFor(capability)
                if (osPerm != null) {
                    val stillUsed = grantStore.allGrants().any { it.capability == capability }
                    if (!stillUsed) {
                        _ui.value = _ui.value.copy(
                            privacyTip = PrivacyTip(capability, osPerm),
                        )
                    }
                }
                refresh()
            }.onFailure {
                Timber.tag(TAG).w(it, "revoke failed (%s, %s)", pluginRowId, capability)
            }
        }
    }

    fun dismissPrivacyTip() {
        _ui.value = _ui.value.copy(privacyTip = null)
    }

    private fun osPermissionFor(capability: String): String? = when (capability) {
        "camera" -> "android.permission.CAMERA"
        "location.coarse" -> "android.permission.ACCESS_COARSE_LOCATION"
        "location.fine" -> "android.permission.ACCESS_FINE_LOCATION"
        else -> null
    }

    companion object {
        private const val TAG = "PluginPermissionsVM"
    }
}

/**
 * Drop-in Settings card. Hosts embed this alongside the
 * existing Account / Devices sections.
 */
@Composable
fun PluginPermissionsCard(
    modifier: Modifier = Modifier,
    viewModel: PluginPermissionsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Plugin permissions",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Capabilities your plugins have asked for. Revoke any " +
                    "to require a fresh prompt next time the plugin " +
                    "needs that capability.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            if (ui.loading) {
                Text("Loading…", style = MaterialTheme.typography.bodySmall)
                return@Card
            }
            if (ui.groups.isEmpty()) {
                Text(
                    "No plugin capability grants stored.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Card
            }

            ui.groups.forEach { group ->
                Text(
                    group.displayName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                group.grants.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.padding(end = 8.dp)) {
                            Text(
                                row.capability,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val hint = row.lastInvokedAtMs?.let { formatRelativeMs(it) }
                            if (hint != null) {
                                Text(
                                    "Last used $hint",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                viewModel.revoke(group.pluginRowId, row.capability)
                            },
                        ) { Text("Revoke") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            val tip = ui.privacyTip
            if (tip != null) {
                Spacer(Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Privacy tip",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "No plugins are using ${tip.capability} anymore. " +
                                "You can also disable Syncler's " +
                                "${friendlyOsName(tip.osPermission)} access in App " +
                                "Info if you don't need it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.dismissPrivacyTip() }) {
                            Text("Got it")
                        }
                    }
                }
            }
        }
    }
}

private fun friendlyOsName(permission: String): String = when (permission) {
    "android.permission.CAMERA" -> "camera"
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION" -> "location"
    else -> permission.substringAfterLast('.').lowercase()
}

/** Returns "5m ago" / "2h ago" / "3d ago" / "" for null. */
private fun formatRelativeMs(epochMs: Long): String {
    val deltaMs = System.currentTimeMillis() - epochMs
    if (deltaMs < 0) return "just now"
    val seconds = deltaMs / 1000
    if (seconds < 60) return "just now"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m ago"
    val hours = minutes / 60
    if (hours < 24) return "${hours}h ago"
    val days = hours / 24
    if (days < 30) return "${days}d ago"
    return "${days / 30}mo ago"
}
