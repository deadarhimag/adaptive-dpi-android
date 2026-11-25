package org.adaptivedpi.android.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
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
        TransportTestHooks.tcpPrepared(socket)
    }

    override fun prepare(socket: DatagramSocket) {
        requirePhysicalNetwork()
        socket.reuseAddress
        if (!vpn.protect(socket)) rejectProtection("UDP")
        bind { network.bindSocket(socket) }
    }

    override fun configureUdpReadTimeout(socket: DatagramSocket, timeoutMillis: Int) {
        // Java-тайм-аут Android использует poll(), который может пережить close().
        // Тайм-аут ядра оставляет receive в recvfrom(), где close будит заблокированный поток.
        ParcelFileDescriptor.fromDatagramSocket(socket).use { descriptor ->
            try {
                Os.setsockoptTimeval(
                    descriptor.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                    StructTimeval.fromMillis(timeoutMillis.toLong()),
                )
            } catch (error: ErrnoException) {
                throw IOException("Не удалось настроить тайм-аут UDP.", error)
            }
        }
    }

    /** Тайм-аут чтения TCP на уровне ядра (`SO_RCVTIMEO`): `close()` из другого потока будит чтение. */
    fun configureTcpReadTimeout(socket: Socket, timeoutMillis: Int) {
        ParcelFileDescriptor.fromSocket(socket).use { descriptor ->
            try {
                Os.setsockoptTimeval(
                    descriptor.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                    StructTimeval.fromMillis(timeoutMillis.toLong()),
                )
            } catch (error: ErrnoException) {
                throw IOException("Не удалось настроить тайм-аут TCP.", error)
            }
        }
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
