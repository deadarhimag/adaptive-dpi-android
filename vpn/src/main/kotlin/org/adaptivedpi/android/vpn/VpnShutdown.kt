package org.adaptivedpi.android.vpn

import org.adaptivedpi.android.vpn.diagnostics.CleanupStep

internal class VpnShutdown(
    private val release: () -> Unit,
    private val stopService: () -> Unit,
) {
    var started: Boolean = false
        private set
    private var stopped: Boolean = false

    fun releaseNow() {
        if (started) return
        started = true
        release()
    }

    fun stop(report: () -> Unit = {}) {
        if (stopped) return
        stopped = true
        try {
            releaseNow()
        } finally {
            try {
                stopService()
            } finally {
                report()
            }
        }
    }
}

internal fun releaseVpnResources(onError: (CleanupStep, Throwable) -> Unit, vararg steps: Pair<CleanupStep, () -> Unit>) {
    val failures = steps.mapNotNull { (step, operation) ->
        runCatching(operation).exceptionOrNull()?.let { step to it }
    }
    failures.forEach { (step, error) -> onError(step, error) }
}
