package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.vpn.VpnOutboundSocketPolicy
import org.adaptivedpi.android.vpn.analysis.IpFamily
import org.adaptivedpi.android.vpn.analysis.SystemDns
import java.net.InetAddress
import java.net.Socket

/** Всё, что проба берёт у сети: имя, привязка сокета, тайм-аут чтения. */
internal interface ProbeNetwork {
    /** Адреса одного семейства. Отмена корутины возвращает управление сразу. */
    suspend fun resolve(host: String, family: IpFamily): List<InetAddress>

    /** Защищает сокет от VPN и привязывает его к физической сети. */
    fun prepare(socket: Socket)

    /** Тайм-аут чтения без Java `soTimeout` (урок RelayWait, [TRANSPORT.md]). */
    fun configureReadTimeout(socket: Socket, timeoutMillis: Int)
}

/** Проба идёт через ту же физическую сеть и политику `protect`, что и релей. */
internal class PhysicalProbeNetwork(
    private val dns: SystemDns,
    private val policy: VpnOutboundSocketPolicy,
) : ProbeNetwork {
    override suspend fun resolve(host: String, family: IpFamily) = dns.resolve(host, family)

    override fun prepare(socket: Socket) = policy.prepare(socket)

    override fun configureReadTimeout(socket: Socket, timeoutMillis: Int) =
        policy.configureTcpReadTimeout(socket, timeoutMillis)
}
