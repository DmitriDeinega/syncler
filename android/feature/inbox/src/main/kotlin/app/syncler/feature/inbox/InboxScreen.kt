package app.syncler.feature.inbox

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repository: InboxRepository,
    private val userState: UserStateRepository,
) : ViewModel() {

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    val items: StateFlow<List<InboxItem>> = repository.items

    // Derived: set of message IDs already read on any of the user's devices.
    // Collected from UserStateRepository.state and exposed as a snapshot the
    // UI can pick from directly.
    val readMessageIds: StateFlow<Set<String>> = userState.state
        .map { st -> st.readMessages.map { it.messageId }.toSet() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = userState.state.value.readMessages.map { it.messageId }.toSet(),
        )

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    init {
        // Pull the latest cross-device state at startup so the inbox shows
        // read marks set on the user's other devices. No-ops if locked.
        viewModelScope.launch { userState.pull() }
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            repository.refresh()
            // Opportunistically pull state — cheap, and catches read marks
            // pushed from another device between polls.
            userState.pull()
            _refreshing.value = false
        }
    }

    fun open(itemId: String) {
        _selectedId.value = itemId
        // Mark-read trigger: opening the detail view (the conservative trigger
        // both Codex and Gemini argued for in review 35 — scanning the list
        // alone should not punish unread state).
        viewModelScope.launch { userState.markRead(itemId) }
    }
    fun closeDetail() { _selectedId.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onLogout: () -> Unit,
    onManageDevices: () -> Unit,
    onPairSender: () -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val readMessageIds by viewModel.readMessageIds.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            delay(POLL_INTERVAL_MS)
        }
    }

    val selectedItem = selectedId?.let { id -> items.firstOrNull { it.id == id } }
    if (selectedItem != null) {
        DetailScreen(item = selectedItem, onBack = viewModel::closeDetail)
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Inbox") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPairSender) { Text("Pair sender") }
                OutlinedButton(onClick = onManageDevices) { Text("Devices") }
                OutlinedButton(onClick = { viewModel.refresh() }) { Text("Refresh") }
                if (refreshing) CircularProgressIndicator(Modifier.height(20.dp))
                OutlinedButton(onClick = onLogout) { Text("Log out") }
            }
            Spacer(Modifier.height(16.dp))

            if (items.isEmpty()) {
                Text(
                    "No cards yet. Pair a sender and they can push to you.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.id }) { item ->
                        InboxRow(
                            item = item,
                            isRead = item.id in readMessageIds,
                            onClick = { viewModel.open(item.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Native Compose row — no WebView. The plugin's `render()` runs only in
 * [DetailScreen] when the user taps a row. Senders fill in `hostPreview` via
 * the sdk-python / sdk-plugin contract; rows without it use the generic
 * fallback.
 *
 * Unread state (M11.2): a small dot + bold title until the user opens the
 * detail view. Read state syncs across devices via the M7 CAS user-state
 * blob — opening on one phone marks the row read on every device on the
 * next pull.
 */
@Composable
private fun InboxRow(item: InboxItem, isRead: Boolean, onClick: () -> Unit) {
    val preview = item.hostPreview
    val titleStyle = if (isRead) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Unread dot: leading affordance, reserves space when read so the
            // row geometry stays stable across the state transition.
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(UNREAD_DOT_SIZE)
                    .then(
                        if (isRead) Modifier
                        else Modifier.background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        )
                    ),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(item.sentAt, style = MaterialTheme.typography.labelSmall)
                }
                if (preview != null) {
                    Text(
                        preview.title,
                        style = titleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    preview.subtitle?.let { sub ->
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    preview.summary?.let { sum ->
                        Text(
                            sum,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        "New message from ${item.senderName}",
                        style = titleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Open to view",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(item: InboxItem, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            item.hostPreview?.title ?: "Message",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            item.senderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                item.bundleJs != null -> PluginRenderView(
                    bundleJs = item.bundleJs,
                    payloadJson = item.payloadJson,
                    declaredEndpoints = item.declaredEndpoints,
                )
                else -> Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Plugin not loaded",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Couldn't fetch or verify the sender's plugin bundle. " +
                            "Raw decrypted payload shown below for diagnostics.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PayloadFallbackView(item.payloadJson)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun PluginRenderView(
    bundleJs: String,
    payloadJson: String,
    declaredEndpoints: List<String>,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                with(settings) {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    domStorageEnabled = false
                    databaseEnabled = false
                }
                setBackgroundColor(0x00000000)
                val bridge = CardBridge(webView = this, declaredEndpoints = declaredEndpoints)
                addJavascriptInterface(bridge, CardBridge.NATIVE_BRIDGE_NAME)
                tag = bridge
                val html = RenderShell.build(bundleJs, payloadJson)
                loadDataWithBaseURL("https://syncler-plugin-host/inbox", html, "text/html", "utf-8", null)
            }
        },
        onRelease = { webView ->
            (webView.tag as? CardBridge)?.destroy()
            webView.removeJavascriptInterface(CardBridge.NATIVE_BRIDGE_NAME)
            webView.stopLoading()
            webView.destroy()
        },
    )
}

@Composable
private fun PayloadFallbackView(payloadJson: String) {
    val parsed = runCatching { JSONObject(payloadJson) }.getOrNull()
    if (parsed == null) {
        Text(payloadJson, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        parsed.keys().asSequence().forEach { key ->
            Row(verticalAlignment = Alignment.Top) {
                Text("$key: ", style = MaterialTheme.typography.labelMedium)
                Text(
                    formatValue(parsed.get(key)),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun formatValue(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONArray -> value.toString()
    is JSONObject -> value.toString(2)
    else -> value.toString()
}

private const val POLL_INTERVAL_MS = 15_000L
private val UNREAD_DOT_SIZE = 8.dp
