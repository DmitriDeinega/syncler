package app.syncler.feature.inbox

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repository: InboxRepository,
) : ViewModel() {

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    val items: StateFlow<List<InboxItem>> = repository.items

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            repository.refresh()
            _refreshing.value = false
        }
    }
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

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            delay(POLL_INTERVAL_MS)
        }
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
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(items, key = { it.id }) { item -> InboxCard(item) }
                }
            }
        }
    }
}

@Composable
private fun InboxCard(item: InboxItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.senderName, style = MaterialTheme.typography.titleMedium)
                Text(item.sentAt, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            if (item.bundleJs != null) {
                PluginRenderView(
                    bundleJs = item.bundleJs,
                    payloadJson = item.payloadJson,
                    declaredEndpoints = item.declaredEndpoints,
                )
            } else {
                PayloadFallbackView(item.payloadJson)
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
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_RENDER_HEIGHT),
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
                // Stash on the tag so onRelease can clean it up. Without this
                // the bridge's CoroutineScope outlives the WebView and the
                // WebView itself is never destroyed — a per-card leak that
                // accumulates on scroll/recompose.
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
private val CARD_RENDER_HEIGHT = 280.dp
