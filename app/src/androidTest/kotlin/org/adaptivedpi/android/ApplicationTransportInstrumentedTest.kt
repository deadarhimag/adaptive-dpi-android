package org.adaptivedpi.android

import android.content.ComponentName
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Process
import android.os.ResultReceiver
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.adaptivedpi.android.vpn.VpnController
import org.adaptivedpi.android.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ApplicationTransportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val probePackage = instrumentation.context.packageName

    @Test
    fun separatePackageUsesTcpUdpDnsAndHttpsAcrossTwoVpnSessions() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val target = arguments.getString("transportTarget")
        val certificate = arguments.getString("transportCertificate")
        if (arguments.getString("requireTransportFixture") == "true") {
            assertNotNull("Требуется адрес стенда", target)
            assertNotNull("Требуется публичный тестовый сертификат HTTPS", certificate)
        }
        assumeTrue("Нужен стенд с HTTPS", target != null && certificate != null)
        val before = awaitProbeNetwork(vpn = false)
        assertNotEquals("Проверка должна идти из отдельного пакета с другим UID", Process.myUid(), before.getInt("uid"))

        ActivityScenario.launch(TransportTestActivity::class.java).use { scenario ->
            repeat(2) { index ->
                try {
                    scenario.onActivity {
                        assertEquals(VpnState.Idle, VpnController.state.value)
                        assertNull("Стенд требует согласие VPN", VpnService.prepare(it))
                        assertNull(VpnController.requestStart(it, applicationPackage = probePackage))
                        assertEquals(VpnState.Starting, VpnController.state.value)
                    }
                    awaitState(VpnState.EngineRunning)
                    assertVpnPackageScope()
                    awaitProbeNetwork(vpn = true)
                    val result = queryProbe(target, certificate)
                    assertEquals(before.getInt("uid"), result.getInt("uid"))
                    assertTrue("Тестовый пакет должен использовать VPN", result.getBoolean("vpn"))
                    assertTrue("Требуется полный маршрут IPv4", result.getBoolean("ipv4Default"))
                    assertFalse("IPv6 не должен маршрутизироваться через VPN", result.getBoolean("ipv6Default"))
                    assertTrue("Android должен видеть DNS по IPv4", result.getBoolean("ipv4Dns"))
                    for (protocol in listOf("tcp", "udp", "dns", "https")) {
                        assertTrue("Отдельное приложение не получило ответ $protocol", result.getBoolean(protocol))
                    }
                    withTimeout(8_000) {
                        VpnController.transport.first { stats ->
                            stats != null && stats.applicationMode && stats.transferred &&
                                stats.openedTcp >= 2 && stats.openedUdp >= 2
                        }
                    }
                    Log.i(TAG, "Сеанс ${index + 1}: отдельный UID подтвердил TCP, UDP, DNS/UDP и содержимое HTTPS")
                } finally {
                    instrumentation.runOnMainSync { VpnController.stop() }
                    withTimeout(20_000) { VpnController.state.first { it.canStart } }
                }
                assertEquals(VpnState.Idle, VpnController.state.value)
                val stopped = checkNotNull(VpnController.transport.value)
                assertFalse(stopped.running)
                assertEquals(0, stopped.connections)
                assertEquals(stopped.openedTcp, stopped.closedTcp)
                assertEquals(stopped.openedUdp, stopped.closedUdp)
                awaitProbeNetwork(vpn = false)
                Log.i(TAG, "Сеанс ${index + 1}: маршрут пакета снят, TCP и UDP закрыты")
            }
        }
    }

    private fun assertVpnPackageScope() {
        val connectivity = checkNotNull(app.getSystemService(ConnectivityManager::class.java))
        val active = checkNotNull(connectivity.activeNetwork)
        assertFalse(checkNotNull(connectivity.getNetworkCapabilities(active)).hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }

    private suspend fun awaitProbeNetwork(vpn: Boolean): Bundle {
        val actual = withTimeoutOrNull(8_000) {
            var result = queryProbe()
            while (!result.getBoolean("network") || result.getBoolean("vpn") != vpn) {
                delay(100)
                result = queryProbe()
            }
            result
        }
        val expected = if (vpn) "VPN" else "физическая сеть"
        return checkNotNull(actual) { "Отдельному приложению не стала доступна ожидаемая сеть ($expected)" }
    }

    private suspend fun queryProbe(target: String? = null, certificate: String? = null): Bundle {
        val response = CompletableDeferred<Pair<Int, Bundle>>()
        val reply = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
                response.complete(resultCode to resultData)
            }
        }
        val intent = Intent().setComponent(ComponentName(probePackage, AppTransportProbe::class.java.name))
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("reply", reply)
        if (target != null) intent.putExtra("target", target).putExtra("certificate", certificate)
        app.sendBroadcast(intent)
        val (code, result) = withTimeout(20_000) { response.await() }
        assertEquals("Проверка отдельным приложением: ${result.getString("error")}", 0, code)
        return result
    }

    private suspend fun awaitState(expected: VpnState) {
        withTimeout(20_000) {
            val state = VpnController.state.first {
                it == expected || it == VpnState.Failed || it == VpnState.StartFailed || it == VpnState.ConsentRevoked
            }
            assertEquals("Транспорт приложения завершился в другом состоянии", expected, state)
        }
    }

    private companion object {
        const val TAG = "AppTransportTest"
    }
}
