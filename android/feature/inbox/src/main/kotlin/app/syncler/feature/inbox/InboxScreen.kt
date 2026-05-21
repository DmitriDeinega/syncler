package app.syncler.feature.inbox

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repository: InboxRepository,
    private val userState: UserStateRepository,
    private val session: app.syncler.core.auth.Session,
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

    private val _searchActive = MutableStateFlow(false)
    val searchActive: StateFlow<Boolean> = _searchActive.asStateFlow()
    fun setSearchActive(active: Boolean) {
        _searchActive.value = active
        if (!active) _searchQuery.value = ""
    }

    init {
        // Pull cross-device state, then retry any push that failed previously
        // (network blip, 409 conflict that couldn't resolve in one retry,
        // session was locked when the mark was issued, etc.). No-ops if
        // currently locked or if no dirty changes are pending.
        viewModelScope.launch {
            userState.pull()
            userState.flushPendingPush()
        }

        // M11.7 fix-up (review 44, Codex BLOCKER): InboxViewModel is
        // activity-scoped, so selection / collapsed-senders / group mode /
        // search / archive-open state survives the AuthScreen <-> InboxScreen
        // switch when the user logs out and a different user logs in. Mirror
        // the UserStateRepository observer pattern: collect sessionState,
        // drop the initial emission, clear UI state on transition to locked.
        session.sessionState
            .map { it.isUnlocked }
            .distinctUntilChanged()
            .drop(1)
            .onEach { unlocked -> if (!unlocked) clearUiState() }
            .launchIn(viewModelScope)
    }

    private fun clearUiState() {
        _selectedId.value = null
        _showArchive.value = false
        _searchQuery.value = ""
        _searchActive.value = false
        _selectedIds.value = emptySet()
        _collapsedSenders.value = emptySet()
        _groupMode.value = GroupMode.Chronological
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

    val deletedMessageIds: StateFlow<Set<String>> = userState.state
        .map { st -> st.deletedMessages.map { it.messageId }.toSet() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = userState.state.value.deletedMessages.map { it.messageId }.toSet(),
        )

    fun archive(itemId: String) {
        viewModelScope.launch { userState.markArchived(itemId) }
    }

    fun unarchive(itemId: String) {
        viewModelScope.launch { userState.markUnarchived(itemId) }
    }

    fun delete(itemId: String) {
        viewModelScope.launch { userState.markDeleted(itemId) }
    }

    private val _showArchive = MutableStateFlow(false)
    val showArchive: StateFlow<Boolean> = _showArchive.asStateFlow()
    fun openArchive() { _showArchive.value = true }
    fun closeArchive() { _showArchive.value = false }

    // ---------- M11.7: multi-select ----------
    /**
     * Selection mode is entered by long-pressing a row. While active, rows
     * toggle on tap (instead of opening detail), the top-app-bar swaps to
     * a selection bar with bulk actions, and back exits selection without
     * navigating away.
     */
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()
    val selectionMode: StateFlow<Boolean> = _selectedIds
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleSelect(itemId: String) {
        _selectedIds.value = _selectedIds.value.let {
            if (itemId in it) it - itemId else it + itemId
        }
    }

    fun clearSelection() { _selectedIds.value = emptySet() }

    fun archiveSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch { userState.markManyArchived(ids) }
        clearSelection()
    }

    fun deleteSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch { userState.markManyDeleted(ids) }
        clearSelection()
    }

    // ---------- M11.7: group-by-sender + collapse ----------
    /**
     * Inbox layout mode. Defaults to chronological with date buckets;
     * BySender groups consecutive items per sender with collapsible section
     * headers. The toggle lives in the top-app-bar.
     */
    private val _groupMode = MutableStateFlow(GroupMode.Chronological)
    val groupMode: StateFlow<GroupMode> = _groupMode.asStateFlow()
    fun cycleGroupMode() {
        _groupMode.value = when (_groupMode.value) {
            GroupMode.Chronological -> GroupMode.BySender
            GroupMode.BySender -> GroupMode.Chronological
        }
    }

    /** Set of senderIds whose group section is collapsed (BySender mode). */
    private val _collapsedSenders = MutableStateFlow<Set<String>>(emptySet())
    val collapsedSenders: StateFlow<Set<String>> = _collapsedSenders.asStateFlow()
    fun toggleSenderCollapse(senderId: String) {
        _collapsedSenders.value = _collapsedSenders.value.let {
            if (senderId in it) it - senderId else it + senderId
        }
    }
}

enum class GroupMode { Chronological, BySender }

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
    val deletedMessageIds by viewModel.deletedMessageIds.collectAsState()
    val showArchive by viewModel.showArchive.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val groupMode by viewModel.groupMode.collectAsState()
    val collapsedSenders by viewModel.collapsedSenders.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchActive by viewModel.searchActive.collectAsState()

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
            onDelete = { viewModel.delete(selectedItem.id) },
            modifier = modifier,
        )
        return
    }
    if (showArchive) {
        ArchiveScreen(
            // Deleted items are hidden EVERYWHERE — archive view too. The
            // archive screen shows only archived-but-not-deleted.
            items = items.filter { it.id in archivedMessageIds && it.id !in deletedMessageIds },
            readMessageIds = readMessageIds,
            onBack = viewModel::closeArchive,
            onItemClick = { id -> viewModel.open(id) },
            modifier = modifier,
        )
        return
    }

    // Android back exits selection mode before any other navigation. This is
    // the standard "Gmail / Apple Mail" expectation — back from a selection
    // should clear the selection, not leave the screen.
    BackHandler(enabled = selectionMode, onBack = { viewModel.clearSelection() })
    Scaffold(
        modifier = modifier,
        topBar = {
            when {
                selectionMode -> SelectionTopBar(
                    count = selectedIds.size,
                    onClose = viewModel::clearSelection,
                    onArchive = viewModel::archiveSelected,
                    onDelete = viewModel::deleteSelected,
                )
                searchActive -> InboxSearchAppBar(
                    query = searchQuery,
                    onQueryChange = viewModel::setSearch,
                    onClose = { viewModel.setSearchActive(false) },
                )
                else -> TopAppBar(
                    title = { Text("Inbox") },
                    actions = {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp).padding(horizontal = 12.dp),
                            )
                        }
                        IconButton(onClick = { viewModel.setSearchActive(true) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = viewModel::cycleGroupMode) {
                            Icon(
                                when (groupMode) {
                                    GroupMode.Chronological -> Icons.AutoMirrored.Filled.Sort
                                    GroupMode.BySender -> Icons.AutoMirrored.Filled.List
                                },
                                contentDescription = when (groupMode) {
                                    GroupMode.Chronological -> "Group by sender"
                                    GroupMode.BySender -> "Show chronological"
                                },
                            )
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
            val activeItems = items.filter {
                it.id !in archivedMessageIds && it.id !in deletedMessageIds
            }
            val filtered = remember(activeItems, searchQuery) {
                if (searchQuery.isBlank()) activeItems else activeItems.filter { matchesQuery(it, searchQuery) }
            }
            val onRowClick: (String) -> Unit = { id ->
                if (selectionMode) viewModel.toggleSelect(id) else viewModel.open(id)
            }
            val onRowLongClick: (String) -> Unit = { id -> viewModel.toggleSelect(id) }
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
                groupMode == GroupMode.BySender -> GroupedBySenderList(
                    items = filtered,
                    readMessageIds = readMessageIds,
                    selectedIds = selectedIds,
                    collapsedSenders = collapsedSenders,
                    onItemClick = onRowClick,
                    onItemLongClick = onRowLongClick,
                    onSenderToggle = viewModel::toggleSenderCollapse,
                )
                else -> BucketedInboxList(
                    items = filtered,
                    readMessageIds = readMessageIds,
                    selectedIds = selectedIds,
                    onItemClick = onRowClick,
                    onItemLongClick = onRowLongClick,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $count message${if (count == 1) "" else "s"}?") },
            text = { Text("They'll disappear from inbox and archive on all your devices. This can't be undone.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = onArchive) {
                Icon(Icons.Filled.Archive, contentDescription = "Archive selected")
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxSearchAppBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    // Android system back closes search consistently with the top-bar back
    // arrow — Codex review 41 flagged the asymmetry where back used to fall
    // through to the previous tab while only the arrow exited search.
    BackHandler(onBack = onClose)

    TopAppBar(
        title = {
            androidx.compose.material3.TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search inbox") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
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
    val q = rawQuery.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return true
    if (item.senderName.lowercase(Locale.ROOT).contains(q)) return true
    item.hostPreview?.let { preview ->
        if (preview.title.lowercase(Locale.ROOT).contains(q)) return true
        if (preview.subtitle?.lowercase(Locale.ROOT)?.contains(q) == true) return true
        if (preview.summary?.lowercase(Locale.ROOT)?.contains(q) == true) return true
        if (preview.searchText.any { it.lowercase(Locale.ROOT).contains(q) }) return true
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
    selectedIds: Set<String>,
    onItemClick: (String) -> Unit,
    onItemLongClick: (String) -> Unit,
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
                    isSelected = item.id in selectedIds,
                    onClick = { onItemClick(item.id) },
                    onLongClick = { onItemLongClick(item.id) },
                )
            }
        }
    }
}

/**
 * BySender layout: items grouped by senderId with collapsible section
 * headers. Sections sort by the most-recent sentAt in each group so the
 * most-active sender bubbles to the top. Within a section, items are
 * newest-first (same as chronological).
 */
@Composable
private fun GroupedBySenderList(
    items: List<InboxItem>,
    readMessageIds: Set<String>,
    selectedIds: Set<String>,
    collapsedSenders: Set<String>,
    onItemClick: (String) -> Unit,
    onItemLongClick: (String) -> Unit,
    onSenderToggle: (String) -> Unit,
) {
    val groups = remember(items) {
        InboxRepository.groupItemsBySender(items)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { (senderId, group) ->
            val collapsed = senderId in collapsedSenders
            val senderName = group.first().senderName
            item(key = "sender-header-$senderId") {
                SenderSectionHeader(
                    senderName = senderName,
                    count = group.size,
                    collapsed = collapsed,
                    onToggle = { onSenderToggle(senderId) },
                )
            }
            if (!collapsed) {
                items(group, key = { it.id }) { item ->
                    InboxRow(
                        item = item,
                        isRead = item.id in readMessageIds,
                        isSelected = item.id in selectedIds,
                        onClick = { onItemClick(item.id) },
                        onLongClick = { onItemLongClick(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SenderSectionHeader(
    senderName: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (collapsed) "Expand" else "Collapse",
        )
        Text(
            "$senderName  ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
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
                            // V1 archive view doesn't support multi-select —
                            // unarchive is per-card from the detail view.
                            isSelected = false,
                            onClick = { onItemClick(item.id) },
                            onLongClick = { onItemClick(item.id) },
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InboxRow(
    item: InboxItem,
    isRead: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val preview = item.hostPreview
    val titleStyle = if (isRead) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    }
    val cardColors = if (isSelected) {
        androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        )
    } else {
        androidx.compose.material3.CardDefaults.cardColors()
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = cardColors,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Leading affordance: selection check OR unread dot. Reserves
            // a 24dp slot so the row geometry stays stable across read /
            // unread / selected transitions, and the check icon renders at
            // a real Material icon size instead of being crammed into the
            // 8dp dot footprint (review 44, Codex).
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(LEADING_AFFORDANCE_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isSelected -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    !isRead -> Box(
                        modifier = Modifier
                            .size(UNREAD_DOT_SIZE)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            ),
                    )
                }
            }
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
                    Text(
                    TimestampFormat.relative(item.sentAt),
                    style = MaterialTheme.typography.labelSmall,
                )
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
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this message?") },
            text = {
                Text(
                    "It'll disappear from the inbox and archive on all your " +
                        "devices. This can't be undone.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    onDelete()
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
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
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
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
private val LEADING_AFFORDANCE_SIZE = 24.dp
