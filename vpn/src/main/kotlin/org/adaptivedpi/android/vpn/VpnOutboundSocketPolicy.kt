package org.adaptivedpi.android.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import org.adaptivedpi.android.engine.OutboundSocketPolicy
import java.net.DatagramSocket
import java.net.Socket
import java.net.SocketException

/** Политика одного физического подключения, выбранного Android-слоем VPN. */
internal class VpnOutboundSocketPolicy(
    private val vpn: VpnService,
    private val network: Network,
) : OutboundSocketPolicy {
    private val connectivity = checkNotNull(vpn.getSystemService(ConnectivityManager::class.java))

    override fun prepare(socket: Socket) {
        requirePhysicalNetwork()
        if (!vpn.protect(socket)) throw SocketException("Android не разрешил исключить TCP-сокет из VPN.")
        network.bindSocket(socket)
    }

    override fun prepare(socket: DatagramSocket) {
        requirePhysicalNetwork()
        if (!vpn.protect(socket)) throw SocketException("Android не разрешил исключить UDP-сокет из VPN.")
        network.bindSocket(socket)
    }

    private fun requirePhysicalNetwork() {
        val capabilities = connectivity.getNetworkCapabilities(network)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) != true) {
            throw SocketException("Исходящий сокет требует доступную сеть вне VPN.")
        }
    }
}
