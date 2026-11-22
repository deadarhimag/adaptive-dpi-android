package org.adaptivedpi.android.vpn

import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import org.adaptivedpi.android.engine.HevLoopbackRelay
import org.adaptivedpi.android.engine.OutboundTransport
import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode
import org.adaptivedpi.android.engine.TransportFailure
import org.adaptivedpi.android.engine.TransportOperation
import org.adaptivedpi.android.engine.TransportSite
import org.adaptivedpi.android.engine.TransportSocketException
import org.adaptivedpi.android.engine.TransportSubsystem
import org.adaptivedpi.android.vpn.diagnostics.TransportSessionDiagnostics
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    external fun release(token: Long)
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
    val finished = CompletableDeferred<Unit>()
    @Volatile var tunClosed = false
        private set

    init {
        if (!HevBridge.reserve(token, VpnController.state.value.ordinal)) {
            throw TransportSocketException(TransportCode.HEV_RESERVE, TransportCategory.HEV_INIT)
        }
        try {
            outbound.updateNetwork(VpnOutboundSocketPolicy(service, network))
            relay = HevLoopbackRelay(outbound, target, sessionLimit, failures, onFailure)
        } catch (error: Exception) {
            outbound.close()
            HevBridge.release(token)
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
                    failures.recordError(NATIVE_RUN, error, stopping.get(), TransportCategory.INTERNAL)
                } catch (error: LinkageError) {
                    failures.recordError(NATIVE_RUN, error, fallback = TransportCategory.INTERNAL)
                } finally {
                    val released = finish()
                    onExit(failure || !released)
                }
            }, "HEV TUN").start()
        } catch (failure: RuntimeException) {
            finish()
            throw failure
        }
    }

    fun stop() {
        if (stopping.compareAndSet(false, true)) {
            HevBridge.stop(token, VpnController.state.value.ordinal)
            relay.close()
        }
    }

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

    fun stateChanged(state: VpnState) = HevBridge.setFaultState(token, state.ordinal)

    private fun drainFaults(final: Boolean = false) {
        if (final) drainLock.lock() else if (!drainLock.tryLock()) return
        try {
            HevBridge.takeFaults(token)?.let(observer::accept)
        } finally {
            drainLock.unlock()
        }
    }

    private fun finish(): Boolean {
        try {
            runCatching { drainFaults(final = true) }
            tunClosed = runCatching(tunnel::close).onFailure {
                failures.recordError(TUN_CLOSE, it, fallback = TransportCategory.CLEANUP)
            }.isSuccess
            relay.close()
            val relayStopped = runCatching(relay::awaitShutdown).getOrElse { false }
            if (!relayStopped) failures.record(TransportFailure(RELAY_STOP, TransportCategory.CLEANUP, TransportCode.RELAY_STOP))
            return tunClosed && relayStopped
        } finally {
            HevBridge.release(token)
            finished.complete(Unit)
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

    private companion object {
        val nextToken = AtomicLong()
        val NATIVE_RUN = TransportSite(TransportSubsystem.HEV, TransportOperation.RUN)
        val TUN_CLOSE = TransportSite(TransportSubsystem.TUN, TransportOperation.CLOSE)
        val RELAY_STOP = TransportSite(TransportSubsystem.HEV, TransportOperation.STOP)
    }
}
