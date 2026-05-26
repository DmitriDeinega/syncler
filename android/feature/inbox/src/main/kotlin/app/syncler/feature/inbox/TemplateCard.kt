package app.syncler.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.syncler.core.network.TemplateActionDto
import app.syncler.core.network.TemplateBlockDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

/**
 * Phase 3a — native Compose renderer for manifest-declared template cards.
 *
 * Replaces the WebView path for plugins published with `renderer == "template"`.
 * The publish-time server validator (see `server/app/schemas.py`
 * `TemplateObject` + `PluginPublishRequest.validate_renderer_template_pairing`)
 * has already enforced:
 *  - layout is in the supported set (currently only `standard_card`),
 *  - JSONPath syntax is `$.field(.subfield)*`,
 *  - the layout has its required fields (e.g. standard_card requires `title`),
 *  - every action's `endpoint` matches one of the plugin's `endpoints` globs,
 *  - action ids are unique within the template.
 *
 * So this renderer can trust the [TemplateBlockDto] structurally and only
 * needs to defend against payload-level surprises (missing key, wrong JSON
 * type at a path, oversized strings).
 *
 * Security: every text resolved from the payload is rendered through Compose
 * [Text], which does NOT interpret markdown, HTML, or any markup — so a
 * malicious payload value cannot inject a clickable link or escape the card.
 * Action endpoints are NOT re-validated client-side because the manifest
 * was already validated server-side; if the manifest later changes (e.g. a
 * sender publishes a new version), the client only sees fields the new
 * manifest declares.
 */
@Composable
fun TemplateCard(
    template: TemplateBlockDto,
    payloadJson: String,
    @Suppress("UNUSED_PARAMETER") declaredEndpoints: List<String>,
    onAction: (id: String, endpoint: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Parse once and remember — the same payloadJson string identity through
    // the lifetime of this composable instance is the cache key.
    val payload = remember(payloadJson) {
        runCatching { JSONObject(payloadJson) }.getOrElse {
            Timber.tag(TAG).w(it, "template card payload is not JSON")
            JSONObject()
        }
    }

    val titlePath = template.fields["title"]?.path
    val subtitlePath = template.fields["subtitle"]?.path
    val bodyPath = template.fields["body"]?.path

    val title = titlePath?.let { resolveJsonPath(payload, it) }
    val subtitle = subtitlePath?.let { resolveJsonPath(payload, it) }
    val body = bodyPath?.let { resolveJsonPath(payload, it) }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (template.layout) {
            "standard_card" -> StandardCard(
                title = title,
                subtitle = subtitle,
                body = body,
                actions = template.actions,
                onActionTap = { action -> onAction(action.id, action.endpoint) },
            )
            "compact_row" -> CompactRow(
                leading = template.fields["leading"]?.path?.let { resolveJsonPath(payload, it) },
                trailing = template.fields["trailing"]?.path?.let { resolveJsonPath(payload, it) },
                subtitle = template.fields["subtitle"]?.path?.let { resolveJsonPath(payload, it) },
                actions = template.actions,
                onActionTap = { action -> onAction(action.id, action.endpoint) },
            )
            "score_card" -> ScoreCard(
                score = template.fields["score"]?.path?.let { resolveJsonPath(payload, it) },
                label = template.fields["label"]?.path?.let { resolveJsonPath(payload, it) },
                caption = template.fields["caption"]?.path?.let { resolveJsonPath(payload, it) },
                actions = template.actions,
                onActionTap = { action -> onAction(action.id, action.endpoint) },
            )
            "stat_grid" -> StatGrid(
                title = template.fields["title"]?.path?.let { resolveJsonPath(payload, it) },
                stats = listOf(1, 2, 3, 4).mapNotNull { i ->
                    val labelPath = template.fields["stat${i}_label"]?.path
                    val valuePath = template.fields["stat${i}_value"]?.path
                    val labelVal = labelPath?.let { resolveJsonPath(payload, it) }
                    val valueVal = valuePath?.let { resolveJsonPath(payload, it) }
                    if (labelVal != null || valueVal != null) {
                        StatEntry(labelVal ?: "", valueVal ?: "")
                    } else null
                },
                actions = template.actions,
                onActionTap = { action -> onAction(action.id, action.endpoint) },
            )
            else -> {
                // Defense-in-depth — the server validator would have rejected
                // any other layout, but if a future server-side relaxation
                // forwards an unknown layout to a stale client, surface a
                // clear diagnostic instead of crashing or rendering nothing.
                Text(
                    "Unsupported template layout: ${template.layout}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun StandardCard(
    title: String?,
    subtitle: String?,
    body: String?,
    actions: List<TemplateActionDto>,
    onActionTap: (TemplateActionDto) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!title.isNullOrBlank()) {
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.titleSmall)
            }
            if (!body.isNullOrBlank()) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (actions.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Action lists are bounded and small (a few buttons); no
                    // need for LazyColumn here. Plain forEach keeps the
                    // composition graph flat and avoids nested scroll quirks
                    // inside the parent Column.
                    actions.forEach { action ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onActionTap(action) },
                        ) {
                            Text(action.label)
                        }
                    }
                }
            }
        }
    }
}

/**
 * V2 #12 — `compact_row`. Single-line dense layout: leading
 * text on the left, trailing on the right, optional subtitle
 * below. Designed for high-density feeds (stock tickers, sports
 * scores in-game, etc.).
 */
@Composable
private fun CompactRow(
    leading: String?,
    trailing: String?,
    subtitle: String?,
    actions: List<TemplateActionDto>,
    onActionTap: (TemplateActionDto) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                if (!leading.isNullOrBlank()) {
                    Text(leading, style = MaterialTheme.typography.titleMedium)
                }
                if (!trailing.isNullOrBlank()) {
                    Text(trailing, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (actions.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    actions.forEach { action ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onActionTap(action) },
                        ) {
                            Text(action.label)
                        }
                    }
                }
            }
        }
    }
}

/**
 * V2 #12 — `score_card`. Large numeric score (e.g. game score,
 * stock %, sensor reading) with a smaller label and optional
 * caption.
 */
@Composable
private fun ScoreCard(
    score: String?,
    label: String?,
    caption: String?,
    actions: List<TemplateActionDto>,
    onActionTap: (TemplateActionDto) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!score.isNullOrBlank()) {
                Text(score, style = MaterialTheme.typography.displayMedium)
            }
            if (!label.isNullOrBlank()) {
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
            if (!caption.isNullOrBlank()) {
                Text(caption, style = MaterialTheme.typography.bodySmall)
            }
            if (actions.isNotEmpty()) {
                actions.forEach { action ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onActionTap(action) },
                    ) {
                        Text(action.label)
                    }
                }
            }
        }
    }
}

internal data class StatEntry(val label: String, val value: String)

/**
 * V2 #12 — `stat_grid`. Title at the top + up to 4 stat tiles
 * (label/value pairs) in a 2x2 grid. Suitable for game stats,
 * health metrics, KPI dashboards.
 */
@Composable
private fun StatGrid(
    title: String?,
    stats: List<StatEntry>,
    actions: List<TemplateActionDto>,
    onActionTap: (TemplateActionDto) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!title.isNullOrBlank()) {
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            // Render the (up to 4) stats in 2-column rows.
            stats.chunked(2).forEach { row ->
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { stat ->
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(stat.label, style = MaterialTheme.typography.bodySmall)
                            Text(stat.value, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    // Pad if odd count.
                    if (row.size == 1) {
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            if (actions.isNotEmpty()) {
                actions.forEach { action ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onActionTap(action) },
                    ) {
                        Text(action.label)
                    }
                }
            }
        }
    }
}

/**
 * Visible only for unit testing. Resolves a `$.foo.bar` JSONPath against the
 * decrypted payload. Returns:
 *  - the string value when the leaf is a JSON string,
 *  - a JSON-encoded compact form when the leaf is a number/bool,
 *  - null if any segment is missing or the leaf is an object/array/null.
 *
 * The publish-time validator enforces the `$.foo(.bar)*` syntax, but this
 * function still tolerates a malformed input (returns null) rather than
 * throwing — defense against a future server change that loosens validation.
 */
internal fun resolveJsonPath(root: JSONObject, path: String): String? {
    if (!path.startsWith("$.")) return null
    val segments = path.substring(2).split('.')
    if (segments.isEmpty() || segments.any { it.isEmpty() }) return null

    var current: Any? = root
    for (segment in segments) {
        val obj = current as? JSONObject ?: return null
        if (!obj.has(segment)) return null
        current = obj.opt(segment)
        if (current == null || current == JSONObject.NULL) return null
    }
    return when (current) {
        is String -> current
        is Number, is Boolean -> current.toString()
        else -> null
    }
}

/**
 * Fire-and-forget POST helper used by [InboxViewModel.runTemplateAction]. The
 * receiving service decides how to authenticate (HMAC over body, plugin-
 * specific JWT, …); the host just attests to the user's tap.
 *
 * SECURITY: this builds its OWN [OkHttpClient] rather than injecting the
 * `:core:network` singleton. That singleton has an `AuthTokenProvider`-backed
 * interceptor (see
 * [app.syncler.core.network.NetworkModule.provideAuthInterceptor]) which
 * would auto-attach the user's Syncler bearer JWT to every request. Template
 * action endpoints are sender-controlled (the plugin's `declaredEndpoints`
 * globs) and must never see the user's host-auth token — otherwise a
 * compromised or hostile sender could exfiltrate it from a single tap.
 * Closes Codex consultation 62 RED #1.
 *
 * Pattern mirrors [app.syncler.android.pluginhost.capabilities.NetworkBridge],
 * which also builds its own unauthenticated client for the same reason on
 * the WebView-based renderer path.
 */
@Singleton
class TemplateActionRunner @Inject constructor() {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionSpecs(
                if (BuildConfig.DEBUG) {
                    listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
                } else {
                    listOf(ConnectionSpec.MODERN_TLS)
                },
            )
            .build()
    }

    suspend fun post(endpoint: String, payloadJson: String) {
        // Legacy V1 fire-and-forget shape — preserved for the
        // inbox UI's existing action button code path until the
        // V2 #11 flow upgrade lands the request/response variant
        // at the call site too.
        postWithResponse(endpoint, payloadJson)
    }

    /**
     * V2 #11 request/response variant: returns the HTTP status +
     * body to the caller so a plugin's `ctx.messageRespond(...)`
     * can surface success/failure back to plugin code.
     *
     * Returns null if the scheme check rejects (caller treats
     * this as an io_error / blocked outcome) or if the network
     * call throws.
     */
    suspend fun postWithResponse(endpoint: String, payloadJson: String): TemplateActionResponse? =
        withContext(Dispatchers.IO) {
            // Release builds reject cleartext outright. NetworkBridge does
            // the same check; we mirror it here so a leaked-template
            // manifest with an http:// endpoint can't fall through to a
            // cleartext POST on production.
            val schemeOk = endpoint.startsWith("https://") ||
                (BuildConfig.DEBUG && endpoint.startsWith("http://"))
            if (!schemeOk) {
                Timber.tag(TAG).w("template action POST %s blocked: non-HTTPS", endpoint)
                return@withContext null
            }
            runCatching {
                val request = Request.Builder()
                    .url(endpoint)
                    .post(payloadJson.toRequestBody(JSON_MEDIA))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        Timber.tag(TAG).w(
                            "template action POST %s failed: HTTP %d",
                            endpoint,
                            response.code,
                        )
                    } else {
                        Timber.tag(TAG).i("template action POST %s ok", endpoint)
                    }
                    TemplateActionResponse(status = response.code, body = body)
                }
            }.onFailure {
                Timber.tag(TAG).w(it, "template action POST %s threw", endpoint)
            }.getOrNull()
        }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}

/** V2 #11: typed result for plugin-facing message.respond. */
data class TemplateActionResponse(val status: Int, val body: String)

private const val TAG = "TemplateCard"
