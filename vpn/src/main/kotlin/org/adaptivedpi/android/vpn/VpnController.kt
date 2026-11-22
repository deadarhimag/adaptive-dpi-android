package org.adaptivedpi.android.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnEventHistory
import org.adaptivedpi.android.vpn.diagnostics.VpnEventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class VpnConsentRequest(val id: String, val intent: Intent)

object VpnController {
    private val processId = UUID.randomUUID().toString()
    private val lifecycle = VpnLifecycle(VpnEventHistory::record)
    val state = lifecycle.state
    private val transportSnapshot = MutableStateFlow<TransportSnapshot?>(null)
    val transport = transportSnapshot.asStateFlow()
    val processIdentity get() = processId

    fun activityRestored(previousProcessIdentity: String) {
        lifecycle.activityRestored(previousProcessIdentity == processId)
    }

    fun availability(context: Context): VpnAvailability = runCatching {
        observeVpnAvailability(context, state.value)
    }.getOrDefault(VpnAvailability.Unknown)

    // Запросы Activity и события службы обрабатываются в главном потоке.
    fun requestStart(context: Context, target: String? = null, applicationPackage: String? = null): VpnConsentRequest? {
        require(target == null || applicationPackage == null)
        val id = "$processId:${UUID.randomUUID()}"
        if (!lifecycle.beginConsent(id)) return null
        transportSnapshot.value = null

        return try {
            val intent = VpnService.prepare(context)
            if (intent == null) {
                lifecycle.permissionResult(id, VpnEventType.PermissionAlreadyGranted)
                start(context, id, target, applicationPackage)
                null
            } else {
                VpnConsentRequest(id, intent)
            }
        } catch (exception: RuntimeException) {
            consentLaunchFailed(id, exception)
            null
        }
    }

    fun consentLaunched(id: String) = lifecycle.permissionResult(id, VpnEventType.PermissionRequested)

    fun completeConsent(context: Context, id: String, granted: Boolean, target: String? = null, applicationPackage: String? = null) {
        // В новом процессе восстанавливается только запрос, для которого Android доставил результат.
        if (!id.startsWith("$processId:")) lifecycle.beginConsent(id, restoring = true)
        if (!lifecycle.isAwaitingConsent(id)) return

        if (!granted) {
            lifecycle.update(id, VpnEvent.ConsentDenied)
            return
        }

        lifecycle.permissionResult(id, VpnEventType.PermissionGranted)
        try {
            // После диалога Android право могло уже перейти другому приложению.
            if (VpnService.prepare(context) == null) {
                start(context, id, target, applicationPackage)
            } else {
                lifecycle.update(id, VpnEvent.ConsentRevoked)
            }
        } catch (exception: RuntimeException) {
            consentLaunchFailed(id, exception)
        }
    }

    fun abandonConsent(id: String) {
        if (lifecycle.isAwaitingConsent(id)) lifecycle.update(id, VpnEvent.ConsentAbandoned)
    }

    fun consentLaunchFailed(id: String, exception: RuntimeException) {
        if (lifecycle.isAwaitingConsent(id)) {
            lifecycle.update(id, VpnEvent.ServiceFailed, DiagnosticCode.ConsentFailed)
            VpnDiagnostics.record(DiagnosticCode.ConsentFailed, exception)
        }
    }

    fun stop() = lifecycle.stop()

    internal fun stopFromNotification(id: String) = lifecycle.stop(id, fromNotification = true)
    internal fun attachService(id: String, stop: () -> Unit) = lifecycle.attachService(id, stop)
    internal fun serviceStarted(id: String) = lifecycle.update(id, VpnEvent.ServiceStarted)
    internal fun transportTunCreated(id: String) = lifecycle.update(id, VpnEvent.TransportTunCreated)
    internal fun updateTransport(snapshot: TransportSnapshot) { transportSnapshot.value = snapshot }
    internal fun engineConfirmed(id: String) = lifecycle.update(id, VpnEvent.EngineConfirmed)
    internal fun serviceStopping(id: String) = lifecycle.update(id, VpnEvent.StopRequested)
    internal fun serviceEnded(id: String, event: VpnEvent, code: DiagnosticCode?) =
        lifecycle.serviceEnded(id, event, code)

    private fun start(context: Context, id: String, target: String?, applicationPackage: String?) {
        lifecycle.update(id, VpnEvent.StartRequested)
        val intent = Intent(context, AdaptiveVpnService::class.java)
            .setAction(AdaptiveVpnService.ACTION_START)
            .putExtra(AdaptiveVpnService.EXTRA_SESSION_ID, id)
            .putExtra(AdaptiveVpnService.EXTRA_TARGET, target)
            .putExtra(AdaptiveVpnService.EXTRA_APPLICATION, applicationPackage)
        try {
            checkNotNull(context.startForegroundService(intent))
        } catch (exception: RuntimeException) {
            lifecycle.update(id, VpnEvent.ServiceFailed, DiagnosticCode.VpnStartFailed)
            VpnDiagnostics.record(DiagnosticCode.VpnStartFailed, exception)
        }
    }

}
