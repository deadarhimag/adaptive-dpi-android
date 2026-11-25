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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode
import org.adaptivedpi.android.engine.TransportFailure
import org.adaptivedpi.android.engine.TransportOperation
import org.adaptivedpi.android.engine.TransportSite
import org.adaptivedpi.android.engine.TransportSocketException
import org.adaptivedpi.android.engine.TransportSubsystem
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.CleanupIssue
import org.adaptivedpi.android.vpn.diagnostics.CleanupStep
import org.adaptivedpi.android.vpn.diagnostics.ExceptionKind
import org.adaptivedpi.android.vpn.diagnostics.KnownFlag
import org.adaptivedpi.android.vpn.diagnostics.TransportDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.TransportSessionDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnEventHistory
import org.adaptivedpi.android.vpn.diagnostics.VpnEventType
import org.adaptivedpi.android.vpn.diagnostics.VpnSnapshot
import org.adaptivedpi.android.vpn.diagnostics.VpnCleanupDetails
import java.io.IOException
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AdaptiveVpnService : VpnService() {
    private var sessionId: String? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var foregroundRequested = false
    private var termination = VpnEvent.ServiceDestroyed
    private var terminationCode: DiagnosticCode? = null
    private var stopStartedAt = 0L
    @Volatile
    private var destroyed = false
    private val serviceDestroyed = CompletableDeferred<Unit>()
    private var vpnFlags = VpnSnapshot(VpnState.Idle)
    private var revokedDuringStop = false
    private val cleanupIssues = ConcurrentHashMap<CleanupStep, ExceptionKind>()
    private val slowSteps = ConcurrentHashMap<CleanupStep, Long>()
    @Volatile private var cleanupStep = CleanupStep.StartupDrain to 0L
    private data class Released(val details: VpnCleanupDetails, val snapshot: TransportSnapshot?)
    private var released: Released? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val shutdown = VpnShutdown(
        serviceScope, STOP_DELAY_MILLIS, SystemClock::elapsedRealtime,
        ::releaseResources, ::reportCleanupDelay, ::onCleanupComplete,
    )
    private val stopHandler = Handler(Looper.getMainLooper())
    private var sessionStart: Job? = null
    private var transportPoll: Job? = null
    private var transportStarted = false
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
    private val refreshTransport = Runnable {
        if (!shutdown.started) transportPoll = serviceScope.launch { inspectTransport() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (shutdown.started) {
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_SESSION_ID)
        when (intent?.action) {
            ACTION_START -> if (sessionId == null) {
                val request = serviceScope.launch(start = CoroutineStart.LAZY) {
                    startSession(id, intent.getStringExtra(EXTRA_TARGET), intent.getStringExtra(EXTRA_APPLICATION))
                }
                sessionStart = request
                request.start()
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
        serviceScope.launch {
            withContext(SESSION_IO) { runCatching { stopSelfResult(startId) } }.onFailure(::recordServiceStopFailure)
        }
    }

    private fun recordCleanupFailure(step: CleanupStep, error: Throwable) {
        cleanupIssues[step] = ExceptionKind.from(error)
        transportDiagnostics?.reporter()?.recordError(LIFECYCLE_STOP, error, fallback = TransportCategory.CLEANUP)
    }

    private fun recordServiceStopFailure(error: Throwable) {
        if (cleanupIssues.containsKey(CleanupStep.ServiceStop)) return
        recordCleanupFailure(CleanupStep.ServiceStop, error)
        reportCleanup(DiagnosticCode.VpnCleanupFailed, sessionId, diagnosticSnapshot(),
            released?.details?.copy(issues = recordedIssues()) ?: pendingCleanup(), error)
    }

    private fun reportCleanup(
        code: DiagnosticCode, id: String?, snapshot: VpnSnapshot, details: VpnCleanupDetails,
        error: Throwable? = null, recordHistory: Boolean = true,
    ) {
        if (recordHistory) VpnEventHistory.record(VpnEventType.StopError, id?.toSessionUuid(), code, snapshot.state)
        Log.w("AdaptiveTun", "${code.name}: ${details.text()}")
        VpnDiagnostics.recordCleanupAsync(code, snapshot, details, error)
    }

    private fun recordedIssues() = cleanupIssues.map { (step, exception) -> CleanupIssue(step, exception) }

    private fun cleanupStage(step: CleanupStep) {
        val now = SystemClock.elapsedRealtime()
        val (previous, since) = cleanupStep
        val elapsed = now - since
        if (since != 0L && elapsed >= SLOW_STEP_MILLIS) slowSteps.merge(previous, elapsed, ::maxOf)
        cleanupStep = step to now
    }

    private fun pendingCleanup(): VpnCleanupDetails {
        val (step, since) = cleanupStep.let {
            if (it.first == CleanupStep.NativeExit) closingTransport?.finalizationStep ?: it else it
        }
        val now = SystemClock.elapsedRealtime()
        val snapshot = released?.details ?: VpnCleanupDetails(
            sessionId?.toSessionUuid(), 0, closingTransport?.tunClosed?.takeIf { it }, null,
            null, null, null, null, recordedIssues(),
        )
        return snapshot.copy(elapsedMillis = (now - stopStartedAt).coerceAtLeast(0), waitingFor = step,
            stepMillis = (now - maxOf(since, stopStartedAt)).coerceAtLeast(0), slowSteps = slowSteps.toMap())
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
            if (attachedId != null && VpnController.state.value == VpnState.Stopping) finishSession()
            withContext(SESSION_IO) { showForegroundNotification(attachedId, experiment) }
            if (shutdown.started) return
            if (attachedId == null || VpnController.state.value == VpnState.Stopping) {
                if (attachedId == sessionId) finishSession()
                return
            }
            vpnFlags = withContext(SESSION_IO) {
                VpnSnapshot(VpnState.Idle, systemFlag { isAlwaysOn }, systemFlag { isLockdownEnabled })
            }
            if (shutdown.started) return

            failureCode = DiagnosticCode.VpnCleanupFailed
            withContext(SESSION_IO) { ExperimentalTransport.requirePreviousTransportReleased() }
            if (shutdown.started) return
            failureCode = DiagnosticCode.TunnelFailed
            val builder = Builder()
                .setSession(getString(R.string.vpn_service_name))
                .addAddress("192.0.2.1", 32)
            if (applicationPackage != null) {
                failureCode = DiagnosticCode.TransportApplicationUnavailable
                require(experimentalTarget == null && applicationPackage != packageName && ExperimentalApplication.isPackageName(applicationPackage))
                withContext(SESSION_IO) {
                    // Android 14 может принять отсутствующий пакет при заполнении списка VPN.
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(applicationPackage, 0)
                    builder.addAllowedApplication(applicationPackage)
                }
                failureCode = DiagnosticCode.TunnelFailed
            } else {
                withContext(SESSION_IO) { builder.addAllowedApplication(packageName) }
            }
            if (shutdown.started) return
            if (experimentalTarget == null && applicationPackage == null) {
                builder.addAddress("2001:db8::1", 128)
            } else {
                val target = experimentalTarget?.let { ExperimentalTarget.parse(it) ?: throw IOException("Недопустимый тестовый адрес.") }
                val available = withContext(SESSION_IO) { awaitPhysicalNetwork() }
                if (shutdown.started) return
                val physical = available
                    ?: throw TransportSocketException(TransportCode.NO_PHYSICAL_NETWORK, TransportCategory.UNREACHABLE)
                builder.setMtu(1280).setUnderlyingNetworks(arrayOf(physical))
                if (target != null) {
                    builder.addRoute(target, 32)
                } else {
                    failureCode = DiagnosticCode.TransportIpv4Unavailable
                    val link = withContext(SESSION_IO) { connectivity.getLinkProperties(physical) }
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
                val started = withContext(SESSION_IO) {
                    ExperimentalTransport(this@AdaptiveVpnService, physical, target, descriptor, checkNotNull(transportDiagnostics)) {
                        mainExecutor.execute { failTransport(attachedId) }
                    }
                }
                transport = started
                tunnel = null // Дескриптор закрывает рабочий поток после выхода HEV.
                if (shutdown.started) return
                transportStarted = true
                transportReadyAt = SystemClock.elapsedRealtime() + 5_000
                withContext(SESSION_IO) {
                    started.start { failed ->
                        if (failed) mainExecutor.execute { failTransport(attachedId) }
                    }
                    watchPhysicalNetwork(attachedId, physical)
                }
                if (!shutdown.started) stopHandler.post(refreshTransport)
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

    private suspend fun establishTunnel(builder: Builder): ParcelFileDescriptor? {
        // Android проверяет право при establish(). Повторный prepare здесь мог бы сменить VPN,
        // выбранный пользователем между запуском службы и созданием TUN.
        if (shutdown.started) return null
        tunnel = withContext(SESSION_IO) { builder.establish() }
        if (shutdown.started) return null // Владелец очистки дождётся выхода задачи запуска.
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
            while (physical == null && !shutdown.started) {
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
        if (!isPhysical(initial)) mainExecutor.execute { stopForNetwork(id, VpnEventType.TransportNetworkLost) }
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

    private suspend fun inspectTransport() {
        val engine = transport ?: return
        val id = sessionId ?: return
        val trackReady = !transportConfirmed
        val stats = try {
            observeTransportPoll(warnAfterMillis = 5_000, onDelay = {
                if (sessionId == id) {
                    recordTransportEvent(VpnEventType.TransportPollDelayed)
                    Log.w("AdaptiveTun", "Опрос HEV не вернулся за 5 с. Владение ресурсами сохраняется.")
                }
            }) {
                withContext(TRANSPORT_POLL_IO) {
                    engine.snapshot().also {
                        if (it.running && trackReady && !shutdown.started) engine.stateChanged(VpnState.EngineRunning)
                    }
                }
            }
        } catch (error: Exception) {
            if (!shutdown.started) finishSession(VpnEvent.CriticalFailure, DiagnosticCode.EngineFailed, error)
            return
        } catch (error: LinkageError) {
            if (!shutdown.started) finishSession(VpnEvent.CriticalFailure, DiagnosticCode.EngineFailed, error)
            return
        }
        if (shutdown.started || sessionId != id) return
        logConnectionChanges(stats)
        VpnController.updateTransport(stats)
        if (stats.running && !transportConfirmed) {
            transportConfirmed = true
            VpnController.engineConfirmed(id)
            recordTransportEvent(VpnEventType.TransportStarted)
        }
        if (stats.transferred && !trafficConfirmed) {
            trafficConfirmed = true
            recordTransportEvent(VpnEventType.TransportTraffic)
        }
        // Общий счётчик отказов включает штатные тайм-ауты и отмены; предупреждение - только по первому неожиданному или неизвестному.
        if (!transportErrorReported && (transportDiagnostics?.problems ?: 0L) > 0L) {
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

    override fun onRevoke() {
        // Android может вызвать отзыв разрешения вне главного потока.
        mainExecutor.execute {
            val id = sessionId ?: return@execute
            if (shutdown.started) {
                if (!revokedDuringStop && termination != VpnEvent.SystemConsentRevoked) {
                    revokedDuringStop = true
                    VpnEventHistory.record(VpnEventType.SystemRevoked, id.toSessionUuid(), state = VpnController.state.value)
                }
            } else {
                transportDiagnostics?.reporter()?.record(TransportFailure(LIFECYCLE_STOP,
                    TransportCategory.CANCELLED, TransportCode.VPN_PERMISSION_REVOKED))
                finishSession(VpnEvent.SystemConsentRevoked)
                VpnRevokedNotice.show(this)
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        try {
            if (!shutdown.started) {
                if (sessionId != null && VpnController.state.value != VpnState.Stopping) {
                    finishSession(VpnEvent.CriticalFailure, DiagnosticCode.VpnUnexpectedStop)
                } else {
                    finishSession()
                }
            }
        } finally {
            super.onDestroy()
            serviceDestroyed.complete(Unit)
        }
    }

    private fun reportCleanupDelay() {
        val id = sessionId ?: return
        val details = released?.details?.takeIf { serviceDestroyed.isCompleted }
            ?.copy(elapsedMillis = SystemClock.elapsedRealtime() - stopStartedAt) ?: pendingCleanup()
        details.waitingFor?.let { VpnController.cleanupDelayed(id, it.title) }
        reportCleanup(DiagnosticCode.VpnCleanupDelayed, id, diagnosticSnapshot(), details, recordHistory = false)
        val relayTransport = closingTransport
        val step = details.waitingFor
        if (relayTransport != null && step != null) {
            val startedAt = stopStartedAt
            serviceScope.launch(Dispatchers.Default) {
                logRelayShutdown(relayTransport, id, startedAt, "cleanup-delayed", step, includeStacks = step == CleanupStep.RelayWait)
            }
        }
    }

    private fun logRelayShutdown(
        relayTransport: ExperimentalTransport,
        id: String,
        startedAt: Long,
        checkpoint: String,
        step: CleanupStep,
        includeStacks: Boolean,
    ) {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
        val prefix = "sessionId=$id elapsedMs=$elapsed step=$step checkpoint=$checkpoint"
        try {
            relayTransport.relayShutdownDiagnostics(includeStacks).forEach { Log.i(RELAY_WAIT_TAG, "$prefix $it") }
        } catch (error: Exception) {
            Log.w(RELAY_WAIT_TAG, "$prefix diagnosticError=${error.javaClass.simpleName}")
        }
    }

    private fun observeRelayWait(relayTransport: ExperimentalTransport, id: String, startedAt: Long): Job =
        serviceScope.launch(Dispatchers.Default) {
            for ((checkpoint, atMillis) in RELAY_WAIT_CHECKPOINTS) {
                delay((startedAt + atMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (cleanupStep.first != CleanupStep.RelayWait) return@launch
                logRelayShutdown(relayTransport, id, startedAt, checkpoint, CleanupStep.RelayWait, includeStacks = true)
            }
        }

    private fun onCleanupComplete(result: Result<Released>) {
        val completed = result.getOrElse {
            recordCleanupFailure(cleanupStep.first, it)
            Released(pendingCleanup(), null)
        }
        released = null
        val id = sessionId
        sessionId = null
        if (id == null) {
            serviceScope.cancel()
            return
        }
        val snapshot = diagnosticSnapshot()
        val cleanup = completed.details.copy(
            issues = (completed.details.issues + recordedIssues()).distinct(),
            slowSteps = slowSteps.toMap(),
        )
        val cleanupFailed = cleanup.incomplete
        val failedDuringStop = termination == VpnEvent.ServiceFailed && VpnController.state.value == VpnState.Stopping
        val event = when {
            cleanupFailed -> VpnEvent.CleanupFailed
            failedDuringStop -> VpnEvent.CriticalFailure
            else -> termination
        }
        val code = if (cleanupFailed) DiagnosticCode.VpnCleanupFailed else terminationCode
        transportDiagnostics?.let { diagnostics ->
            val last = VpnController.transport.value?.takeIf { it.sessionId == diagnostics.id }
            val final = (completed.snapshot ?: last?.copy(errors = diagnostics.total, running = false))
                ?.let { it.copy(finalized = it.finalized && !cleanupFailed) }
            final?.let {
                logConnectionChanges(it)
                VpnController.updateTransport(it)
            }
            if (!cleanupFailed) diagnostics.finish()
        }
        if (closingTransport != null) {
            val session = id.toSessionUuid()
            if (cleanup.relay?.terminated == true && cleanup.nativeReleased == true && cleanup.sockets?.confirmed == true) {
                VpnEventHistory.record(VpnEventType.TransportStopped, session, state = snapshot.state)
            }
            if (cleanup.tunClosed == true) VpnEventHistory.record(VpnEventType.TransportTunClosed, session, state = snapshot.state)
        }
        if (!cleanupFailed) activeMarker?.let { VpnDiagnostics.endActive(it) }
        activeMarker = null
        VpnController.serviceEnded(id, event, code, released = !cleanupFailed)
        if (cleanupFailed || cleanup.issues.isNotEmpty()) {
            reportCleanup(DiagnosticCode.VpnCleanupFailed, id, snapshot.copy(state = VpnController.state.value), cleanup,
                error = result.exceptionOrNull(), recordHistory = !cleanupFailed)
        }
        serviceScope.cancel()
    }

    private fun finishSession(
        event: VpnEvent = VpnEvent.ServiceDestroyed,
        code: DiagnosticCode? = null,
        error: Throwable? = null,
    ) {
        if (shutdown.started) return
        termination = event
        terminationCode = code
        stopStartedAt = SystemClock.elapsedRealtime()
        transport?.stopAcceptingNewConnections()
        cleanupStage(CleanupStep.StartupDrain)
        if (code != null) {
            val snapshot = diagnosticSnapshot()
            val marker = retainActiveMarker()
            VpnDiagnostics.persistCritical(code, error, snapshot, marker)
        }
        if (event != VpnEvent.ServiceFailed) sessionId?.let(VpnController::serviceStopping)
        stopHandler.removeCallbacks(refreshTransport)
        val previous = VpnController.transport.value
        if (previous != null && transportDiagnostics?.id == previous.sessionId) {
            VpnController.updateTransport(previous.copy(running = false))
        }
        shutdown.start()
    }

    private fun retainActiveMarker(): UUID? = activeMarker?.also {
        activeMarker = null
        VpnDiagnostics.endActive(it, keepMarker = true)
    }

    private suspend fun releaseResources(): Released {
        sessionStart?.join()
        sessionStart = null
        val runningTransport = transport
        transport = null
        closingTransport = runningTransport
        val callbacks = networkCallbacks.toList()
        networkCallbacks.clear()
        val descriptor = tunnel
        tunnel = null
        val last = VpnController.transport.value?.takeIf { it.sessionId == transportDiagnostics?.id }
        val stopSession = sessionId
        val startedAt = stopStartedAt
        val id = stopSession?.toSessionUuid()
        val started = transportStarted
        val removeForeground = foregroundRequested
        foregroundRequested = false
        var tunClosed: Boolean? = null
        requestStopAndAwaitPoll(transportPoll) {
            withContext(SESSION_IO) {
                if (runningTransport != null) {
                    if (started) runningTransport.stop(::cleanupStage) else {
                        cleanupStage(CleanupStep.NativeExit)
                        runningTransport.abortBeforeStart()
                    }
                    if (stopSession != null) {
                        logRelayShutdown(runningTransport, stopSession, startedAt, "after-close", cleanupStep.first, includeStacks = false)
                    }
                }
            }
            if (transportPoll?.isCompleted == false) cleanupStage(CleanupStep.TransportSnapshot)
        }
        transportPoll = null
        withContext(SESSION_IO) {
            val steps = listOf(
                CleanupStep.TunClose to {
                    descriptor?.let {
                        val fd = it.fileDescriptor
                        try { if (fd.valid()) it.close() } finally { tunClosed = !fd.valid() }
                    }
                    Unit
                },
            )
            releaseVpnResources(::recordCleanupFailure, *steps.map { (step, operation) ->
                step to { cleanupStage(step); operation() }
            }.toTypedArray())
            cleanupStage(CleanupStep.NetworkCallback)
            callbacks.forEach { callback ->
                runCatching { connectivity.unregisterNetworkCallback(callback) }
                    .onFailure { recordCleanupFailure(CleanupStep.NetworkCallback, it) }
            }
        }
        if (runningTransport != null) {
            cleanupStage(CleanupStep.NativeExit)
            runningTransport.finished.await()
            withContext(SESSION_IO) {
                runningTransport.awaitWorkerExit()
                cleanupStage(CleanupStep.RelayWait)
                val observer = stopSession?.let { observeRelayWait(runningTransport, it, startedAt) }
                try {
                    runningTransport.awaitRelayRelease()
                } finally {
                    observer?.cancel()
                }
            }
        }
        val resources = withContext(SESSION_IO) {
            cleanupStage(CleanupStep.ForegroundNotification)
            if (removeForeground) runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                .onFailure { recordCleanupFailure(CleanupStep.ForegroundNotification, it) }
            cleanupStage(CleanupStep.ServiceStop)
            if (!destroyed) runCatching { stopSelf() }.onFailure { recordCleanupFailure(CleanupStep.ServiceStop, it) }
            cleanupStage(CleanupStep.TransportSnapshot)
            val elapsed = SystemClock.elapsedRealtime() - stopStartedAt
            Released(
                runningTransport?.cleanupDetails(elapsed, recordedIssues())
                    ?: VpnCleanupDetails(id, elapsed, tunClosed, null, null, null, null, null, recordedIssues()),
                runningTransport?.stoppedSnapshot(last),
            )
        }
        released = resources
        cleanupStage(CleanupStep.ServiceStop)
        if (!cleanupIssues.containsKey(CleanupStep.ServiceStop)) serviceDestroyed.await()
        return resources.copy(details = resources.details.copy(elapsedMillis = SystemClock.elapsedRealtime() - stopStartedAt))
    }

    private fun diagnosticSnapshot(state: VpnState = VpnController.state.value) = vpnFlags.copy(state = state)

    private fun systemFlag(read: () -> Boolean) = runCatching {
        if (read()) KnownFlag.Yes else KnownFlag.No
    }.getOrDefault(KnownFlag.Unknown)

    private fun showForegroundNotification(id: String?, experiment: Boolean) {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        VpnRevokedNotice.cancel(this)
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
        private const val STOP_DELAY_MILLIS = 4_000L
        private const val SLOW_STEP_MILLIS = 1_000L
        private const val RELAY_WAIT_TAG = "RelayWaitDiag"
        // Последний снимок должен появиться до 20-секундного watchdog инструментального теста.
        private val RELAY_WAIT_CHECKPOINTS = listOf("still-waiting" to 10_000L, "before-test-watchdog" to 19_000L)
        private val SESSION_IO = Dispatchers.IO.limitedParallelism(1)
        private val TRANSPORT_POLL_IO = Dispatchers.IO.limitedParallelism(1)
        private val HEV_INIT = TransportSite(TransportSubsystem.HEV, TransportOperation.INIT)
        private val TUN_INIT = TransportSite(TransportSubsystem.TUN, TransportOperation.INIT)
        private val DNS_INIT = TransportSite(TransportSubsystem.DNS, TransportOperation.INIT)
        private val LIFECYCLE_START = TransportSite(TransportSubsystem.LIFECYCLE, TransportOperation.INIT)
        private val LIFECYCLE_STOP = TransportSite(TransportSubsystem.LIFECYCLE, TransportOperation.STOP)
    }
}
