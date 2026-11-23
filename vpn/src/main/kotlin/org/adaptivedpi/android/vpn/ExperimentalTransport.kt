package org.adaptivedpi.android.vpn

import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import org.adaptivedpi.android.engine.HevLoopbackRelay
import org.adaptivedpi.android.engine.OutboundTransport
import org.adaptivedpi.android.engine.OutboundStatistics
import org.adaptivedpi.android.engine.TransportCancellation
import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode
import org.adaptivedpi.android.engine.TransportFailure
import org.adaptivedpi.android.engine.TransportOperation
import org.adaptivedpi.android.engine.TransportSite
import org.adaptivedpi.android.engine.TransportSocketException
import org.adaptivedpi.android.engine.TransportSubsystem
import org.adaptivedpi.android.vpn.diagnostics.TransportSessionDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.CleanupIssue
import org.adaptivedpi.android.vpn.diagnostics.CleanupStep
import org.adaptivedpi.android.vpn.diagnostics.ExceptionKind
import org.adaptivedpi.android.vpn.diagnostics.VpnCleanupDetails
import java.net.Inet4Address
import java.net.InetAddress
import java.io.FileDescriptor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

object ExperimentalTarget {
    fun parse(value: String): Inet4Address? {
        if (!value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return null
        val octets = value.split('.').map(String::toInt)
        if (octets.any { it !in 0..255 }) return null
        val address = InetAddress.getByAddress(octets.map(Int::toByte).toByteArray()) as Inet4Address
        return address.takeIf { it.isSiteLocalAddress }
    }
}

object ExperimentalApplication {
    fun isPackageName(value: String): Boolean = value.length <= 223 &&
        value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
}

data class TransportSnapshot(
    val running: Boolean,
    val tunSentPackets: Long,
    val tunReceivedPackets: Long,
    val sentBytes: Long,
    val receivedBytes: Long,
    val connections: Int,
    val errors: Long,
    val applicationMode: Boolean = false,
    val openedTcp: Long = 0,
    val closedTcp: Long = 0,
    val openedUdp: Long = 0,
    val closedUdp: Long = 0,
    val sessionId: UUID? = null,
    val finalized: Boolean = false,
) {
    val transferred get() = tunSentPackets > 0 && tunReceivedPackets > 0 && sentBytes > 0 && receivedBytes > 0
}

internal object HevBridge {
    init { System.loadLibrary("adaptive-hev") }
    external fun reserve(token: Long, state: Int): Boolean
    external fun run(token: Long, config: ByteArray, fd: Int): Int
    external fun stop(token: Long, state: Int)
    external fun setFaultState(token: Long, state: Int)
    external fun takeFaults(token: Long): LongArray?
    external fun release(token: Long): Boolean
    external fun isReady(token: Long): Boolean
    external fun counters(token: Long): LongArray
}

internal class ExperimentalTransport(
    service: VpnService,
    network: Network,
    private val target: Inet4Address?,
    private val tunnel: ParcelFileDescriptor,
    private val diagnostics: TransportSessionDiagnostics,
    onFailure: () -> Unit,
) {
    private val token = nextToken.incrementAndGet()
    private val stopping = AtomicBoolean()
    private val sessionLimit = if (target == null) 32 else 16
    private val failures = diagnostics.reporter()
    private val observer = NativeTransportObserver(diagnostics)
    private val drainLock = ReentrantLock()
    private val outbound = OutboundTransport(maxSockets = sessionLimit, readTimeoutMillis = 60_000, failures = failures)
    private val relay: HevLoopbackRelay
    private var worker: Thread? = null
    val finished = CompletableDeferred<Unit>()
    @Volatile var tunClosed = false
        private set
    @Volatile private var nativeReleased = false
    @Volatile private var finalOutbound: OutboundStatistics? = null
    @Volatile private var finalCounters: LongArray? = null
    @Volatile var finalizationStep: Pair<CleanupStep, Long>? = null
        private set
    private val cleanupIssues = ConcurrentHashMap<CleanupStep, ExceptionKind>()

    init {
        if (!HevBridge.reserve(token, VpnController.state.value.ordinal)) {
            throw TransportSocketException(TransportCode.HEV_RESERVE, TransportCategory.HEV_INIT)
        }
        try {
            requirePreviousTransportReleased()
            outbound.updateNetwork(VpnOutboundSocketPolicy(service, network))
            relay = HevLoopbackRelay(outbound, target, sessionLimit, failures, onFailure)
        } catch (error: Throwable) {
            runCatching(outbound::close).exceptionOrNull()?.let(error::addSuppressed)
            runCatching { check(HevBridge.release(token)) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    fun start(onExit: (Boolean) -> Unit) {
        try {
            Thread({
                var failure = true
                try {
                    val config = config().toByteArray(Charsets.UTF_8)
                    try {
                        val result = HevBridge.run(token, config, tunnel.fd)
                        failure = result < 0 || !stopping.get()
                        if (result >= 0 && failure) failures.record(TransportFailure(
                            NATIVE_RUN, TransportCategory.INTERNAL, TransportCode.UNEXPECTED_EXIT, result = result,
                        ))
                    } finally {
                        config.fill(0)
                    }
                } catch (error: Exception) {
                    failures.recordError(NATIVE_RUN, error, if (stopping.get()) TransportCancellation.SESSION else null, TransportCategory.INTERNAL)
                } catch (error: LinkageError) {
                    failures.recordError(NATIVE_RUN, error, fallback = TransportCategory.INTERNAL)
                } finally {
                    finish()
                    onExit(failure)
                }
            }, "HEV TUN").also { worker = it }.start()
        } catch (failure: RuntimeException) {
            finish()
            throw failure
        }
    }

    fun stopAcceptingNewConnections() = outbound.stopAcceptingNewConnections()

    fun stop(step: (CleanupStep) -> Unit) {
        stopAcceptingNewConnections()
        if (stopping.compareAndSet(false, true)) {
            diagnostics.stateChanged(VpnState.Stopping)
            step(CleanupStep.NativeStop)
            cleanupStep(CleanupStep.NativeStop, RELAY_STOP) { HevBridge.stop(token, VpnState.Stopping.ordinal) }
            step(CleanupStep.RelayClose)
            cleanupStep(CleanupStep.RelayClose, RELAY_STOP, relay::close)
        }
    }

    fun abortBeforeStart() {
        stopAcceptingNewConnections()
        stopping.set(true)
        finish()
    }

    fun awaitWorkerExit() = worker?.join()

    fun awaitRelayRelease() = relay.awaitDrained()

    fun snapshot(): TransportSnapshot {
        drainFaults()
        val counters = HevBridge.counters(token)
        val stats = outbound.statistics()
        return TransportSnapshot(
            HevBridge.isReady(token), counters[0], counters[1], stats.sentBytes, stats.receivedBytes,
            stats.activeTcp + stats.activeUdp, diagnostics.total,
            target == null, stats.openedTcp, stats.closedTcp, stats.openedUdp, stats.closedUdp, diagnostics.id,
        )
    }

    fun stateChanged(state: VpnState) {
        diagnostics.stateChanged(state)
        HevBridge.setFaultState(token, state.ordinal)
    }

    fun cleanupDetails(elapsedMillis: Long, serviceIssues: List<CleanupIssue>): VpnCleanupDetails {
        val relayNow = relay.shutdownStatus()
        val stats = if (relayNow.terminated) outbound.statistics().also { finalOutbound = it } else finalOutbound
        return VpnCleanupDetails(
            diagnostics.id, elapsedMillis, tunClosed, nativeReleased, null, relayNow,
            stats?.let { it.activeTcp + it.activeUdp }, stats?.pending,
            serviceIssues + cleanupIssues.map { (step, error) -> CleanupIssue(step, error) },
            sockets = relay.socketClosures(),
        )
    }

    fun stoppedSnapshot(last: TransportSnapshot?): TransportSnapshot? = finalOutbound?.let { stats ->
        val counters = finalCounters
        TransportSnapshot(
            false, maxOf(counters?.get(0) ?: 0, last?.tunSentPackets ?: 0), maxOf(counters?.get(1) ?: 0, last?.tunReceivedPackets ?: 0),
            stats.sentBytes, stats.receivedBytes, stats.activeTcp + stats.activeUdp, diagnostics.total,
            target == null, stats.openedTcp, stats.closedTcp, stats.openedUdp, stats.closedUdp, diagnostics.id,
            finalized = counters != null,
        )
    }

    private fun drainFaults(final: Boolean = false) {
        if (final) drainLock.lock() else if (!drainLock.tryLock()) return
        try {
            HevBridge.takeFaults(token)?.let(observer::accept)
        } finally {
            drainLock.unlock()
        }
    }

    private fun finish() {
        try {
            finalizationStep = CleanupStep.TransportSnapshot to SystemClock.elapsedRealtime()
            diagnostics.stateChanged(VpnState.Stopping)
            runCatching {
                drainFaults(final = true)
                finalCounters = HevBridge.counters(token)
            }.onFailure {
                cleanupIssues[CleanupStep.TransportSnapshot] = ExceptionKind.from(it)
                failures.recordError(NATIVE_RUN, it, fallback = TransportCategory.INTERNAL)
            }
            finalizationStep = CleanupStep.RelayClose to SystemClock.elapsedRealtime()
            cleanupStep(CleanupStep.RelayClose, RELAY_STOP, relay::close)
            finalizationStep = CleanupStep.TunClose to SystemClock.elapsedRealtime()
            val descriptor = tunnel.fileDescriptor
            cleanupStep(CleanupStep.TunClose, TUN_CLOSE) {
                if (descriptor.valid()) tunnel.close()
            }
            tunClosed = !descriptor.valid()
            if (!tunClosed) drainingTun.set(descriptor)
        } finally {
            if (!relay.isTerminated) drainingRelay.set(relay)
            try {
                finalizationStep = CleanupStep.NativeRelease to SystemClock.elapsedRealtime()
                cleanupStep(CleanupStep.NativeRelease, RELAY_STOP) {
                    check(HevBridge.release(token))
                    nativeReleased = true
                }
            } finally {
                finalizationStep = null
                finished.complete(Unit)
            }
        }
    }

    private inline fun cleanupStep(step: CleanupStep, site: TransportSite, operation: () -> Unit) {
        runCatching(operation).onFailure {
            cleanupIssues[step] = ExceptionKind.from(it)
            failures.recordError(site, it, fallback = TransportCategory.CLEANUP)
        }
    }

    private fun config() = """
        tunnel:
          mtu: 1280
          ipv4: '192.0.2.1'
        socks5:
          address: '127.0.0.1'
          port: ${relay.port}
          udp: 'tcp'
          username: 'adaptive'
          password: '${relay.configSecret}'
        misc:
          log-file: '/dev/null'
          log-level: 'error'
          max-session-count: $sessionLimit
          tcp-buffer-size: 32768
          udp-recv-buffer-size: 32768
          udp-copy-buffer-nums: 4
          connect-timeout: 5000
          tcp-read-write-timeout: 60000
          udp-read-write-timeout: 60000
    """.trimIndent()

    companion object {
        private val nextToken = AtomicLong()
        private val drainingRelay = AtomicReference<HevLoopbackRelay?>()
        private val drainingTun = AtomicReference<FileDescriptor?>()
        private val NATIVE_RUN = TransportSite(TransportSubsystem.HEV, TransportOperation.RUN)
        private val TUN_CLOSE = TransportSite(TransportSubsystem.TUN, TransportOperation.CLOSE)
        private val RELAY_STOP = TransportSite(TransportSubsystem.HEV, TransportOperation.STOP)

        fun requirePreviousTransportReleased() {
            drainingTun.get()?.let { descriptor ->
                if (descriptor.valid()) throw TransportSocketException(TransportCode.TUN_DRAINING, TransportCategory.CLEANUP)
                drainingTun.compareAndSet(descriptor, null)
            }
            val previous = drainingRelay.get() ?: return
            if (!previous.isTerminated) throw TransportSocketException(TransportCode.RELAY_DRAINING, TransportCategory.CLEANUP)
            drainingRelay.compareAndSet(previous, null)
        }
    }
}
