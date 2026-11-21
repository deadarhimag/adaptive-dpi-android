package org.adaptivedpi.android.vpn

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

internal fun releaseVpnResources(onError: (Throwable) -> Unit, vararg steps: () -> Unit) {
    var failure: Throwable? = null
    for (step in steps) {
        runCatching(step).onFailure { error -> if (failure == null) failure = error }
    }
    failure?.let { runCatching { onError(it) } }
}
