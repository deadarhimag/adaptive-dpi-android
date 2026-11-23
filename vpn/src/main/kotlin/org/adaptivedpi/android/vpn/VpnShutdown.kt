package org.adaptivedpi.android.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.adaptivedpi.android.vpn.diagnostics.CleanupStep

/** Вызывается в потоке, который владеет состоянием службы. Таймер наблюдает, очистка владеет ресурсами. */
internal class VpnShutdown<T>(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
    private val clock: () -> Long,
    private val release: suspend () -> T,
    private val onDelay: () -> Unit,
    private val onComplete: (Result<T>) -> Unit,
) {
    @Volatile var started: Boolean = false
        private set

    fun start() {
        if (started) return
        started = true
        val warnAt = clock() + delayMillis
        scope.launch {
            var delayReported = false
            val observer = launch {
                delay((warnAt - clock()).coerceAtLeast(0))
                delayReported = true
                onDelay()
            }
            val result = try {
                runCatching { release() }
            } finally {
                observer.cancel()
            }
            if (!delayReported && clock() >= warnAt) onDelay()
            onComplete(result)
        }
    }
}

/** Опрос больше не удерживает команду остановки. Владение ресурсами сохраняется до завершения обоих. */
internal suspend fun requestStopAndAwaitPoll(poll: Job?, requestStop: suspend () -> Unit) {
    requestStop()
    poll?.join()
}

/** Наблюдает опрос с диспетчера службы. Владельцем ресурсов остаётся транспорт. */
internal suspend fun <T> observeTransportPoll(
    warnAfterMillis: Long,
    onDelay: () -> Unit,
    poll: suspend () -> T,
): T = coroutineScope {
    val observer = launch {
        delay(warnAfterMillis)
        onDelay()
    }
    try {
        poll()
    } finally {
        observer.cancel()
    }
}

internal fun releaseVpnResources(onError: (CleanupStep, Throwable) -> Unit, vararg steps: Pair<CleanupStep, () -> Unit>) {
    val failures = steps.mapNotNull { (step, operation) ->
        runCatching(operation).exceptionOrNull()?.let { step to it }
    }
    failures.forEach { (step, error) -> onError(step, error) }
}
