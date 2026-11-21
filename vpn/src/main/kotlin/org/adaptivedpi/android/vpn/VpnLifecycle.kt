package org.adaptivedpi.android.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.VpnEventType
import org.adaptivedpi.android.vpn.diagnostics.VpnJournalEvent
import java.util.UUID

/** Все обращения к экземпляру выполняются в главном потоке процесса. */
internal class VpnLifecycle(private val record: (VpnJournalEvent) -> Unit = {}) {
    private val currentState = MutableStateFlow(VpnState.Idle)
    private var operationId: String? = null
    private var stopService: (() -> Unit)? = null
    private var stopIssued = false
    private val recordedPermissionResults = mutableSetOf<VpnEventType>()
    val state = currentState.asStateFlow()

    fun beginConsent(id: String, restoring: Boolean = false): Boolean {
        if (!state.value.canStart || (restoring && state.value != VpnState.Idle)) return false
        operationId = id
        stopIssued = false
        recordedPermissionResults.clear()
        update(id, VpnEvent.ConsentRequired)
        return true
    }

    fun isAwaitingConsent(id: String): Boolean =
        id == operationId && state.value == VpnState.AwaitingConsent

    fun update(id: String, event: VpnEvent, code: DiagnosticCode? = null) {
        if (id != operationId) return
        val previous = state.value
        val nextState = previous.reduce(event)
        if (nextState == previous) return
        if (nextState.canStart) operationId = null
        currentState.value = nextState
        val type = when (event) {
            VpnEvent.ConsentDenied -> VpnEventType.PermissionDenied
            VpnEvent.ConsentAbandoned -> VpnEventType.PermissionAbandoned
            VpnEvent.StartRequested -> VpnEventType.StartRequested
            VpnEvent.ServiceStarted -> VpnEventType.Started
            VpnEvent.TransportTunCreated -> VpnEventType.TransportTunCreated
            VpnEvent.ConsentRevoked -> VpnEventType.PermissionUnavailable
            VpnEvent.SystemConsentRevoked -> VpnEventType.SystemRevoked
            VpnEvent.CriticalFailure -> VpnEventType.UnexpectedStop
            VpnEvent.ServiceFailed -> when {
                previous == VpnState.AwaitingConsent -> VpnEventType.PermissionError
                else -> VpnEventType.StartError
            }
            VpnEvent.ServiceDestroyed -> if (nextState == VpnState.StartFailed) {
                VpnEventType.StartError
            } else {
                VpnEventType.Stopped
            }
            else -> null
        }
        type?.let { record(it, id, code) }
    }

    fun permissionResult(id: String, type: VpnEventType) {
        if (isAwaitingConsent(id) && recordedPermissionResults.add(type)) record(type, id)
    }

    fun activityRestored(sameProcess: Boolean) {
        val type = if (sameProcess) VpnEventType.ActivityRestored else VpnEventType.ProcessStateRestored
        record(type, operationId)
    }

    fun attachService(id: String, stop: () -> Unit): Boolean {
        if (id != operationId || stopService != null) return false
        if (state.value != VpnState.Starting && state.value != VpnState.Stopping) return false
        stopService = stop
        return true
    }

    fun stop(id: String? = operationId, fromNotification: Boolean = false) {
        if (id == null || id != operationId || !state.value.canStop || stopIssued) return
        stopIssued = true
        update(id, VpnEvent.StopRequested)
        record(if (fromNotification) VpnEventType.StopFromNotification else VpnEventType.StopFromApp, id)
        stopService?.invoke()
    }

    fun serviceEnded(id: String, event: VpnEvent, code: DiagnosticCode? = null) {
        if (id != operationId || stopService == null) return
        stopService = null
        update(id, event, code)
    }

    private fun record(type: VpnEventType, id: String?, code: DiagnosticCode? = null) {
        record(VpnJournalEvent.create(type, id?.toSessionUuid(), code, state.value))
    }
}

internal fun String.toSessionUuid(): UUID? = runCatching {
    UUID.fromString(substringAfterLast(':'))
}.getOrNull()
