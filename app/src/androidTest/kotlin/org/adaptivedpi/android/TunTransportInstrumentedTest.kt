package org.adaptivedpi.android

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.adaptivedpi.android.vpn.ExperimentalTarget
import org.adaptivedpi.android.vpn.TransportSnapshot
import org.adaptivedpi.android.vpn.VpnController
import org.adaptivedpi.android.vpn.VpnState
import org.adaptivedpi.android.vpn.diagnostics.VpnEventHistory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Proxy
import java.net.Socket

class TunTransportInstrumentedTest {
    @Test
    fun tcpUdpAndDnsTraverseTunAcrossTwoSessions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val target = arguments.getString("transportTarget")
        if (arguments.getString("requireTransportFixture") == "true") {
            assertNotNull("Нужно передать transportTarget", target)
        }
        assumeTrue("Нужен локальный стенд и аргумент transportTarget", target != null)
        val ip = checkNotNull(ExperimentalTarget.parse(checkNotNull(target)))
        val connectivity = checkNotNull(instrumentation.targetContext.getSystemService(ConnectivityManager::class.java))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            if (arguments.getString("waitForVpnConsent") == "true") {
                scenario.onActivity { activity -> VpnService.prepare(activity)?.let { activity.startActivity(it) } }
                runBlocking {
                    withTimeout(120_000) {
                        while (VpnService.prepare(instrumentation.targetContext) != null) delay(400)
                    }
                }
            }
            assertNull("Сначала требуется системное согласие VPN", VpnService.prepare(instrumentation.targetContext))
            awaitRouteRemoval(connectivity, ip)
            exchangeAll(ip) { protocol, sent, received ->
                Log.i(TAG, "Стенд доступен до VPN: $protocol $sent/$received байт")
            }
            repeat(2) { index ->
                var failure: Throwable? = null
                try {
                    scenario.onActivity { assertNull(VpnController.requestStart(it, target)) }
                    awaitState(VpnState.EngineRunning)
                    assertOnlyFixtureRoute(connectivity, ip)
                    var before = snapshot()
                    assertTrue(before.running)
                    exchangeAll(ip) { protocol, sent, received ->
                        val after = verifyTunAdvanced(before, sent, received)
                        Log.i(TAG, "Сеанс ${index + 1}: $protocol $sent/$received байт, TUN " +
                            "+${after.tunSentPackets - before.tunSentPackets}/+${after.tunReceivedPackets - before.tunReceivedPackets}")
                        reportProgress("session${index + 1}=$protocol:$sent/$received")
                        before = after
                    }
                    assertTrue(before.transferred)
                } catch (error: Throwable) {
                    failure = error
                    val eventTypes = VpnEventHistory.state.value.entries.take(12).joinToString(", ") { it.type.name }
                    Log.w(TAG, "Сбой в состоянии ${VpnController.state.value}, события VPN: $eventTypes")
                    throw error
                } finally {
                    try {
                        scenario.onActivity { VpnController.stop() }
                        runBlocking { withTimeout(20_000) { VpnController.state.first { it.canStart } } }
                        awaitRouteRemoval(connectivity, ip)
                        if (failure == null) {
                            assertEquals(VpnState.Idle, VpnController.state.value)
                            assertFalse(snapshot().running)
                            assertEquals(0, snapshot().connections)
                            Log.i(TAG, "Сеанс ${index + 1}: маршрут снят, активных соединений 0")
                            reportProgress("session${index + 1}=closed")
                        }
                    } catch (cleanup: Throwable) {
                        if (failure == null) throw cleanup
                        failure.addSuppressed(cleanup)
                    }
                }
            }
        }
    }

    private fun reportProgress(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("transportProgress", value)
        })
    }

    private fun exchangeAll(ip: InetAddress, verified: (String, Int, Int) -> Unit) {
        val tcp = byteArrayOf(1, 2, 3, 4, 97)
        Socket(Proxy.NO_PROXY).use { client ->
            client.soTimeout = TIMEOUT
            client.connect(InetSocketAddress(ip, 39_052), TIMEOUT)
            client.getOutputStream().write(tcp)
            val returned = ByteArray(tcp.size)
            DataInputStream(client.getInputStream()).readFully(returned)
            assertArrayEquals("TCP echo", tcp, returned)
        }
        verified("TCP", tcp.size, tcp.size)

        val udp = byteArrayOf(5, 4, 3, 2, -14)
        assertArrayEquals("UDP echo", udp, exchangeUdp(ip, 39_052, udp))
        verified("UDP", udp.size, udp.size)

        val dns = byteArrayOf(
            0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 101, 120, 97, 109, 112, 108, 101, 4, 116, 101, 115, 116, 0, 0, 1, 0, 1,
        )
        val dnsReply = dns.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
        } + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192.toByte(), 0, 2, 45)
        assertArrayEquals("DNS A через UDP", dnsReply, exchangeUdp(ip, 39_053, dns))
        verified("DNS/UDP", dns.size, dnsReply.size)
    }

    private fun exchangeUdp(ip: InetAddress, port: Int, payload: ByteArray): ByteArray = DatagramSocket().use { client ->
        client.soTimeout = TIMEOUT
        client.connect(InetSocketAddress(ip, port))
        client.send(DatagramPacket(payload, payload.size))
        val packet = DatagramPacket(ByteArray(512), 512)
        client.receive(packet)
        packet.data.copyOf(packet.length)
    }

    private fun assertOnlyFixtureRoute(connectivity: ConnectivityManager, ip: InetAddress) = runBlocking {
        val link = withTimeout(TIMEOUT.toLong()) {
            while (true) {
                val found = findVpnRoute(connectivity, ip)
                if (found != null) return@withTimeout found
                delay(100)
            }
            error("Недостижимый код")
        }
        val routes = link.routes.filter {
            Build.VERSION.SDK_INT < 33 || it.type == RouteInfo.RTN_UNICAST
        }
        assertTrue("Нужен явный /32 к стенду", routes.any { it.destination.address == ip && it.destination.prefixLength == 32 })
        assertTrue("Неожиданный захватываемый маршрут", routes.all {
            val address = it.destination.address
            address is Inet4Address && it.destination.prefixLength == 32 &&
                (address == ip || address.address.contentEquals(byteArrayOf(192.toByte(), 0, 2, 1)))
        })
    }

    private fun awaitRouteRemoval(connectivity: ConnectivityManager, ip: InetAddress) = runBlocking {
        withTimeout(TIMEOUT.toLong()) {
            while (findVpnRoute(connectivity, ip) != null) delay(100)
        }
    }

    @Suppress("DEPRECATION")
    private fun findVpnRoute(connectivity: ConnectivityManager, ip: InetAddress): LinkProperties? =
        connectivity.allNetworks.asSequence().filter {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }.mapNotNull(connectivity::getLinkProperties).firstOrNull { link ->
            link.routes.any { it.destination.address == ip && it.destination.prefixLength == 32 }
        }

    private fun snapshot(): TransportSnapshot {
        assertNotNull(VpnController.transport.value)
        return checkNotNull(VpnController.transport.value)
    }

    private fun verifyTunAdvanced(before: TransportSnapshot, sent: Int, received: Int): TransportSnapshot = runBlocking {
        withTimeout(TIMEOUT.toLong()) {
            VpnController.transport.first { current ->
                current != null && current.tunSentPackets > before.tunSentPackets &&
                    current.tunReceivedPackets > before.tunReceivedPackets &&
                    current.sentBytes == before.sentBytes + sent && current.receivedBytes == before.receivedBytes + received
            }!!
        }
    }

    private fun awaitState(expected: VpnState) = runBlocking {
        withTimeout(20_000) { VpnController.state.first { it == expected } }
    }

    private companion object {
        const val TIMEOUT = 8_000
        const val TAG = "TunTransportTest"
    }
}
