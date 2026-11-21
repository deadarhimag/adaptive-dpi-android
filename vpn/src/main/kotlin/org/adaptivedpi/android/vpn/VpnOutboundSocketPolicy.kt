package org.adaptivedpi.android.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
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
        // Android создаёт дескриптор TCP лениво. Чтение опции создаёт его без bind/connect.
        socket.reuseAddress
        if (!vpn.protect(socket)) rejectProtection("TCP")
        network.bindSocket(socket)
    }

    override fun prepare(socket: DatagramSocket) {
        requirePhysicalNetwork()
        socket.reuseAddress
        if (!vpn.protect(socket)) rejectProtection("UDP")
        network.bindSocket(socket)
    }

    private fun rejectProtection(protocol: String): Nothing {
        Log.w("AdaptiveTun", "protect отклонён: $protocol")
        throw SocketException("Android не разрешил исключить исходящий сокет из VPN.")
    }

    private fun requirePhysicalNetwork() {
        val capabilities = connectivity.getNetworkCapabilities(network)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) != true) {
            throw SocketException("Исходящий сокет требует доступную сеть вне VPN.")
        }
    }
}
