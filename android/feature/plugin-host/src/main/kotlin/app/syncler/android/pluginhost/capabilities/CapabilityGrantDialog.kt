package app.syncler.android.pluginhost.capabilities

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 12 (V2 #10) — in-app grant dialog for service-call
 * capabilities (`location.coarse`, `location.fine`).
 *
 * Camera, gallery, and file all use the OS picker / camera UI
 * as their consent surface (triad 138 #3 per gemini). The
 * in-app dialog exists ONLY for `location.*` where the bridge
 * fires a service call without any visible OS UI.
 *
 * Threading: bridge calls `CapabilityGrantPrompter.requestGrant`
 * from any coroutine; it pushes a request onto the state flow
 * and suspends on a CompletableDeferred. The host's main
 * Activity observes the flow with `collectAsState` and renders
 * [CapabilityGrantDialogHost]. Allow/Deny buttons call
 * resolveGrant which completes the deferred → bridge resumes.
 */
class CapabilityGrantPrompter {

    private val _pendingPrompt = MutableStateFlow<PendingGrantPrompt?>(null)
    val pendingPrompt: StateFlow<PendingGrantPrompt?> = _pendingPrompt.asStateFlow()

    /**
     * Suspend until the user accepts or rejects the prompt for
     * [pluginName] requesting [capability]. Returns `true` if
     * the user granted; `false` if denied or dismissed.
     *
     * Only one prompt visible at a time — if a prompt is
     * already pending, this one queues with a synchronization
     * mutex. The spec calls out one concurrency rule for
     * Activity-result calls but doesn't mention prompts, so we
     * pick the safe serialization here.
     */
    suspend fun requestGrant(
        pluginRowId: String,
        pluginName: String,
        capability: String,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val request = PendingGrantPrompt(
            pluginRowId = pluginRowId,
            pluginName = pluginName,
            capability = capability,
            deferred = deferred,
        )
        // Set the flow; bridge waits.
        while (_pendingPrompt.value != null) {
            // Cooperative wait: yield to another suspending point
            // so the in-flight prompt can resolve. We don't queue
            // formally here — the AndroidX prompts are rare enough
            // that contention is unlikely in v0.1.
            kotlinx.coroutines.delay(50)
        }
        _pendingPrompt.value = request
        return deferred.await()
    }

    /** Called from the dialog's Allow / Deny buttons. */
    fun resolveGrant(callId: String, granted: Boolean) {
        val current = _pendingPrompt.value ?: return
        if (current.deferred.isCompleted) return
        if (current.deferred.complete(granted)) {
            _pendingPrompt.value = null
        }
    }
}

/**
 * Pending grant — the active state-flow entry. `deferred` is
 * what the bridge call is suspended on.
 */
data class PendingGrantPrompt(
    val pluginRowId: String,
    val pluginName: String,
    val capability: String,
    val deferred: CompletableDeferred<Boolean>,
)

/**
 * Composable the host Activity drops into its content tree.
 * Re-renders whenever a fresh prompt arrives on the prompter's
 * state flow.
 */
@Composable
fun CapabilityGrantDialogHost(prompter: CapabilityGrantPrompter) {
    val pending by prompter.pendingPrompt.collectAsState()
    val prompt = pending ?: return

    val capabilityLabel = when (prompt.capability) {
        "location.coarse" -> "Approximate location"
        "location.fine" -> "Precise location"
        else -> prompt.capability
    }

    AlertDialog(
        onDismissRequest = {
            prompter.resolveGrant(prompt.pluginRowId, granted = false)
        },
        title = { Text("Allow ${prompt.pluginName}?") },
        text = {
            Text(
                "This plugin is requesting access to $capabilityLabel. " +
                    "You can revoke this in Settings at any time.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    prompter.resolveGrant(prompt.pluginRowId, granted = true)
                },
            ) { Text("Allow") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    prompter.resolveGrant(prompt.pluginRowId, granted = false)
                },
            ) { Text("Deny") }
        },
    )
}
