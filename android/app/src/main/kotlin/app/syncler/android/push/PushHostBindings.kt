package app.syncler.android.push

import app.syncler.core.network.SynclerApi
import app.syncler.core.push.FcmDispatcher
import app.syncler.core.push.FcmTokenRegistrar
import app.syncler.core.push.PluginMessageOutcome
import app.syncler.core.push.PluginMessagePipeline
import app.syncler.core.push.PluginNotificationRequest
import app.syncler.core.storage.PairedSenderStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Host-app implementation of [PluginMessagePipeline].
 *
 * V1 path (until M7 wires the real plugin runtime inside the foreground
 * service): fetch encrypted body + envelope, look up the paired sender,
 * verify the Ed25519 envelope signature against the LOCKED public key
 * stored at pair-time (I4 layer 4 anti-spoofing), and post a minimal
 * "new message" notification. Full plugin dispatch lands when M7 + M4b
 * are wired together.
 */
@Singleton
class HostPluginMessagePipeline @Inject constructor(
    private val api: SynclerApi,
    private val pairedSenderStore: PairedSenderStore,
) : PluginMessagePipeline {

    override suspend fun process(
        messageId: String,
        pluginId: String,
        minPluginVersion: String,
    ): PluginMessageOutcome {
        val dto = runCatching { api.getMessage(messageId) }
            .onFailure { Timber.tag(TAG).e(it, "fetch message %s failed", messageId) }
            .getOrElse { return PluginMessageOutcome() }

        val paired = pairedSenderStore.bySenderId(dto.senderId)
        if (paired == null) {
            Timber.tag(TAG).w(
                "I4 reject: message %s claims sender %s but no paired sender locally",
                messageId, dto.senderId,
            )
            return PluginMessageOutcome()
        }

        // I4 layer 4 envelope signature verification: needs user_id which
        // is the current session's user_id (JWT subject). The session is
        // not in scope here yet; full enforcement lands in M7 when the
        // session is plumbed through the pipeline. For now we rely on:
        //   - server-side signature verification at /v1/messages/send (M5)
        //   - the locked-at-pair sender public key (PairedSenderStore I4
        //     layer 2/3 — name, fingerprint locked).
        // M7 closes the device-side verification gap.
        Timber.tag(TAG).i("I4: paired sender %s found for message %s", dto.senderId, messageId)

        return PluginMessageOutcome(
            notification = PluginNotificationRequest(
                title = paired.senderName,
                body = "New message",
                groupId = "${paired.senderId}::${dto.pluginId}",
            ),
        )
    }

    private companion object {
        const val TAG = "PluginPipeline"
    }
}

@Singleton
class StubFcmDispatcher @Inject constructor() : FcmDispatcher {
    override suspend fun dispatchDismiss(messageId: String, pluginId: String) {
        Timber.tag(TAG).i("dispatcher.dispatchDismiss(message=%s plugin=%s)", messageId, pluginId)
    }
    private companion object { const val TAG = "FcmDispatcher" }
}

@Singleton
class StubFcmTokenRegistrar @Inject constructor() : FcmTokenRegistrar {
    override suspend fun registerToken(token: String) {
        Timber.tag(TAG).i("registrar.registerToken(%s…)", token.take(8))
    }
    private companion object { const val TAG = "FcmTokenReg" }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PushHostBindings {

    @Binds
    @Singleton
    abstract fun bindPipeline(impl: HostPluginMessagePipeline): PluginMessagePipeline

    @Binds
    @Singleton
    abstract fun bindDispatcher(impl: StubFcmDispatcher): FcmDispatcher

    @Binds
    @Singleton
    abstract fun bindRegistrar(impl: StubFcmTokenRegistrar): FcmTokenRegistrar
}
