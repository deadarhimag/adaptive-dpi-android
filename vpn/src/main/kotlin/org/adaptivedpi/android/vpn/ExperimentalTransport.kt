package org.adaptivedpi.android.vpn

import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import org.adaptivedpi.android.engine.HevLoopbackRelay
import org.adaptivedpi.android.engine.OutboundTransport
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ExperimentalTarget {
    fun parse(value: String): Inet4Address? {
        if (!value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return null
        val octets = value.split('.').map(String::toInt)
        if (octets.any { it !in 0..255 }) return null
        val address = InetAddress.getByAddress(octets.map(Int::toByte).toByteArray()) as Inet4Address
        return address.takeIf { it.isSiteLocalAddress }
    }
}

data class TransportSnapshot(
    val running: Boolean,
    val tunSentPackets: Long,
    val tunReceivedPackets: Long,
    val sentBytes: Long,
    val receivedBytes: Long,
    val connections: Int,
    val errors: Long,
) {
    val transferred get() = tunSentPackets > 0 && tunReceivedPackets > 0 && sentBytes > 0 && receivedBytes > 0
}

internal object HevBridge {
    init { System.loadLibrary("adaptive-hev") }
    external fun reserve(token: Long): Boolean
    external fun run(token: Long, config: ByteArray, fd: Int): Int
    external fun stop(token: Long)
    external fun release(token: Long)
    external fun isReady(token: Long): Boolean
    external fun counters(token: Long): LongArray
}

internal class ExperimentalTransport(
    service: VpnService,
    network: Network,
    target: Inet4Address,
    private val tunnel: ParcelFileDescriptor,
    onFailure: () -> Unit,
) {
    private val token = nextToken.incrementAndGet()
    private val stopping = AtomicBoolean()
    private val outbound = OutboundTransport(maxSockets = 16, readTimeoutMillis = 60_000)
    private val relay: HevLoopbackRelay
    val finished = CompletableDeferred<Unit>()
    @Volatile var tunClosed = false
        private set

    init {
        if (!HevBridge.reserve(token)) throw IOException("Предыдущий сетевой сеанс ещё завершается.")
        try {
            outbound.updateNetwork(VpnOutboundSocketPolicy(service, network))
            relay = HevLoopbackRelay(outbound, target, onFailure)
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
                        failure = HevBridge.run(token, config, tunnel.fd) < 0 || !stopping.get()
                    } finally {
                        config.fill(0)
                    }
                } catch (_: Exception) {
                    failure = true
                } catch (_: LinkageError) {
                    failure = true
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
            HevBridge.stop(token)
            relay.close()
        }
    }

    fun snapshot(): TransportSnapshot {
        val counters = HevBridge.counters(token)
        val stats = outbound.statistics()
        return TransportSnapshot(
            HevBridge.isReady(token), counters[0], counters[1], stats.sentBytes, stats.receivedBytes,
            stats.activeTcp + stats.activeUdp, stats.failedOperations + relay.failedSessions,
        )
    }

    private fun finish(): Boolean {
        try {
            tunClosed = runCatching(tunnel::close).isSuccess
            relay.close()
            val relayStopped = runCatching(relay::awaitShutdown).getOrDefault(false)
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
          max-session-count: 16
          tcp-buffer-size: 32768
          udp-recv-buffer-size: 32768
          udp-copy-buffer-nums: 4
          connect-timeout: 5000
          tcp-read-write-timeout: 60000
          udp-read-write-timeout: 60000
    """.trimIndent()

    private companion object {
        val nextToken = AtomicLong()
    }
}
