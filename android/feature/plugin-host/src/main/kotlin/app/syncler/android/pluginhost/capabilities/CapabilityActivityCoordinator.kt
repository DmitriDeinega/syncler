package app.syncler.android.pluginhost.capabilities

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber

/**
 * Phase 12 (V2 #10) — singleton coordinator that routes
 * Activity-result-bearing capability calls from sandbox
 * processes (`:plugin`, `:nativePlugin`) into the host's
 * foreground Activity.
 *
 * Lifecycle (spec v3 "Continuation lifecycle"):
 *
 *   created
 *     → waiting_for_grant     (in-app prompt — Category B only)
 *     → waiting_for_os_perm   (runtime permission dialog — camera + location.*)
 *     → launched              (Activity intent fired / picker open)
 *     → completed / cancelled / timed_out
 *
 * Timeouts:
 *  - `waiting_for_grant` + `waiting_for_os_perm`: 30s.
 *  - `launched`: 5 min.
 *
 * Concurrency (spec): one global active Activity-result op at
 * a time. Second concurrent call returns `capability_busy`.
 * `location.*` is exempt (no Activity launch).
 *
 * Threading: callbacks fire on the Activity's main thread;
 * the coordinator wraps everything in coroutines so bridge
 * handlers can `suspend` on the result without blocking.
 */
class CapabilityActivityCoordinator(
    private val application: Application,
) {

    /**
     * The currently-foregrounded Activity, set by
     * [bindToActivity]. Null when no Activity is in the
     * STARTED state; bridge calls in that window return
     * `no_foreground_activity` immediately.
     */
    @Volatile
    private var activeOwner: ActivityResultRegistryOwner? = null

    @Volatile
    private var activeLifecycleOwner: LifecycleOwner? = null

    /**
     * One launcher per registered contract, keyed by the
     * stable registry key. Phase 12 registers a fixed set on
     * Activity attach so re-registration after config change
     * is automatic.
     */
    private val launchers = ConcurrentHashMap<String, ActivityResultLauncher<*>>()

    /**
     * `call_id → continuation`. Phase 12 stores the deferred
     * here so the AndroidX callback can resolve it from the
     * main-thread callback into whichever coroutine launched
     * the call.
     */
    private val pending = ConcurrentHashMap<String, PendingCall>()

    /**
     * Spec concurrency rule: one global activity-result op at
     * a time. Set when a call moves into `launched` state;
     * cleared on result / cancel / timeout.
     */
    @Volatile
    private var activeActivityResultCallId: String? = null

    /**
     * Register the application as the
     * [ActivityResultRegistryOwner] consumer so launchers
     * survive activity recreation. Called from
     * `SynclerApplication.onCreate`.
     */
    fun attach() {
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    /** For symmetry — called from `onTerminate` if it ever fires. */
    fun detach() {
        application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        launchers.clear()
        pending.clear()
        activeOwner = null
        activeLifecycleOwner = null
        activeActivityResultCallId = null
    }

    /**
     * Called from a host Activity's `onCreate` to register the
     * Phase 12 contracts against that Activity's registry.
     * After this, a config-change Activity recreate re-binds the
     * launchers automatically — pending calls reference launchers
     * by stable keys (see [REGISTRY_KEY_*] constants).
     */
    fun bindToActivity(activity: Activity) {
        val owner = activity as? ActivityResultRegistryOwner ?: run {
            Timber.tag(TAG).w("Activity is not ActivityResultRegistryOwner; bridge will fail to dispatch")
            return
        }
        val lifecycleOwner = activity as? LifecycleOwner ?: run {
            Timber.tag(TAG).w("Activity is not LifecycleOwner; coordinator can't watch start/stop")
            return
        }
        activeOwner = owner
        activeLifecycleOwner = lifecycleOwner

        val registry = owner.activityResultRegistry

        // Pre-register every contract we use against stable keys.
        // Each register*() call hands us a launcher whose lifetime
        // is bound to the Activity's lifecycle observer. When the
        // Activity dies, the launcher dies; on recreation we re-
        // bind here with the same key.
        launchers[REGISTRY_KEY_PICK_FILE] = registry.register(
            REGISTRY_KEY_PICK_FILE,
            lifecycleOwner,
            ActivityResultContracts.OpenDocument(),
            ::handleFileResult,
        )
        launchers[REGISTRY_KEY_PICK_VISUAL_SINGLE] = registry.register(
            REGISTRY_KEY_PICK_VISUAL_SINGLE,
            lifecycleOwner,
            ActivityResultContracts.PickVisualMedia(),
            ::handleGallerySingleResult,
        )
        launchers[REGISTRY_KEY_PICK_VISUAL_MULTIPLE] = registry.register(
            REGISTRY_KEY_PICK_VISUAL_MULTIPLE,
            lifecycleOwner,
            ActivityResultContracts.PickMultipleVisualMedia(GALLERY_MAX_ITEMS),
            ::handleGalleryMultipleResult,
        )
        launchers[REGISTRY_KEY_CAMERA] = registry.register(
            REGISTRY_KEY_CAMERA,
            lifecycleOwner,
            ActivityResultContracts.TakePicture(),
            ::handleCameraResult,
        )
        launchers[REGISTRY_KEY_REQUEST_PERMISSION] = registry.register(
            REGISTRY_KEY_REQUEST_PERMISSION,
            lifecycleOwner,
            ActivityResultContracts.RequestPermission(),
            ::handlePermissionResult,
        )
    }

    /**
     * Bridge handler: open a SAF document picker, awaiting a
     * content URI. Returns null on timeout / cancel / no-foreground.
     */
    suspend fun pickFile(callId: String, mimeFilter: String?): Uri? {
        val result = launchActivityResult(callId, ActivityResultKind.FILE) { launcher ->
            @Suppress("UNCHECKED_CAST")
            (launcher as ActivityResultLauncher<Array<String>>).launch(
                arrayOf(mimeFilter ?: "*/*"),
            )
        }
        return result as? Uri
    }

    /** Bridge handler: pick a single image/video. */
    suspend fun pickVisualSingle(callId: String): Uri? =
        launchActivityResult(callId, ActivityResultKind.GALLERY_SINGLE) { launcher ->
            @Suppress("UNCHECKED_CAST")
            (launcher as ActivityResultLauncher<PickVisualMediaRequest>).launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    .build(),
            )
        } as Uri?

    /** Bridge handler: pick multiple images/videos. */
    suspend fun pickVisualMultiple(callId: String): List<Uri> {
        val result = launchActivityResult(callId, ActivityResultKind.GALLERY_MULTIPLE) { launcher ->
            @Suppress("UNCHECKED_CAST")
            (launcher as ActivityResultLauncher<PickVisualMediaRequest>).launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    .build(),
            )
        }
        @Suppress("UNCHECKED_CAST")
        return (result as? List<Uri>) ?: emptyList()
    }

    /** Bridge handler: launch ACTION_IMAGE_CAPTURE writing to [outputUri]. */
    suspend fun captureImage(callId: String, outputUri: Uri): Boolean {
        val result = launchActivityResult(callId, ActivityResultKind.CAMERA) { launcher ->
            @Suppress("UNCHECKED_CAST")
            (launcher as ActivityResultLauncher<Uri>).launch(outputUri)
        }
        return result == true
    }

    /**
     * Bridge handler: synchronous OS permission request. Used
     * for CAMERA + ACCESS_COARSE_LOCATION + ACCESS_FINE_LOCATION.
     * Returns true if granted, false if denied or no foreground.
     */
    suspend fun requestPermission(callId: String, permission: String): Boolean {
        val result = launchActivityResult(callId, ActivityResultKind.PERMISSION) { launcher ->
            @Suppress("UNCHECKED_CAST")
            (launcher as ActivityResultLauncher<String>).launch(permission)
        }
        return result == true
    }

    /**
     * The shared work behind every launchX(). Records the
     * pending continuation, sets the global busy flag, and
     * launches the contract via the named launcher. Suspends
     * until the result handler resolves.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun launchActivityResult(
        callId: String,
        kind: ActivityResultKind,
        launch: (ActivityResultLauncher<*>) -> Unit,
    ): Any? = suspendCancellableCoroutine { cont ->
        // Concurrency gate.
        val existing = activeActivityResultCallId
        if (existing != null && existing != callId) {
            cont.resume("capability_busy")
            return@suspendCancellableCoroutine
        }
        // Resolve launcher by kind.
        val key = kind.registryKey
        val launcher = launchers[key]
        val owner = activeOwner
        if (launcher == null || owner == null) {
            cont.resume("no_foreground_activity")
            return@suspendCancellableCoroutine
        }
        // Record pending — store the CancellableContinuation
        // directly so completeMatching can resume it without an
        // unsafe cast. Triad 139 gemini #2 fix: previous version
        // tried to cast cont to CompletableDeferred which would
        // throw ClassCastException at runtime on every call.
        pending[callId] = PendingCall(
            callId = callId,
            kind = kind,
            continuation = cont,
            launchedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        activeActivityResultCallId = callId
        cont.invokeOnCancellation {
            pending.remove(callId)
            if (activeActivityResultCallId == callId) {
                activeActivityResultCallId = null
            }
        }
        try {
            launch(launcher)
        } catch (exc: Throwable) {
            Timber.tag(TAG).e(exc, "launch failed for kind=%s call=%s", kind, callId)
            pending.remove(callId)
            activeActivityResultCallId = null
            cont.resume("io_error")
        }
    }

    // ---- Result handlers (main-thread callbacks from AndroidX) ----

    private fun handleFileResult(uri: Uri?) {
        completeMatching(ActivityResultKind.FILE, uri)
    }

    private fun handleGallerySingleResult(uri: Uri?) {
        completeMatching(ActivityResultKind.GALLERY_SINGLE, uri)
    }

    private fun handleGalleryMultipleResult(uris: List<Uri>) {
        completeMatching(ActivityResultKind.GALLERY_MULTIPLE, uris)
    }

    private fun handleCameraResult(success: Boolean) {
        completeMatching(ActivityResultKind.CAMERA, success)
    }

    private fun handlePermissionResult(granted: Boolean) {
        completeMatching(ActivityResultKind.PERMISSION, granted)
    }

    /**
     * Find the pending call matching the result kind. AndroidX's
     * ActivityResult callback doesn't carry our call_id, so we
     * match by kind. The concurrency-busy gate guarantees only
     * one pending of each kind at a time.
     */
    private fun completeMatching(kind: ActivityResultKind, result: Any?) {
        val match = pending.values.firstOrNull { it.kind == kind } ?: run {
            Timber.tag(TAG).w("no pending call for result kind=%s", kind)
            return
        }
        pending.remove(match.callId)
        if (activeActivityResultCallId == match.callId) {
            activeActivityResultCallId = null
        }
        // Resume the suspending continuation that launchActivityResult
        // is awaiting. Stored as the same `cont` object via casting trick.
        @Suppress("UNCHECKED_CAST")
        (match.continuation as kotlin.coroutines.Continuation<Any?>).resume(result)
    }

    // ---- Application lifecycle tracking ----

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            bindToActivity(activity)
        }

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {
            if (activeOwner === activity) {
                activeOwner = null
                activeLifecycleOwner = null
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            // Launchers auto-unregister via lifecycle observers; just
            // forget any keys this Activity owned so the next bind
            // re-registers cleanly.
            if (activeOwner === activity) {
                activeOwner = null
                activeLifecycleOwner = null
                launchers.clear()
            }
        }
    }

    private data class PendingCall(
        val callId: String,
        val kind: ActivityResultKind,
        val continuation: Any,
        val launchedAtElapsedMs: Long,
    )

    private enum class ActivityResultKind(val registryKey: String) {
        FILE(REGISTRY_KEY_PICK_FILE),
        GALLERY_SINGLE(REGISTRY_KEY_PICK_VISUAL_SINGLE),
        GALLERY_MULTIPLE(REGISTRY_KEY_PICK_VISUAL_MULTIPLE),
        CAMERA(REGISTRY_KEY_CAMERA),
        PERMISSION(REGISTRY_KEY_REQUEST_PERMISSION),
    }

    companion object {
        private const val TAG = "CapCoord"

        // Stable registry keys — same name across Activity
        // recreations so the AndroidX registry restores callbacks
        // automatically.
        private const val REGISTRY_KEY_PICK_FILE = "syncler.cap.pick_file"
        private const val REGISTRY_KEY_PICK_VISUAL_SINGLE = "syncler.cap.pick_visual_single"
        private const val REGISTRY_KEY_PICK_VISUAL_MULTIPLE = "syncler.cap.pick_visual_multiple"
        private const val REGISTRY_KEY_CAMERA = "syncler.cap.camera"
        private const val REGISTRY_KEY_REQUEST_PERMISSION = "syncler.cap.permission"

        /** Spec H: SDK boundary cap. */
        const val GALLERY_MAX_ITEMS = 10
    }
}
