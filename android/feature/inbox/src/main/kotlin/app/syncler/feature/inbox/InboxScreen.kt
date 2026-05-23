package app.syncler.feature.inbox

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import java.util.Locale
import javax.inject.Inject
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
    private val eventStream: app.syncler.core.network.EventStreamManager,
) : ViewModel() {

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

        // Phase 2: react to server-pushed SSE events. inbox.changed → re-pull
        // inbox. state.changed → re-pull user state. dismiss → reflect cross-
        // device dismiss locally (V1: just trigger an inbox refresh since
        // dismiss state is recomputed from the server's delivery rows on
        // every refresh). The actual data still comes through the existing
        // REST endpoints under the device-bound JWT — SSE is a hint channel.
        eventStream.events
            .onEach { event ->
                when (event) {
                    is app.syncler.core.network.ServerEvent.InboxChanged ->
                        repository.refresh()
                    is app.syncler.core.network.ServerEvent.StateChanged ->
                        userState.pull()
                    is app.syncler.core.network.ServerEvent.Dismiss ->
                        repository.refresh()
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Lifecycle hook from InboxScreen. Open the SSE event stream and do a
     * one-shot catchup refresh+pull. Replaces the V1 every-15s polling
     * loop with reactive freshness.
     */
    fun onForeground() {
        eventStream.start()
        refresh()
    }

    /**
     * Lifecycle hook from InboxScreen. Close the SSE stream cleanly when
     * the app backgrounds — Doze / app standby would tear it down anyway,
     * but we close ahead of time so the server's `close_device_subscribers`
     * doesn't accumulate dead queues.
     */
    fun onBackground() {
        eventStream.stop()
    }

    private fun clearUiState() {
        _selectedId.value = null
        _searchQuery.value = ""
        _searchActive.value = false
        _selectedIds.value = emptySet()
        _viewMode.value = InboxView.All
        _selectedSenderId.value = null
    }

    fun refresh() {
        // Periodic poll: no visible spinner. The previous CircularProgressIndicator
        // in the top bar redrew on every poll and read as noisy. State changes
        // (new rows, read-mark sync) surface implicitly through the list.
        viewModelScope.launch {
            repository.refresh()
            userState.pull()
            userState.flushPendingPush()
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

    // ---------- Drawer-driven filter ----------
    /**
     * The Inbox tab now lives behind a left navigation drawer with four
     * "views" the user can pick: Unread (read-state filter), All (everything
     * except archive/delete), Archive (archived only), or Sender (one
     * specific paired sender). The top-bar title and the row list both
     * reflect the active view.
     */
    private val _viewMode = MutableStateFlow(InboxView.All)
    val viewMode: StateFlow<InboxView> = _viewMode.asStateFlow()

    private val _selectedSenderId = MutableStateFlow<String?>(null)
    val selectedSenderId: StateFlow<String?> = _selectedSenderId.asStateFlow()

    fun selectView(view: InboxView) {
        _viewMode.value = view
        if (view != InboxView.Sender) _selectedSenderId.value = null
    }

    fun selectSender(senderId: String) {
        _selectedSenderId.value = senderId
        _viewMode.value = InboxView.Sender
    }

    /**
     * Distinct (senderId, senderName) pairs derived from the current item
     * set, used to populate the "Groups" expandable section of the drawer.
     * Sorted by sender name for a stable list.
     */
    val sendersInInbox: StateFlow<List<Pair<String, String>>> = repository.items
        .map { items ->
            items.asSequence()
                .map { it.senderId to it.senderName }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase(Locale.ROOT) }
                .toList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

}

/**
 * Drawer-selectable inbox view. Replaces the previous group-mode toggle +
 * standalone archive screen — those are now just two of the views the user
 * can pick from the left nav.
 */
enum class InboxView { Unread, All, Archive, Sender }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    modifier: Modifier = Modifier,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val readMessageIds by viewModel.readMessageIds.collectAsState()
    val archivedMessageIds by viewModel.archivedMessageIds.collectAsState()
    val deletedMessageIds by viewModel.deletedMessageIds.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchActive by viewModel.searchActive.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val selectedSenderId by viewModel.selectedSenderId.collectAsState()
    val sendersInInbox by viewModel.sendersInInbox.collectAsState()

    // Phase 2: replace the 15-second polling loop with lifecycle-driven SSE.
    // ON_RESUME opens the event stream + does a one-shot catchup refresh;
    // ON_PAUSE closes the stream cleanly. Server events nudge the
    // ViewModel to re-fetch on demand (see InboxViewModel.init).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> viewModel.onForeground()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> viewModel.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    // Android back exits selection mode before any other navigation. This is
    // the standard "Gmail / Apple Mail" expectation — back from a selection
    // should clear the selection, not leave the screen.
    BackHandler(enabled = selectionMode, onBack = { viewModel.clearSelection() })

    val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val currentTitle = remember(viewMode, selectedSenderId, sendersInInbox) {
        when (viewMode) {
            InboxView.Unread -> "Unread"
            InboxView.All -> "Inbox"
            InboxView.Archive -> "Archive"
            InboxView.Sender -> sendersInInbox.firstOrNull { it.first == selectedSenderId }?.second ?: "Sender"
        }
    }

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            InboxDrawer(
                currentView = viewMode,
                selectedSenderId = selectedSenderId,
                senders = sendersInInbox,
                onPick = { view ->
                    viewModel.selectView(view)
                    scope.launch { drawerState.close() }
                },
                onPickSender = { senderId ->
                    viewModel.selectSender(senderId)
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
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
                    title = { Text(currentTitle) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open navigation drawer")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.setSearchActive(true) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            // Compute the view's base item set, then optionally apply search.
            val baseItems = remember(items, viewMode, selectedSenderId, archivedMessageIds, deletedMessageIds, readMessageIds) {
                when (viewMode) {
                    InboxView.All -> items.filter {
                        it.id !in archivedMessageIds && it.id !in deletedMessageIds
                    }
                    InboxView.Unread -> items.filter {
                        it.id !in archivedMessageIds && it.id !in deletedMessageIds && it.id !in readMessageIds
                    }
                    InboxView.Archive -> items.filter {
                        it.id in archivedMessageIds && it.id !in deletedMessageIds
                    }
                    InboxView.Sender -> {
                        val target = selectedSenderId
                        if (target == null) emptyList()
                        else items.filter {
                            it.senderId == target && it.id !in archivedMessageIds && it.id !in deletedMessageIds
                        }
                    }
                }
            }
            val filtered = remember(baseItems, searchQuery) {
                if (searchQuery.isBlank()) baseItems else baseItems.filter { matchesQuery(it, searchQuery) }
            }
            val onRowClick: (String) -> Unit = { id ->
                if (selectionMode) viewModel.toggleSelect(id) else viewModel.open(id)
            }
            val onRowLongClick: (String) -> Unit = { id -> viewModel.toggleSelect(id) }
            when {
                baseItems.isEmpty() -> Text(
                    emptyMessage(viewMode),
                    modifier = Modifier.padding(top = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                searchQuery.isNotBlank() && filtered.isEmpty() -> Text(
                    "No cards match \"$searchQuery\".",
                    modifier = Modifier.padding(top = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> FlatInboxList(
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
}

private fun emptyMessage(view: InboxView): String = when (view) {
    InboxView.All -> "No cards yet. Pair a sender from the Senders tab and they can push to you."
    InboxView.Unread -> "No unread cards."
    InboxView.Archive -> "No archived cards."
    InboxView.Sender -> "Nothing from this sender."
}

@Composable
private fun InboxDrawer(
    currentView: InboxView,
    selectedSenderId: String?,
    senders: List<Pair<String, String>>,
    onPick: (InboxView) -> Unit,
    onPickSender: (String) -> Unit,
) {
    var groupsExpanded by remember { mutableStateOf(currentView == InboxView.Sender) }
    androidx.compose.material3.ModalDrawerSheet {
        Spacer(Modifier.height(12.dp))
        Text(
            "Syncler",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        androidx.compose.material3.NavigationDrawerItem(
            label = { Text("Unread") },
            icon = { Icon(Icons.Filled.MarkEmailUnread, contentDescription = null) },
            selected = currentView == InboxView.Unread,
            onClick = { onPick(InboxView.Unread) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        androidx.compose.material3.NavigationDrawerItem(
            label = { Text("All") },
            icon = { Icon(Icons.Filled.Inbox, contentDescription = null) },
            selected = currentView == InboxView.All,
            onClick = { onPick(InboxView.All) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        androidx.compose.material3.NavigationDrawerItem(
            label = { Text("Archive") },
            icon = { Icon(Icons.Filled.Archive, contentDescription = null) },
            selected = currentView == InboxView.Archive,
            onClick = { onPick(InboxView.Archive) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        androidx.compose.material3.NavigationDrawerItem(
            label = { Text("Groups") },
            icon = {
                Icon(
                    if (groupsExpanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
            selected = false,
            onClick = { groupsExpanded = !groupsExpanded },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        if (groupsExpanded) {
            if (senders.isEmpty()) {
                Text(
                    "No paired senders with messages yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 48.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                )
            } else {
                senders.forEach { (senderId, senderName) ->
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(senderName) },
                        selected = currentView == InboxView.Sender && selectedSenderId == senderId,
                        onClick = { onPickSender(senderId) },
                        modifier = Modifier.padding(start = 32.dp, end = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlatInboxList(
    items: List<InboxItem>,
    readMessageIds: Set<String>,
    selectedIds: Set<String>,
    onItemClick: (String) -> Unit,
    onItemLongClick: (String) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { item ->
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
                    TimestampFormat.arrival(item.sentAt),
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

private val UNREAD_DOT_SIZE = 8.dp
private val LEADING_AFFORDANCE_SIZE = 24.dp
