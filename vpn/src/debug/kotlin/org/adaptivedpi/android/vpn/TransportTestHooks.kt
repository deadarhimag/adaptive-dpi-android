package org.adaptivedpi.android.vpn

import android.net.Network
import android.net.VpnService
import org.adaptivedpi.android.vpn.analysis.SystemDns
import org.adaptivedpi.android.vpn.diagnostics.PhysicalProbeNetwork
import org.adaptivedpi.android.vpn.diagnostics.ProbeNetwork
import org.adaptivedpi.android.vpn.diagnostics.ProbeStage
import org.adaptivedpi.android.vpn.diagnostics.ProbeStatus
import org.adaptivedpi.android.vpn.diagnostics.ResourceProbe
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Синхронизация инструментальных тестов внутри debug-процесса. */
object TransportTestHooks {
    init { System.loadLibrary("adaptive-hev") }

    @Volatile var afterTcpPrepare: ((Socket) -> Unit)? = null

    @Volatile private var fixtureRoute: Pair<VpnService, Network>? = null

    internal fun fixtureRouteStarted(service: VpnService, network: Network) {
        fixtureRoute = service to network
    }

    internal fun fixtureRouteEnded(service: VpnService) {
        if (fixtureRoute?.first === service) fixtureRoute = null
    }

    /** Положительный контроль отличается от пробы ровно отсутствием protect и bind. */
    suspend fun checkFixtureResource(target: InetAddress, certificateDer: ByteArray, protected: Boolean): Boolean {
        require(target is Inet4Address && target.isSiteLocalAddress)
        val (service, network) = checkNotNull(fixtureRoute)
        val policy = VpnOutboundSocketPolicy(service, network)
        val physical = PhysicalProbeNetwork(SystemDns(network), policy)
        val route = if (protected) physical else object : ProbeNetwork by physical {
            override fun prepare(socket: Socket) = Unit
        }
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificateDer))
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("fixture", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        val body = "adaptive-tun-https\n".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
        val uri = URI("https", null, target.hostAddress, 39054, "/probe", null, null)
        val result = ResourceProbe(route, tls.socketFactory, addressAllowed = { it == target })
            .check(uri, hash, onlyAddress = target)
        return result.lastOrNull()?.let { it.stage == ProbeStage.CONTENT && it.status == ProbeStatus.OK } == true
    }

    internal fun tcpPrepared(socket: Socket) {
        afterTcpPrepare?.invoke(socket)
    }

    fun holdNativePollLock(entered: CountDownLatch, release: CountDownLatch) =
        withNativePollLock(Runnable {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "Тест не освободил native mutex за 15 секунд" }
        })

    private external fun withNativePollLock(action: Runnable)
}
