package org.adaptivedpi.android.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Все обращения к экземпляру выполняются в главном потоке процесса. */
internal class VpnLifecycle {
    private val currentState = MutableStateFlow(VpnState.Idle)
    private var operationId: String? = null
    private var stopService: (() -> Unit)? = null
    private var stopIssued = false
    val state = currentState.asStateFlow()

    fun beginConsent(id: String, restoring: Boolean = false): Boolean {
        if (!state.value.canStart || (restoring && state.value != VpnState.Idle)) return false
        operationId = id
        stopIssued = false
        update(id, VpnEvent.ConsentRequired)
        return true
    }

    fun isAwaitingConsent(id: String): Boolean =
        id == operationId && state.value == VpnState.AwaitingConsent

    fun update(id: String, event: VpnEvent) {
        if (id != operationId) return
        val nextState = state.value.reduce(event)
        if (nextState.canStart) operationId = null
        currentState.value = nextState
    }

    fun attachService(id: String, stop: () -> Unit): Boolean {
        if (id != operationId || stopService != null) return false
        if (state.value != VpnState.Starting && state.value != VpnState.Stopping) return false
        stopService = stop
        return true
    }

    fun stop(id: String? = operationId) {
        if (id == null || id != operationId || !state.value.canStop || stopIssued) return
        stopIssued = true
        update(id, VpnEvent.StopRequested)
        stopService?.invoke()
    }

    fun serviceEnded(id: String, event: VpnEvent) {
        if (id != operationId || stopService == null) return
        stopService = null
        update(id, event)
    }
}
