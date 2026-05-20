package app.syncler.android.push

import app.syncler.core.push.FcmDispatcher
import app.syncler.core.push.FcmTokenRegistrar
import app.syncler.core.push.PluginMessageOutcome
import app.syncler.core.push.PluginMessagePipeline
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Host-app stubs that satisfy the Hilt graph for :core:push.
 *
 * Real implementations land in M6 (pairing/sender identity) and M7
 * (sync). For now these are no-ops with explicit logging so the app
 * compiles and runs end-to-end without crashing on missing bindings.
 *
 * Tests in :core:push exercise the contract data classes directly,
 * independent of these stubs.
 */
@Singleton
class StubPluginMessagePipeline @Inject constructor() : PluginMessagePipeline {
    override suspend fun process(
        messageId: String,
        pluginId: String,
        minPluginVersion: String,
    ): PluginMessageOutcome {
        Timber.tag(TAG).i("stub pipeline.process(message=%s plugin=%s)", messageId, pluginId)
        return PluginMessageOutcome()
    }

    private companion object {
        const val TAG = "PluginPipelineStub"
    }
}

@Singleton
class StubFcmDispatcher @Inject constructor() : FcmDispatcher {
    override suspend fun dispatchDismiss(messageId: String, pluginId: String) {
        Timber.tag(TAG).i("stub dispatcher.dispatchDismiss(message=%s plugin=%s)", messageId, pluginId)
    }

    private companion object {
        const val TAG = "FcmDispatcherStub"
    }
}

@Singleton
class StubFcmTokenRegistrar @Inject constructor() : FcmTokenRegistrar {
    override suspend fun registerToken(token: String) {
        Timber.tag(TAG).i("stub registrar.registerToken(token=%s…)", token.take(8))
    }

    private companion object {
        const val TAG = "FcmTokenRegStub"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PushHostBindings {

    @Binds
    @Singleton
    abstract fun bindPipeline(impl: StubPluginMessagePipeline): PluginMessagePipeline

    @Binds
    @Singleton
    abstract fun bindDispatcher(impl: StubFcmDispatcher): FcmDispatcher

    @Binds
    @Singleton
    abstract fun bindRegistrar(impl: StubFcmTokenRegistrar): FcmTokenRegistrar
}
