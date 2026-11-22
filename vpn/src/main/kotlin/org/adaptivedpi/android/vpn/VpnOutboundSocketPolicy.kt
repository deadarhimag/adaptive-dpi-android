package org.adaptivedpi.android.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import org.adaptivedpi.android.engine.OutboundSocketPolicy
import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode
import org.adaptivedpi.android.engine.TransportOperation
import org.adaptivedpi.android.engine.TransportSocketException
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket

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
        bind { network.bindSocket(socket) }
    }

    override fun prepare(socket: DatagramSocket) {
        requirePhysicalNetwork()
        socket.reuseAddress
        if (!vpn.protect(socket)) rejectProtection("UDP")
        bind { network.bindSocket(socket) }
    }

    private fun rejectProtection(protocol: String): Nothing {
        Log.w("AdaptiveTun", "protect отклонён: $protocol")
        throw TransportSocketException(TransportCode.PROTECT_REJECTED, TransportCategory.PROTECTION, TransportOperation.PROTECT)
    }

    private fun requirePhysicalNetwork() {
        val capabilities = connectivity.getNetworkCapabilities(network)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) != true) {
            throw TransportSocketException(TransportCode.NO_PHYSICAL_NETWORK, TransportCategory.UNREACHABLE, TransportOperation.BIND)
        }
    }

    private inline fun bind(action: () -> Unit) {
        try {
            action()
        } catch (error: IOException) {
            throw TransportSocketException(TransportCode.UNKNOWN, TransportCategory.SOCKET_SETUP, TransportOperation.BIND, error)
        }
    }
}
