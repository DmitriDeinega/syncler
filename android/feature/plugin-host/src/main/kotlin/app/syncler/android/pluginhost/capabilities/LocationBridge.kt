package app.syncler.android.pluginhost.capabilities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import app.syncler.android.pluginhost.PluginInstance
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Phase 12 (V2 #10) `location.coarse` / `location.fine`
 * capability bridge. Single-fix only; background location is
 * out of scope.
 *
 * Granularity is part of the capability name:
 *  - `location.fine` → may use ACCESS_FINE_LOCATION (the OS may
 *    still downgrade to coarse if the user chose "approximate")
 *  - `location.coarse` → ACCESS_COARSE_LOCATION only
 *
 * Returned `precision` field reflects what the OS actually
 * granted, not what the plugin asked for (triad 138 G — plugins
 * inspect this to detect downgrade).
 *
 * Uses platform `LocationManager` rather than Fused Location
 * Provider so we don't pull in a Play Services dependency.
 */
class LocationBridge(
    private val context: Context,
    private val coordinator: CapabilityActivityCoordinator?,
    private val grantStore: CapabilityGrantStore,
    private val prompter: CapabilityGrantPrompter,
    private val auditLogger: PluginCapabilityAuditDao,
) {

    /**
     * Bridge entry. argsJson includes `accuracy: "coarse" | "fine"`
     * (defaulting to fine). The bridge picks the capability name
     * to verify based on accuracy + the plugin's manifest.
     */
    suspend fun current(plugin: PluginInstance, argsJson: String): String =
        withContext(Dispatchers.IO) {
            val args = JsonBridgeCodec.objectFrom(argsJson)
            val accuracy = (args["accuracy"] as? String) ?: "fine"
            val capability = when (accuracy) {
                "coarse" -> "location.coarse"
                else -> "location.fine"
            }
            val pluginRowId = plugin.manifest.id
            val sandboxToken = (plugin.sandboxHandle?.sandboxToken ?: 0)
            val callId = UUID.randomUUID().toString()
            val startedAtElapsedMs = nowElapsedMs()

            // 1. Manifest declares the capability?
            if (capability !in plugin.manifest.declaredCapabilities.map { it.toString() }
                .toSet()
            ) {
                audit(plugin, capability, callId, startedAtElapsedMs, "capability_not_declared")
                return@withContext JsonBridgeCodec.error("capability_not_declared")
            }

            // 2. Grant row present?
            val hasGrant = grantStore.hasGrant(pluginRowId, capability)
            // 3. OS permission?
            val osPerm = when (accuracy) {
                "fine" -> Manifest.permission.ACCESS_FINE_LOCATION
                else -> Manifest.permission.ACCESS_COARSE_LOCATION
            }
            val osGranted = ContextCompat.checkSelfPermission(context, osPerm) ==
                PackageManager.PERMISSION_GRANTED

            // Spec v3 Category B sequence.
            if (!hasGrant) {
                val pluginName = plugin.manifest.name ?: plugin.manifest.id
                val accepted = prompter.requestGrant(pluginRowId, pluginName, capability)
                if (!accepted) {
                    audit(plugin, capability, callId, startedAtElapsedMs, "capability_denied")
                    return@withContext JsonBridgeCodec.error("capability_denied")
                }
                if (!osGranted) {
                    val coord = coordinator
                    if (coord == null) {
                        audit(plugin, capability, callId, startedAtElapsedMs, "no_foreground_activity")
                        return@withContext JsonBridgeCodec.error("no_foreground_activity")
                    }
                    val osAccepted = coord.requestPermission(callId, osPerm)
                    if (!osAccepted) {
                        audit(plugin, capability, callId, startedAtElapsedMs, "os_denied")
                        return@withContext JsonBridgeCodec.error("os_denied")
                    }
                }
                grantStore.grant(pluginRowId, capability, nowWallClockMs())
            } else if (!osGranted) {
                // Grant row exists but OS permission was revoked
                // outside the app. Don't auto-re-prompt; surface
                // the deny.
                audit(plugin, capability, callId, startedAtElapsedMs, "os_denied")
                return@withContext JsonBridgeCodec.error("os_denied")
            }

            // 4. Mid-flight: re-verify grant still present (user
            //    may have revoked from settings between prompt and
            //    fix).
            if (!grantStore.reverifyGrant(pluginRowId, capability)) {
                audit(plugin, capability, callId, startedAtElapsedMs, "capability_denied")
                return@withContext JsonBridgeCodec.error("capability_denied")
            }

            // 5. Fire the single-fix request.
            val fix = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                requestSingleFix(accuracy)
            }
            if (fix == null) {
                audit(plugin, capability, callId, startedAtElapsedMs, "timeout")
                return@withContext JsonBridgeCodec.error("timeout")
            }

            // 6. Decide returned precision from the fix's provider.
            val precision = when {
                fix.provider == LocationManager.GPS_PROVIDER -> "fine"
                fix.accuracy > 100f -> "coarse"
                else -> if (accuracy == "fine") "fine" else "coarse"
            }
            grantStore.touch(pluginRowId, capability, nowWallClockMs())
            audit(
                plugin = plugin,
                capability = capability,
                callId = callId,
                startedAtElapsedMs = startedAtElapsedMs,
                outcome = "success",
                precision = precision,
            )
            JsonBridgeCodec.toJson(
                mapOf(
                    "latitude" to fix.latitude,
                    "longitude" to fix.longitude,
                    "accuracyMeters" to fix.accuracy.toDouble(),
                    "precision" to precision,
                ),
            )
        }

    @SuppressLint("MissingPermission") // gated above
    private suspend fun requestSingleFix(accuracy: String): Location? =
        suspendCancellableCoroutine { cont ->
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val provider = when (accuracy) {
                "fine" -> {
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        LocationManager.GPS_PROVIDER
                    } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        LocationManager.NETWORK_PROVIDER
                    } else null
                }
                else -> {
                    if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        LocationManager.NETWORK_PROVIDER
                    } else if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                        LocationManager.PASSIVE_PROVIDER
                    } else null
                }
            }
            if (provider == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    try {
                        lm.removeUpdates(this)
                    } catch (_: Throwable) {}
                    if (!cont.isCompleted) cont.resume(location)
                }

                @Deprecated("Required by older API levels")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    try {
                        lm.removeUpdates(this)
                    } catch (_: Throwable) {}
                    if (!cont.isCompleted) cont.resume(null)
                }
            }

            try {
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (exc: Throwable) {
                Timber.tag(TAG).w(exc, "requestSingleUpdate failed provider=%s", provider)
                if (!cont.isCompleted) cont.resume(null)
            }

            cont.invokeOnCancellation {
                runCatching { lm.removeUpdates(listener) }
            }
        }

    private suspend fun audit(
        plugin: PluginInstance,
        capability: String,
        callId: String,
        startedAtElapsedMs: Long,
        outcome: String,
        precision: String? = null,
    ) {
        runCatching {
            auditLogger.insert(
                PluginCapabilityAuditRow(
                    pluginRowId = plugin.manifest.id,
                    capability = capability,
                    sandboxToken = (plugin.sandboxHandle?.sandboxToken ?: 0),
                    callId = callId,
                    atMs = nowWallClockMs(),
                    durationMs = nowElapsedMs() - startedAtElapsedMs,
                    outcome = outcome,
                    surface = "service_call",
                    precision = precision,
                ),
            )
        }.onFailure { Timber.tag(TAG).w(it, "audit insert failed") }
    }

    companion object {
        private const val TAG = "LocationBridge"

        /** Spec: 10s default; can be overridden via opts.timeoutMillis. */
        const val LOCATION_TIMEOUT_MS: Long = 10_000L
    }
}
