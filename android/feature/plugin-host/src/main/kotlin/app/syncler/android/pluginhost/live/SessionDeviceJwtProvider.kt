package app.syncler.android.pluginhost.live

import app.syncler.android.pluginhost.AuditLogger
import app.syncler.core.auth.Session
import timber.log.Timber

/**
 * V3 #14 device-JWT provider for [LiveChannelClient].
 *
 * Triad 160 codex+gemini DESIGN: extracted from
 * `PluginLoader.android()`'s anonymous factory so the
 * three behavioral branches can be unit-tested directly:
 *
 *   1. Production wiring: `session != null` →
 *      `session.currentToken()` returns the device JWT.
 *      Returned as-is.
 *   2. Production wiring, session unlocked-but-no-token
 *      (locked / signed out) → `currentToken()` returns
 *      null → throw `LiveChannelException("no_session",
 *      "no device JWT available (locked / signed out)")`.
 *   3. No wiring (composition-root bug or test-build
 *      default): `session == null` → throw
 *      `LiveChannelException("no_session", "session not
 *      wired into PluginLoader.android()")` with a
 *      distinct audit-log key so the wiring gap can't
 *      silently masquerade as a locked-state error.
 *
 * Construction is intentionally lightweight — the
 * provider is built per-plugin (because the audit-log
 * tagging is plugin-scoped) but holds only references
 * to the shared Session + AuditLogger.
 */
class SessionDeviceJwtProvider(
    private val session: Session?,
    private val pluginId: String,
    private val auditLogger: AuditLogger,
) {

    /**
     * Resolves the device JWT. Marked `suspend` to match the
     * `LiveChannelClient.deviceJwtProvider` shape — current
     * impl doesn't suspend but the contract reserves the
     * right (e.g. future async token refresh).
     */
    suspend operator fun invoke(): String {
        if (session == null) {
            auditLogger.record(
                pluginId,
                AUDIT_KEY_NO_SESSION_WIRED,
                "no Session wired into PluginLoader.android(); live disabled",
            )
            Timber.tag(TAG).e(
                "deviceJwtProvider: no Session wired — live channel unusable",
            )
            throw LiveChannelException(
                "no_session",
                "session not wired into PluginLoader.android()",
            )
        }
        return session.currentToken()
            ?: throw LiveChannelException(
                "no_session",
                "no device JWT available (locked / signed out)",
            )
    }

    companion object {
        const val TAG = "LiveBridge"
        /** Audit key when the composition root forgot the Session arg. */
        const val AUDIT_KEY_NO_SESSION_WIRED = "live_no_session"
    }
}
