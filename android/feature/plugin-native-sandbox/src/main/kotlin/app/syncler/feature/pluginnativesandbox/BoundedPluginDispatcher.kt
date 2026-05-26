package app.syncler.feature.pluginnativesandbox

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Per-plugin bounded dispatcher (Phase 11). One instance per
 * loaded native plugin token; backed by a small ThreadPoolExecutor
 * that scales from 1 core thread up to [MAX_POOL] non-core threads
 * with a bounded queue.
 *
 * Why bounded: a runaway plugin could otherwise spawn unbounded
 * Tasks via the SDK's coroutine extensions and exhaust the
 * sandbox process's heap. With a fixed cap, runaway dispatch
 * causes the executor to reject submissions (caller-runs policy
 * keeps the host responsive); the offending plugin saturates its
 * own pool and the sandbox unloads it on the next failed hook.
 *
 * This is process-local — the isolated sandbox process hosts ONE
 * plugin (per Phase 11a v4 design via bindIsolatedService) so the
 * dispatcher's lifetime equals that of the plugin token.
 */
internal class BoundedPluginDispatcher private constructor(
    private val executor: ThreadPoolExecutor,
    val dispatcher: CoroutineDispatcher,
) : AutoCloseable {

    override fun close() {
        // Cancel queued work; let in-flight tasks unwind via the
        // surrounding scope.cancel() that the coordinator already
        // fires before close().
        executor.shutdownNow()
        if (dispatcher is ExecutorCoroutineDispatcher) dispatcher.close()
    }

    companion object {
        private const val CORE_POOL = 1
        private const val MAX_POOL = 4
        private const val KEEP_ALIVE_SECONDS = 30L

        /**
         * Caller-runs rejection: when the pool + queue saturate,
         * dispatching falls back to the calling thread instead of
         * dropping work silently. Keeps the host's Binder thread
         * responsive (it'll just take longer to deliver the next
         * hook) and surfaces saturation as latency rather than
         * dropped events.
         */
        fun create(tokenLabel: String): BoundedPluginDispatcher {
            val counter = AtomicInteger(0)
            val tf = ThreadFactory { r ->
                Thread(r, "syncler-plugin-$tokenLabel-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
            val executor = ThreadPoolExecutor(
                CORE_POOL,
                MAX_POOL,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                java.util.concurrent.LinkedBlockingQueue(64),
                tf,
                ThreadPoolExecutor.CallerRunsPolicy(),
            )
            return BoundedPluginDispatcher(executor, executor.asCoroutineDispatcher())
        }
    }
}
