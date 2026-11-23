package org.adaptivedpi.android

import android.app.Activity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
        val targetArg = arguments.getString("transportTarget")
        if (arguments.getString("requireTransportFixture") == "true") {
            assertNotNull("Нужно передать transportTarget", targetArg)
        }
        assumeTrue("Нужен локальный стенд и аргумент transportTarget", targetArg != null)
        val target = checkNotNull(targetArg)
        val ip = checkNotNull(ExperimentalTarget.parse(target))
        val connectivity = checkNotNull(instrumentation.targetContext.getSystemService(ConnectivityManager::class.java))
        ActivityScenario.launch(TransportTestActivity::class.java).use { scenario ->
            prepareVpn(scenario, arguments.getString("waitForVpnConsent") == "true")
            reportPhase("Проверка маршрута и доступности стенда до VPN")
            awaitRouteRemoval(connectivity, ip)
            exchangeAll(ip) { protocol, sent, received ->
                Log.i(TAG, "Стенд доступен до VPN: $protocol $sent/$received байт")
            }
            repeat(2) { index ->
                val session = "Сеанс ${index + 1}"
                var ownsSession = false
                var failure: Throwable? = null
                var activeTcp: Socket? = null
                var activeUdp: DatagramSocket? = null
                try {
                    scenario.onActivity { activity ->
                        requireIdle()
                        reportPhase("$session: запрашиваю запуск транспорта")
                        val consent = VpnController.requestStart(activity, target)
                        if (consent != null) {
                            VpnController.abandonConsent(consent.id)
                            error("$session: VPN-разрешение отозвано перед запуском. Повторите тест.")
                        }
                        assertEquals("$session: контроллер не принял запуск. ${vpnDetails()}", VpnState.Starting, VpnController.state.value)
                        ownsSession = true
                    }
                    val started = awaitTransition("$session: запуск", VpnState.Starting, VpnState.TransportStarting)
                    assertEquals("$session: ожидался EngineRunning. ${vpnDetails()}", VpnState.EngineRunning, started)
                    reportPhase("$session: движок запущен, проверяю маршрут TUN")
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
                    val tcp = Socket(Proxy.NO_PROXY).also { activeTcp = it }
                    val udp = DatagramSocket().also { activeUdp = it }
                    assertArrayEquals(byteArrayOf(71), exchangeTcp(tcp, ip, byteArrayOf(71)))
                    assertArrayEquals(byteArrayOf(72), exchangeUdp(udp, ip, 39_052, byteArrayOf(72)))
                    runBlocking {
                        withTimeout(TIMEOUT.toLong()) {
                            VpnController.transport.first { current ->
                                current != null && current.openedTcp > before.openedTcp &&
                                    current.openedUdp > before.openedUdp && current.openedTcp > current.closedTcp &&
                                    current.openedUdp > current.closedUdp
                            }
                        }
                    }
                    reportPhase("$session: TCP и UDP подтверждены открытыми перед Stop")
                } catch (error: Throwable) {
                    failure = error
                    Log.w(TAG, "$session: ${error.javaClass.simpleName}. ${vpnDetails()}")
                    throw error
                } finally {
                    if (ownsSession) {
                        try {
                            reportPhase("$session: запрашиваю остановку")
                            instrumentation.runOnMainSync { VpnController.stop() }
                            val stopped = awaitTransition("$session: остановка", VpnState.Stopping)
                            awaitRouteRemoval(connectivity, ip)
                            if (failure == null) {
                                assertEquals("$session: не завершён штатно. ${vpnDetails()}", VpnState.Idle, stopped)
                                val final = snapshot()
                                assertFalse(final.running)
                                assertTrue("$session: счётчики TUN должны быть итоговыми", final.finalized)
                                assertTrue(final.transferred)
                                assertEquals(0, final.connections)
                                reportPhase("$session: маршрут снят, активных соединений 0")
                                reportProgress("session${index + 1}=closed")
                            }
                        } catch (cleanup: Throwable) {
                            Log.w(TAG, "$session: ошибка остановки ${cleanup.javaClass.simpleName}. ${vpnDetails()}")
                            if (failure == null) throw cleanup
                            failure.addSuppressed(cleanup)
                        } finally {
                            try { activeTcp?.close() } finally { activeUdp?.close() }
                        }
                    }
                }
            }
        }
    }

    private fun prepareVpn(scenario: ActivityScenario<TransportTestActivity>, waitForConsent: Boolean) {
        var consentResult: StateFlow<Int?>? = null
        scenario.onActivity { activity ->
            requireIdle()
            val request = VpnService.prepare(activity)
            if (request == null) {
                reportPhase("Разрешение VPN уже выдано")
            } else {
                check(waitForConsent) { "Требуется системное согласие VPN. ${vpnDetails()}" }
                reportPhase("Открываю системный запрос на разрешение VPN")
                consentResult = activity.consentResult
                activity.launchConsent(request)
            }
        }
        consentResult?.let { result ->
            val code = runBlocking { withTimeoutOrNull(120_000) { result.filterNotNull().first() } }
            check(code != null) { "За две минуты не получен ответ на системный запрос VPN. ${vpnDetails()}" }
            reportPhase("Получен результат системного запроса VPN, код $code")
            assertEquals("Системное разрешение VPN не подтверждено. ${vpnDetails()}", Activity.RESULT_OK, code)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertNull("Разрешение VPN недоступно после подтверждения", VpnService.prepare(it)) }
        }
    }

    private fun requireIdle() {
        check(VpnController.state.value == VpnState.Idle) {
            "Занят контроллер VPN. Остановите действующий сеанс перед тестом. ${vpnDetails()}"
        }
    }

    private fun reportPhase(message: String) {
        val status = "$message. Состояние VPN: ${VpnController.state.value}"
        Log.i(TAG, status)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("transportPhase", status)
        })
    }

    private fun vpnDetails(): String {
        val events = VpnEventHistory.state.value.entries.take(12).joinToString(", ") {
            listOfNotNull(it.type.name, it.state?.name, it.code?.name).joinToString("/")
        }
        return "Состояние VPN: ${VpnController.state.value}. События VPN: $events"
    }

    private fun reportProgress(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("transportProgress", value)
        })
    }

    internal fun exchangeAll(ip: InetAddress, verified: (String, Int, Int) -> Unit) {
        val tcp = byteArrayOf(1, 2, 3, 4, 97)
        Socket(Proxy.NO_PROXY).use { client ->
            assertArrayEquals("TCP echo", tcp, exchangeTcp(client, ip, tcp))
        }
        verified("TCP", tcp.size, tcp.size)

        val udp = byteArrayOf(5, 4, 3, 2, -14)
        DatagramSocket().use { assertArrayEquals("UDP echo", udp, exchangeUdp(it, ip, 39_052, udp)) }
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
        DatagramSocket().use { assertArrayEquals("DNS A через UDP", dnsReply, exchangeUdp(it, ip, 39_053, dns)) }
        verified("DNS/UDP", dns.size, dnsReply.size)
    }

    private fun exchangeTcp(client: Socket, ip: InetAddress, payload: ByteArray): ByteArray {
        client.soTimeout = TIMEOUT
        client.connect(InetSocketAddress(ip, 39_052), TIMEOUT)
        client.getOutputStream().write(payload)
        return ByteArray(payload.size).also { DataInputStream(client.getInputStream()).readFully(it) }
    }

    private fun exchangeUdp(client: DatagramSocket, ip: InetAddress, port: Int, payload: ByteArray): ByteArray {
        client.soTimeout = TIMEOUT
        client.connect(InetSocketAddress(ip, port))
        client.send(DatagramPacket(payload, payload.size))
        val packet = DatagramPacket(ByteArray(512), 512)
        client.receive(packet)
        return packet.data.copyOf(packet.length)
    }

    internal fun assertOnlyFixtureRoute(connectivity: ConnectivityManager, ip: InetAddress) = runBlocking {
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

    private fun awaitTransition(phase: String, vararg pending: VpnState): VpnState = runBlocking {
        withTimeoutOrNull(20_000) {
            VpnController.state.first { state ->
                reportPhase("$phase, переход $state")
                state !in pending
            }
        } ?: error("$phase: переход не завершён за 20 секунд. ${vpnDetails()}")
    }

    private companion object {
        const val TIMEOUT = 8_000
        const val TAG = "TunTransportTest"
    }
}
