package app.syncler.plugin.runtime

/**
 * Entry point implemented by every native Kotlin plugin (Phase 11).
 *
 * Plugin DEX must declare a public class with a no-arg constructor
 * implementing this interface. The fully-qualified binary name of
 * that class is published in `PluginManifest.entry_class` and
 * resolved by the sandbox via
 * `InMemoryDexClassLoader.loadClass(entryClass)` after the bundle
 * SHA-256 + forbidden-prefix scan + SDK ABI check pass.
 *
 * Lifecycle:
 *  1. Sandbox instantiates the class.
 *  2. Sandbox calls [onInit] under a 10s `withTimeoutOrNull` guard;
 *     failure or timeout reports `LoadFailureCodes.INIT_TIMEOUT` /
 *     similar and tears the sandbox down.
 *  3. Once initialized, [onInbox] / [onAction] hooks fire as
 *     events arrive. The sandbox serializes them per-token via an
 *     internal `Channel` so concurrent host coroutines can't
 *     reorder delivery.
 *  4. On unload, the sandbox cancels its `CoroutineScope` which
 *     propagates to any in-flight hook call.
 *
 * Each call returns [Result] so transient errors round-trip back
 * to the host's audit log without crashing the plugin process.
 * Unhandled throws still propagate — the sandbox catches and
 * surfaces them as a structured error to the host.
 */
interface SynclerPlugin {
    suspend fun onInit(ctx: PluginContext): Result<Unit>

    suspend fun onInbox(ctx: PluginContext, event: InboxEvent): Result<Unit> =
        Result.success(Unit)

    suspend fun onAction(ctx: PluginContext, event: ActionEvent): Result<Unit> =
        Result.success(Unit)
}
