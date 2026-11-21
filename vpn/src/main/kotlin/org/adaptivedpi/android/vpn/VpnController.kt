package org.adaptivedpi.android.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics
import java.util.UUID

data class VpnConsentRequest(val id: String, val intent: Intent)

object VpnController {
    private val processId = UUID.randomUUID().toString()
    private val lifecycle = VpnLifecycle()
    val state = lifecycle.state

    // Запросы Activity и события службы обрабатываются в главном потоке.
    fun requestStart(context: Context): VpnConsentRequest? {
        val id = "$processId:${UUID.randomUUID()}"
        if (!lifecycle.beginConsent(id)) return null

        return try {
            val intent = VpnService.prepare(context)
            if (intent == null) {
                start(context, id)
                null
            } else {
                VpnConsentRequest(id, intent)
            }
        } catch (exception: RuntimeException) {
            consentLaunchFailed(id, exception)
            null
        }
    }

    fun completeConsent(context: Context, id: String, granted: Boolean) {
        // В новом процессе восстанавливается только запрос, для которого Android доставил результат.
        if (!id.startsWith("$processId:")) lifecycle.beginConsent(id, restoring = true)
        if (!lifecycle.isAwaitingConsent(id)) return

        if (!granted) {
            lifecycle.update(id, VpnEvent.ConsentDenied)
            return
        }

        try {
            if (VpnService.prepare(context) == null) {
                start(context, id)
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
            lifecycle.update(id, VpnEvent.ServiceFailed)
            VpnDiagnostics.record(DiagnosticCode.ConsentFailed, exception)
        }
    }

    fun stop() = lifecycle.stop()

    internal fun stop(id: String) = lifecycle.stop(id)
    internal fun attachService(id: String, stop: () -> Unit) = lifecycle.attachService(id, stop)
    internal fun serviceStarted(id: String) = lifecycle.update(id, VpnEvent.ServiceStarted)
    internal fun engineConfirmed(id: String) = lifecycle.update(id, VpnEvent.EngineConfirmed)
    internal fun serviceStopping(id: String) = lifecycle.update(id, VpnEvent.StopRequested)
    internal fun serviceEnded(id: String, event: VpnEvent) = lifecycle.serviceEnded(id, event)

    private fun start(context: Context, id: String) {
        lifecycle.update(id, VpnEvent.StartRequested)
        val intent = Intent(context, AdaptiveVpnService::class.java)
            .setAction(AdaptiveVpnService.ACTION_START)
            .putExtra(AdaptiveVpnService.EXTRA_SESSION_ID, id)
        try {
            checkNotNull(context.startForegroundService(intent))
        } catch (exception: RuntimeException) {
            lifecycle.update(id, VpnEvent.ServiceFailed)
            VpnDiagnostics.record(DiagnosticCode.VpnStartFailed, exception)
        }
    }

}
