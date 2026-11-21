package org.adaptivedpi.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Job
import org.adaptivedpi.android.engine.EngineEmergencyStop
import org.adaptivedpi.android.engine.EngineHealthSource
import org.adaptivedpi.android.engine.EngineNetwork
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.KnownFlag
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnSnapshot
import java.util.UUID

class AdaptiveVpnService : VpnService() {
    private var sessionId: String? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var foregroundRequested = false
    private var termination = VpnEvent.ServiceDestroyed
    private val shutdown = VpnShutdown(::releaseResources) {
        runCatching { stopSelf() }.onFailure(::recordCleanupFailure)
    }
    private val stopHandler = Handler(Looper.getMainLooper())
    private val completeStop = Runnable { shutdown.stop() }
    private var engineMonitor: Job? = null
    private var engineEmergencyStop: EngineEmergencyStop? = null
    private var activeMarker: UUID? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (shutdown.started) {
            stopDeliveredStart(startId)
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_SESSION_ID)
        when (intent?.action) {
            ACTION_START -> if (sessionId == null) startTestSession(id)
            ACTION_STOP -> {
                if (id != null) VpnController.stop(id)
                if (sessionId == null) stopDeliveredStart(startId)
            }
            else -> if (sessionId == null) stopDeliveredStart(startId)
        }
        return START_NOT_STICKY
    }

    private fun stopDeliveredStart(startId: Int) {
        runCatching { stopSelfResult(startId) }.onFailure(::recordCleanupFailure)
    }

    private fun recordCleanupFailure(error: Throwable) {
        VpnDiagnostics.recordAsync(DiagnosticCode.VpnCleanupFailed, error, diagnosticSnapshot())
    }

    private fun startTestSession(id: String?) {
        if (id != null && VpnController.attachService(id) { finishSession() }) {
            sessionId = id
        }
        var failureCode = DiagnosticCode.ForegroundFailed
        try {
            // Даже устаревший ACTION_START мог прийти через startForegroundService.
            val attachedId = sessionId
            showForegroundNotification(attachedId)
            if (attachedId == null || VpnController.state.value == VpnState.Stopping) {
                finishSession()
                return
            }

            // Только собственный пакет и тестовые адреса. Маршруты и DNS не задаются.
            failureCode = DiagnosticCode.TunnelFailed
            tunnel = Builder()
                .setSession(getString(R.string.vpn_service_name))
                .addAllowedApplication(packageName)
                .addAddress("192.0.2.1", 32)
                .addAddress("2001:db8::1", 128)
                .establish()

            if (tunnel == null) {
                finishSession(VpnEvent.ConsentRevoked)
            } else {
                activeMarker = VpnDiagnostics.markActive(diagnosticSnapshot(VpnState.TestSessionRunning))
                VpnController.serviceStarted(attachedId)
            }
        } catch (exception: RuntimeException) {
            finishSession(VpnEvent.ServiceFailed, failureCode.takeIf { sessionId != null }, exception)
        }
    }

    /** Точка подключения будущего движка после создания проверяемого сеанса. Вызывается в главном потоке. */
    internal fun superviseEngine(
        source: EngineHealthSource,
        emergencyStop: EngineEmergencyStop,
        network: () -> EngineNetwork,
    ) {
        val id = checkNotNull(sessionId)
        check(!shutdown.started && engineMonitor == null)
        engineEmergencyStop = emergencyStop
        engineMonitor = monitorEngine(source, network, onConfirmed = {
            mainExecutor.execute {
                if (sessionId == id && !shutdown.started) VpnController.engineConfirmed(id)
            }
        }) { code, error ->
            mainExecutor.execute {
                if (sessionId == id && !shutdown.started) finishSession(VpnEvent.CriticalFailure, code, error)
            }
        }
    }

    override fun onRevoke() {
        // Android может вызвать отзыв разрешения вне главного потока.
        mainExecutor.execute {
            if (sessionId != null) finishSession(VpnEvent.ConsentRevoked)
        }
    }

    override fun onDestroy() {
        stopHandler.removeCallbacks(completeStop)
        val unexpected = sessionId != null && !shutdown.started &&
            VpnController.state.value in setOf(VpnState.Starting, VpnState.TestSessionRunning, VpnState.EngineRunning)
        val snapshot = diagnosticSnapshot()
        val marker = if (unexpected) retainActiveMarker() else null
        try {
            releaseResources()
        } finally {
            val id = sessionId
            sessionId = null
            if (id != null) {
                VpnController.serviceEnded(id, if (unexpected) VpnEvent.CriticalFailure else termination)
                if (unexpected) VpnDiagnostics.persistCritical(DiagnosticCode.VpnUnexpectedStop, null, snapshot, marker)
            }
            super.onDestroy()
        }
    }

    private fun finishSession(
        event: VpnEvent = VpnEvent.ServiceDestroyed,
        code: DiagnosticCode? = null,
        error: Throwable? = null,
    ) {
        if (shutdown.started) {
            stopHandler.removeCallbacks(completeStop)
            shutdown.stop()
            return
        }
        termination = event
        if (code == null) {
            shutdown.stop()
            return
        }
        val snapshot = diagnosticSnapshot()
        val marker = retainActiveMarker()
        if (event == VpnEvent.CriticalFailure) sessionId?.let(VpnController::serviceStopping)
        // TUN освобождается сразу. Медленный диск может задержать только stopSelf и в пределах дедлайна.
        shutdown.releaseNow()
        stopHandler.postDelayed(completeStop, JOURNAL_GRACE_MILLIS)
        VpnDiagnostics.persistCritical(code, error, snapshot, marker) {
            mainExecutor.execute {
                if (sessionId != null) {
                    stopHandler.removeCallbacks(completeStop)
                    shutdown.stop()
                }
            }
        }
    }

    private fun retainActiveMarker(): UUID? = activeMarker?.also {
        activeMarker = null
        VpnDiagnostics.endActive(it, keepMarker = true)
    }

    private fun releaseResources() {
        val watcher = engineMonitor
        engineMonitor = null
        val emergency = engineEmergencyStop
        engineEmergencyStop = null
        val descriptor = tunnel
        tunnel = null
        val removeNotification = foregroundRequested
        foregroundRequested = false
        val clearMarker = activeMarker
        activeMarker = null
        releaseVpnResources(
            ::recordCleanupFailure,
            { watcher?.cancel() },
            { descriptor?.close() },
            { emergency?.closeTunnel() },
            { emergency?.requestStop() },
            { if (removeNotification) stopForeground(STOP_FOREGROUND_REMOVE) },
            { clearMarker?.let { VpnDiagnostics.endActive(it) } },
        )
    }

    private fun diagnosticSnapshot(state: VpnState = VpnController.state.value) = VpnSnapshot(
        state,
        systemFlag { isAlwaysOn },
        systemFlag { isLockdownEnabled },
    )

    private fun systemFlag(read: () -> Boolean) = runCatching {
        if (read()) KnownFlag.Yes else KnownFlag.No
    }.getOrDefault(KnownFlag.Unknown)

    private fun showForegroundNotification(id: String?) {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_message))
            .setOngoing(true)
            .setShowWhen(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        if (id != null) {
            // Extras не участвуют в идентичности PendingIntent. URI разделяет уведомления сеансов.
            val intent = Intent(this, AdaptiveVpnService::class.java)
                .setAction(ACTION_STOP)
                .setData(Uri.fromParts("adaptive-dpi", id, null))
                .putExtra(EXTRA_SESSION_ID, id)
            val stopIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, getString(R.string.vpn_notification_stop), stopIntent).build())
        }

        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            builder.setContentIntent(PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE))
        }

        val notification = builder.build()
        foregroundRequested = true
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        }
        startForeground(NOTIFICATION_ID, notification, type)
    }

    companion object {
        internal const val ACTION_START = "org.adaptivedpi.android.vpn.START"
        internal const val EXTRA_SESSION_ID = "org.adaptivedpi.android.vpn.SESSION_ID"
        private const val ACTION_STOP = "org.adaptivedpi.android.vpn.STOP"
        private const val CHANNEL_ID = "vpn_session"
        private const val NOTIFICATION_ID = 1
        private const val JOURNAL_GRACE_MILLIS = 250L
    }
}
