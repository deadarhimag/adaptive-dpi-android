package org.adaptivedpi.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.adaptivedpi.android.engine.EngineEmergencyStop
import org.adaptivedpi.android.engine.EngineHealthSource
import org.adaptivedpi.android.engine.EngineNetwork
import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode
import org.adaptivedpi.android.engine.TransportFailure
import org.adaptivedpi.android.engine.TransportOperation
import org.adaptivedpi.android.engine.TransportSite
import org.adaptivedpi.android.engine.TransportSocketException
import org.adaptivedpi.android.engine.TransportSubsystem
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.KnownFlag
import org.adaptivedpi.android.vpn.diagnostics.TransportDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.TransportSessionDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnEventHistory
import org.adaptivedpi.android.vpn.diagnostics.VpnEventType
import org.adaptivedpi.android.vpn.diagnostics.VpnSnapshot
import java.io.IOException
import java.net.Inet4Address
import java.util.UUID

class AdaptiveVpnService : VpnService() {
    private var sessionId: String? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var foregroundRequested = false
    private var termination = VpnEvent.ServiceDestroyed
    private var terminationCode: DiagnosticCode? = null
    private var cleanupErrorRecorded = false
    private val shutdown = VpnShutdown(::releaseResources) {
        val closing = closingTransport
        if (closing == null) {
            stopService()
        } else {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                closing.finished.await()
                stopService()
            }
        }
    }
    private val stopHandler = Handler(Looper.getMainLooper())
    private val completeStop = Runnable { shutdown.stop() }
    private var sessionStart: Job? = null
    private var engineMonitor: Job? = null
    private var engineEmergencyStop: EngineEmergencyStop? = null
    private var activeMarker: UUID? = null
    private var transport: ExperimentalTransport? = null
    private var transportDiagnostics: TransportSessionDiagnostics? = null
    private var closingTransport: ExperimentalTransport? = null
    private val networkCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private val connectivity by lazy { checkNotNull(getSystemService(ConnectivityManager::class.java)) }
    private var transportReadyAt = 0L
    private var transportConfirmed = false
    private var trafficConfirmed = false
    private var transportErrorReported = false
    private val refreshTransport = Runnable { inspectTransport() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (shutdown.started) {
            stopDeliveredStart(startId)
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_SESSION_ID)
        when (intent?.action) {
            ACTION_START -> if (sessionId == null) {
                sessionStart = CoroutineScope(Dispatchers.Main.immediate).launch {
                    startSession(id, intent.getStringExtra(EXTRA_TARGET), intent.getStringExtra(EXTRA_APPLICATION))
                }
            }
            ACTION_STOP -> {
                if (id != null) VpnController.stopFromNotification(id)
                if (sessionId == null) stopDeliveredStart(startId)
            }
            else -> if (sessionId == null) stopDeliveredStart(startId)
        }
        return START_NOT_STICKY
    }

    private fun stopDeliveredStart(startId: Int) {
        if (closingTransport?.finished?.isCompleted == false) return
        runCatching { stopSelfResult(startId) }.onFailure(::recordCleanupFailure)
    }

    private fun stopService() {
        runCatching { stopSelf() }.onFailure(::recordCleanupFailure)
    }

    private fun recordCleanupFailure(error: Throwable) {
        transportDiagnostics?.reporter()?.recordError(LIFECYCLE_STOP, error, fallback = TransportCategory.CLEANUP)
        if (!cleanupErrorRecorded) {
            cleanupErrorRecorded = true
            VpnEventHistory.record(
                VpnEventType.StopError, sessionId?.toSessionUuid(), DiagnosticCode.VpnCleanupFailed, VpnController.state.value,
            )
        }
        VpnDiagnostics.recordAsync(DiagnosticCode.VpnCleanupFailed, error, diagnosticSnapshot())
    }

    private suspend fun startSession(id: String?, experimentalTarget: String?, applicationPackage: String?) {
        if (id != null && VpnController.attachService(id) { finishSession() }) {
            sessionId = id
        }
        var failureCode = DiagnosticCode.ForegroundFailed
        try {
            // Даже устаревший ACTION_START мог прийти через startForegroundService.
            val attachedId = sessionId
            val experiment = experimentalTarget != null || applicationPackage != null
            if (experiment) transportDiagnostics = attachedId?.toSessionUuid()?.let(TransportDiagnostics::newSession)
            showForegroundNotification(attachedId, experiment)
            if (attachedId == null || VpnController.state.value == VpnState.Stopping) {
                finishSession()
                return
            }

            failureCode = DiagnosticCode.VpnCleanupFailed
            ExperimentalTransport.requirePreviousRelayStopped()
            failureCode = DiagnosticCode.TunnelFailed
            val builder = Builder()
                .setSession(getString(R.string.vpn_service_name))
                .addAddress("192.0.2.1", 32)
            if (applicationPackage != null) {
                failureCode = DiagnosticCode.TransportApplicationUnavailable
                require(experimentalTarget == null && applicationPackage != packageName && ExperimentalApplication.isPackageName(applicationPackage))
                builder.addAllowedApplication(applicationPackage)
                failureCode = DiagnosticCode.TunnelFailed
            } else {
                builder.addAllowedApplication(packageName)
            }
            if (experimentalTarget == null && applicationPackage == null) {
                builder.addAddress("2001:db8::1", 128)
            } else {
                val target = experimentalTarget?.let { ExperimentalTarget.parse(it) ?: throw IOException("Недопустимый тестовый адрес.") }
                val physical = awaitPhysicalNetwork()
                    ?: throw TransportSocketException(TransportCode.NO_PHYSICAL_NETWORK, TransportCategory.UNREACHABLE)
                builder.setMtu(1280).setUnderlyingNetworks(arrayOf(physical))
                if (target != null) {
                    builder.addRoute(target, 32)
                } else {
                    failureCode = DiagnosticCode.TransportIpv4Unavailable
                    val link = connectivity.getLinkProperties(physical)
                    val dns = link?.dnsServers.orEmpty().filterIsInstance<Inet4Address>()
                        .filterNot { it.isLoopbackAddress || it.isAnyLocalAddress || it.isMulticastAddress }
                    if (link?.linkAddresses?.any { it.address is Inet4Address } != true || dns.isEmpty()) {
                        throw TransportSocketException(TransportCode.IPV4_DNS_UNAVAILABLE, TransportCategory.UNREACHABLE)
                    }
                    builder.addRoute("0.0.0.0", 0).setMetered(false)
                    dns.forEach { builder.addDnsServer(it) }
                    failureCode = DiagnosticCode.TunnelFailed
                }
                val descriptor = establishTunnel(builder) ?: return
                VpnController.transportTunCreated(attachedId)
                transportDiagnostics?.stateChanged(VpnState.TransportStarting)
                if (applicationPackage != null) recordTransportEvent(VpnEventType.ApplicationRoutesConfigured)
                activeMarker = VpnDiagnostics.markActive(diagnosticSnapshot(), attachedId.toSessionUuid())
                failureCode = DiagnosticCode.EngineFailed
                recordTransportEvent(VpnEventType.TransportInitializing)
                val started = ExperimentalTransport(this, physical, target, descriptor, checkNotNull(transportDiagnostics)) {
                    mainExecutor.execute { failTransport(attachedId) }
                }
                transport = started
                tunnel = null // Дескриптор закрывает рабочий поток после выхода HEV.
                transportReadyAt = SystemClock.elapsedRealtime() + 5_000
                started.start { failed ->
                    if (failed) mainExecutor.execute { failTransport(attachedId) }
                }
                watchPhysicalNetwork(attachedId, physical)
                stopHandler.post(refreshTransport)
                return
            }

            establishTunnel(builder) ?: return
            activeMarker = VpnDiagnostics.markActive(diagnosticSnapshot(VpnState.TestSessionRunning), attachedId.toSessionUuid())
            VpnController.serviceStarted(attachedId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            recordTransportStartFailure(failureCode, exception)
            finishSession(VpnEvent.ServiceFailed, failureCode.takeIf { sessionId != null }, exception)
        } catch (error: LinkageError) {
            recordTransportStartFailure(DiagnosticCode.EngineFailed, error)
            finishSession(VpnEvent.ServiceFailed, DiagnosticCode.EngineFailed, error)
        }
    }

    private fun establishTunnel(builder: Builder): ParcelFileDescriptor? {
        // Android проверяет право при establish(). Повторный prepare здесь мог бы сменить VPN,
        // выбранный пользователем между запуском службы и созданием TUN.
        tunnel = builder.establish()
        if (tunnel == null) {
            transportDiagnostics?.reporter()?.record(TransportFailure(
                TUN_INIT, TransportCategory.CANCELLED, TransportCode.VPN_PERMISSION_REVOKED,
            ))
            finishSession(VpnEvent.ConsentRevoked)
        }
        return tunnel
    }

    private suspend fun awaitPhysicalNetwork(): Network? {
        // После переключения Android может ещё не вернуть физическую сеть по умолчанию.
        return connectivity.activeNetwork?.takeIf(::isPhysical) ?: withTimeoutOrNull(3_000) {
            var physical = connectivity.activeNetwork?.takeIf(::isPhysical)
            while (physical == null) {
                delay(100)
                physical = connectivity.activeNetwork?.takeIf(::isPhysical)
            }
            physical
        }
    }

    private fun recordTransportStartFailure(code: DiagnosticCode, error: Throwable) {
        val reporter = transportDiagnostics?.reporter() ?: return
        when (code) {
            DiagnosticCode.EngineFailed -> reporter.recordError(HEV_INIT, error, fallback = TransportCategory.HEV_INIT)
            DiagnosticCode.TransportIpv4Unavailable -> reporter.recordError(DNS_INIT, error, fallback = TransportCategory.UNREACHABLE)
            DiagnosticCode.TunnelFailed -> reporter.recordError(TUN_INIT, error, fallback = TransportCategory.TUN_IO)
            else -> reporter.recordError(LIFECYCLE_START, error, fallback = TransportCategory.INTERNAL)
        }
    }

    private fun isPhysical(network: Network): Boolean =
        connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true

    private fun watchPhysicalNetwork(id: String, initial: Network) {
        val vanished = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == initial) stopForNetwork(id, VpnEventType.TransportNetworkLost)
            }
        }
        val defaultChanged = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network != initial && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    stopForNetwork(id, VpnEventType.TransportNetworkChanged)
                }
            }
        }
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
            vanished, stopHandler,
        )
        networkCallbacks.add(vanished)
        connectivity.registerDefaultNetworkCallback(defaultChanged, stopHandler)
        networkCallbacks.add(defaultChanged)
        if (!isPhysical(initial)) stopForNetwork(id, VpnEventType.TransportNetworkLost)
    }

    private fun stopForNetwork(id: String, event: VpnEventType) {
        if (id != sessionId || shutdown.started) return
        val lost = event == VpnEventType.TransportNetworkLost
        transportDiagnostics?.reporter()?.record(TransportFailure(LIFECYCLE_STOP,
            if (lost) TransportCategory.UNREACHABLE else TransportCategory.CANCELLED,
            if (lost) TransportCode.NETWORK_LOST else TransportCode.NETWORK_CHANGED))
        recordTransportEvent(event)
        VpnController.serviceStopping(id)
        finishSession()
    }

    private fun inspectTransport() {
        val engine = transport ?: return
        val id = sessionId ?: return
        val stats = engine.snapshot()
        logConnectionChanges(stats)
        VpnController.updateTransport(stats)
        if (stats.running && !transportConfirmed) {
            transportConfirmed = true
            VpnController.engineConfirmed(id)
            engine.stateChanged(VpnController.state.value)
            recordTransportEvent(VpnEventType.TransportStarted)
        }
        if (stats.transferred && !trafficConfirmed) {
            trafficConfirmed = true
            recordTransportEvent(VpnEventType.TransportTraffic)
        }
        if (stats.errors > 0 && !transportErrorReported) {
            transportErrorReported = true
            recordTransportEvent(VpnEventType.TransportOperationFailed)
        }
        if (!stats.running && SystemClock.elapsedRealtime() >= transportReadyAt) {
            transportDiagnostics?.reporter()?.record(TransportFailure(HEV_INIT,
                TransportCategory.HEV_INIT, TransportCode.HEV_READY_TIMEOUT))
            failTransport(id)
            return
        }
        stopHandler.postDelayed(refreshTransport, 500)
    }

    private fun logConnectionChanges(stats: TransportSnapshot) {
        if (!stats.applicationMode) return
        val previous = VpnController.transport.value
        if (previous != null && previous.openedTcp == stats.openedTcp && previous.closedTcp == stats.closedTcp &&
            previous.openedUdp == stats.openedUdp && previous.closedUdp == stats.closedUdp) return
        Log.i("AdaptiveTun", "Соединения за сеанс, открыто/закрыто: TCP ${stats.openedTcp}/${stats.closedTcp}, UDP ${stats.openedUdp}/${stats.closedUdp}")
    }

    private fun failTransport(id: String) {
        if (id != sessionId || shutdown.started) return
        finishSession(VpnEvent.CriticalFailure, DiagnosticCode.EngineFailed)
    }

    private fun recordTransportEvent(type: VpnEventType) {
        VpnEventHistory.record(type, sessionId?.toSessionUuid(), state = VpnController.state.value)
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
            if (sessionId != null && !shutdown.started) {
                transportDiagnostics?.reporter()?.record(TransportFailure(LIFECYCLE_STOP,
                    TransportCategory.CANCELLED, TransportCode.VPN_PERMISSION_REVOKED))
                finishSession(VpnEvent.SystemConsentRevoked)
            }
        }
    }

    override fun onDestroy() {
        stopHandler.removeCallbacks(completeStop)
        val unexpected = sessionId != null && !shutdown.started &&
            VpnController.state.value.canStop && VpnController.state.value != VpnState.Stopping
        val snapshot = diagnosticSnapshot()
        val marker = if (unexpected) retainActiveMarker() else null
        try {
            releaseResources()
        } finally {
            val id = sessionId
            sessionId = null
            if (id != null) {
                val closing = closingTransport
                if (closing != null && !closing.finished.isCompleted) {
                    VpnController.serviceStopping(id)
                    closing.finished.invokeOnCompletion {
                        mainExecutor.execute { publishEnd(id, unexpected, snapshot, marker) }
                    }
                } else {
                    publishEnd(id, unexpected, snapshot, marker)
                }
            }
            super.onDestroy()
        }
    }

    private fun publishEnd(id: String, unexpected: Boolean, snapshot: VpnSnapshot, marker: UUID?) {
        val cleanupFailed = closingTransport?.cleanedUp == false
        val failedDuringStop = termination == VpnEvent.ServiceFailed && VpnController.state.value == VpnState.Stopping
        val event = if (unexpected || cleanupFailed || failedDuringStop) VpnEvent.CriticalFailure else termination
        val code = when {
            unexpected -> DiagnosticCode.VpnUnexpectedStop
            cleanupFailed -> DiagnosticCode.VpnCleanupFailed
            else -> terminationCode
        }
        transportDiagnostics?.let { diagnostics ->
            VpnController.transport.value?.takeIf { it.sessionId == diagnostics.id }?.let {
                VpnController.updateTransport(it.copy(errors = diagnostics.total))
            }
            diagnostics.finish()
        }
        VpnController.serviceEnded(id, event, code)
        if (unexpected) VpnDiagnostics.persistCritical(DiagnosticCode.VpnUnexpectedStop, null, snapshot, marker)
        if (cleanupFailed) VpnDiagnostics.recordAsync(DiagnosticCode.VpnCleanupFailed, null, snapshot)
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
        terminationCode = code
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
        sessionStart?.cancel()
        sessionStart = null
        stopHandler.removeCallbacks(refreshTransport)
        val runningTransport = transport
        transport = null
        if (runningTransport != null) {
            val transportId = sessionId?.toSessionUuid()
            closingTransport = runningTransport
            val stats = VpnController.transport.value
            runningTransport.stop()
            val stopped = runCatching(runningTransport::snapshot).getOrNull() ?: stats
            if (stopped != null) {
                val snapshot = stopped.copy(
                    running = false,
                    tunSentPackets = maxOf(stopped.tunSentPackets, stats?.tunSentPackets ?: 0),
                    tunReceivedPackets = maxOf(stopped.tunReceivedPackets, stats?.tunReceivedPackets ?: 0),
                )
                logConnectionChanges(snapshot)
                VpnController.updateTransport(snapshot)
            }
            runningTransport.finished.invokeOnCompletion {
                // Запись относится к прежнему сеансу даже при уничтожении Activity.
                VpnEventHistory.record(VpnEventType.TransportStopped, transportId)
                VpnEventHistory.record(
                    if (runningTransport.tunClosed) VpnEventType.TransportTunClosed else VpnEventType.StopError,
                    transportId,
                )
            }
        }
        networkCallbacks.forEach { callback -> runCatching { connectivity.unregisterNetworkCallback(callback) } }
        networkCallbacks.clear()
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

    private fun showForegroundNotification(id: String?, experiment: Boolean) {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(getString(if (experiment) R.string.vpn_notification_experiment_title else R.string.vpn_notification_title))
            .setContentText(getString(if (experiment) R.string.vpn_notification_experiment else R.string.vpn_notification_message))
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
        internal const val EXTRA_TARGET = "org.adaptivedpi.android.vpn.TARGET"
        internal const val EXTRA_APPLICATION = "org.adaptivedpi.android.vpn.APPLICATION"
        private const val ACTION_STOP = "org.adaptivedpi.android.vpn.STOP"
        private const val CHANNEL_ID = "vpn_session"
        private const val NOTIFICATION_ID = 1
        private const val JOURNAL_GRACE_MILLIS = 250L
        private val HEV_INIT = TransportSite(TransportSubsystem.HEV, TransportOperation.INIT)
        private val TUN_INIT = TransportSite(TransportSubsystem.TUN, TransportOperation.INIT)
        private val DNS_INIT = TransportSite(TransportSubsystem.DNS, TransportOperation.INIT)
        private val LIFECYCLE_START = TransportSite(TransportSubsystem.LIFECYCLE, TransportOperation.INIT)
        private val LIFECYCLE_STOP = TransportSite(TransportSubsystem.LIFECYCLE, TransportOperation.STOP)
    }
}
