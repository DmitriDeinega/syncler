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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

    /**
     * Host-side metadata search (M11.5). Substring match against
     * hostPreview.{title,subtitle,summary,searchText} + sender name. Lives
     * entirely client-side — the server is content-blind. Plugin-scoped
     * content search is V1.5 (per the consultation 35 federated-search plan).
     */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    fun setSearch(q: String) { _searchQuery.value = q }
    fun clearSearch() { _searchQuery.value = "" }

    init {
        // Pull cross-device state, then retry any push that failed previously
        // (network blip, 409 conflict that couldn't resolve in one retry,
        // session was locked when the mark was issued, etc.). No-ops if
        // currently locked or if no dirty changes are pending.
        viewModelScope.launch {
            userState.pull()
            userState.flushPendingPush()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            repository.refresh()
            // Opportunistically pull state — cheap, catches read marks
            // pushed from another device between polls — and flush any
            // dirty local change that hasn't been pushed yet.
            userState.pull()
            userState.flushPendingPush()
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

    val archivedMessageIds: StateFlow<Set<String>> = userState.state
        .map { st -> st.archivedMessages.map { it.messageId }.toSet() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = userState.state.value.archivedMessages.map { it.messageId }.toSet(),
        )

    fun archive(itemId: String) {
        viewModelScope.launch { userState.markArchived(itemId) }
    }

    fun unarchive(itemId: String) {
        viewModelScope.launch { userState.markUnarchived(itemId) }
    }

    private val _showArchive = MutableStateFlow(false)
    val showArchive: StateFlow<Boolean> = _showArchive.asStateFlow()
    fun openArchive() { _showArchive.value = true }
    fun closeArchive() { _showArchive.value = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    modifier: Modifier = Modifier,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val readMessageIds by viewModel.readMessageIds.collectAsState()
    val archivedMessageIds by viewModel.archivedMessageIds.collectAsState()
    val showArchive by viewModel.showArchive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var searchActive by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            delay(POLL_INTERVAL_MS)
        }
    }

    // Sub-screens stack on top of the inbox: detail view (per item) and
    // archive list. They handle their own back navigation and return here.
    // We forward `modifier` (which carries the outer Scaffold's bottom-bar
    // inset) so sub-screen content doesn't draw under the bottom nav.
    val selectedItem = selectedId?.let { id -> items.firstOrNull { it.id == id } }
    if (selectedItem != null) {
        DetailScreen(
            item = selectedItem,
            onBack = viewModel::closeDetail,
            isArchived = selectedItem.id in archivedMessageIds,
            onArchive = { viewModel.archive(selectedItem.id) },
            onUnarchive = { viewModel.unarchive(selectedItem.id) },
            modifier = modifier,
        )
        return
    }
    if (showArchive) {
        ArchiveScreen(
            items = items.filter { it.id in archivedMessageIds },
            readMessageIds = readMessageIds,
            onBack = viewModel::closeArchive,
            onItemClick = { id -> viewModel.open(id) },
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (searchActive) {
                InboxSearchAppBar(
                    query = searchQuery,
                    onQueryChange = viewModel::setSearch,
                    onClose = {
                        viewModel.clearSearch()
                        searchActive = false
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Inbox") },
                    actions = {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp).padding(horizontal = 12.dp),
                            )
                        }
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = viewModel::openArchive) {
                            Icon(Icons.Filled.Archive, contentDescription = "Archive")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            // Active inbox = items minus archived. Search filters this set
            // by matching the trimmed query against title/subtitle/summary/
            // searchText/senderName (case-insensitive substring match — host
            // metadata only; plugin-scoped content search is V1.5).
            val activeItems = items.filter { it.id !in archivedMessageIds }
            val filtered = remember(activeItems, searchQuery) {
                if (searchQuery.isBlank()) activeItems else activeItems.filter { matchesQuery(it, searchQuery) }
            }
            when {
                activeItems.isEmpty() -> Text(
                    "No cards yet. Pair a sender from the Senders tab and they can push to you.",
                    modifier = Modifier.padding(top = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                searchQuery.isNotBlank() && filtered.isEmpty() -> Text(
                    "No cards match \"$searchQuery\".",
                    modifier = Modifier.padding(top = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> BucketedInboxList(
                    items = filtered,
                    readMessageIds = readMessageIds,
                    onItemClick = viewModel::open,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxSearchAppBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    TopAppBar(
        title = {
            androidx.compose.material3.TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search inbox") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        },
    )
}

/**
 * Case-insensitive substring match across visible row fields plus the
 * `hostPreview.searchText` tokens the sender pre-indexed. Plugin author
 * promised in §2 of the integration guide that searchText would be
 * folded into host search — this is where that promise gets paid.
 */
internal fun matchesQuery(item: InboxItem, rawQuery: String): Boolean {
    val q = rawQuery.trim().lowercase()
    if (q.isEmpty()) return true
    if (item.senderName.lowercase().contains(q)) return true
    item.hostPreview?.let { preview ->
        if (preview.title.lowercase().contains(q)) return true
        if (preview.subtitle?.lowercase()?.contains(q) == true) return true
        if (preview.summary?.lowercase()?.contains(q) == true) return true
        if (preview.searchText.any { it.lowercase().contains(q) }) return true
    }
    return false
}

/**
 * Groups inbox items into Today / Yesterday / Earlier buckets and renders
 * them with sticky-ish section headers. Bucket boundaries are computed
 * against the device's current local date (the row timestamp is the server's
 * `sent_at`, which is UTC; we convert per-row to local for bucketing).
 */
@Composable
private fun BucketedInboxList(
    items: List<InboxItem>,
    readMessageIds: Set<String>,
    onItemClick: (String) -> Unit,
) {
    val today = LocalDate.now()
    val buckets = remember(items, today) {
        items.groupBy { item ->
            bucketFor(item.sentAt, today)
        }.toSortedMap(compareBy { it.order })
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        buckets.forEach { (bucket, bucketItems) ->
            item(key = "header-${bucket.name}") {
                Text(
                    text = bucket.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(bucketItems, key = { it.id }) { item ->
                InboxRow(
                    item = item,
                    isRead = item.id in readMessageIds,
                    onClick = { onItemClick(item.id) },
                )
            }
        }
    }
}

private enum class DateBucket(val order: Int, val label: String) {
    Today(0, "Today"),
    Yesterday(1, "Yesterday"),
    Earlier(2, "Earlier"),
}

private fun bucketFor(isoSentAt: String, today: LocalDate): DateBucket {
    // sent_at is server-canonical UTC; project to the device's local date
    // for bucketing so "Today" matches what the user sees on their clock.
    val date = runCatching {
        Instant.parse(isoSentAt).atZone(ZoneId.systemDefault()).toLocalDate()
    }.getOrNull() ?: return DateBucket.Earlier
    return when {
        date == today -> DateBucket.Today
        date == today.minusDays(1) -> DateBucket.Yesterday
        else -> DateBucket.Earlier
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveScreen(
    items: List<InboxItem>,
    readMessageIds: Set<String>,
    onBack: () -> Unit,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Archive") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (items.isEmpty()) {
                Text(
                    "No archived cards.",
                    modifier = Modifier.padding(top = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.id }) { item ->
                        InboxRow(
                            item = item,
                            isRead = item.id in readMessageIds,
                            onClick = { onItemClick(item.id) },
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
private fun DetailScreen(
    item: InboxItem,
    onBack: () -> Unit,
    isArchived: Boolean,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier,
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
                actions = {
                    if (isArchived) {
                        IconButton(onClick = {
                            onUnarchive()
                            onBack()
                        }) {
                            Icon(Icons.Filled.Unarchive, contentDescription = "Unarchive")
                        }
                    } else {
                        IconButton(onClick = {
                            onArchive()
                            onBack()  // pop back to inbox so the archive transition feels active
                        }) {
                            Icon(Icons.Filled.Archive, contentDescription = "Archive")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Revocation banner — non-null reason rendered above the plugin
            // (or in place of it for compromised). M11.4 introduced the
            // classified reasons; UI uses them to drive differentiated
            // affordances per Codex review 38.
            item.revocationReason?.let { reason -> RevocationBanner(reason) }
            when {
                item.revocationReason == "compromised" -> {
                    // Refuse to execute. The InboxRepository's fetchPluginByRow
                    // path already null'd out bundleJs for compromised rows,
                    // but we also block here as a belt-and-braces defense in
                    // case bundleJs was populated by an earlier (pre-revoke)
                    // cache hit.
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Plugin execution blocked",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "This plugin has been marked compromised by its sender " +
                                "and will not be loaded. The raw decrypted payload is " +
                                "shown below for diagnostics.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        PayloadFallbackView(item.payloadJson)
                    }
                }
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

@Composable
private fun RevocationBanner(reason: String) {
    val (text, color) = when (reason) {
        "compromised" -> "Security: this plugin was marked compromised by its sender." to
            MaterialTheme.colorScheme.errorContainer
        "sender_disabled" -> "This sender is no longer available." to
            MaterialTheme.colorScheme.surfaceVariant
        "superseded" -> "A newer version of this plugin is available." to
            MaterialTheme.colorScheme.surfaceVariant
        else -> "This plugin was revoked by its sender." to
            MaterialTheme.colorScheme.surfaceVariant
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall)
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
