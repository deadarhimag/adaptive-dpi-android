package org.adaptivedpi.android

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.adaptivedpi.android.vpn.analysis.NativeSocketOps
import java.io.DataInputStream
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Эксперимент 1.6 «аналоги без root»: каждая операция на loopback-паре своих сокетов.
 * Результат строки - `ok …`, `errno N` или имя исключения. Только debug, в продуктовый путь не входит.
 * Влияние на сегменты здесь не видно: его показывает `scripts/kernel-ops-pcap.py` на Linux-хосте.
 */
object KernelCapabilityProbe {
    data class Row(val operation: String, val result: String) {
        fun text() = "$operation\t$result"
    }

    // Номера опций, которых нет в OsConstants: linux/tcp.h из sysroot NDK (usr/include/linux/tcp.h).
    private const val TCP_MAXSEG = 2
    private const val TCP_FASTOPEN_CONNECT = 30

    val OPERATIONS = listOf(
        "TCP_INFO", "гарантированный разрез", "RST без фильтра (контроль)", "BPF-фильтр RST", "vmsplice + splice",
        "TCP_MD5SIG", "TCP_MAXSEG до connect", "SO_RCVBUF до connect", "TCP_FASTOPEN_CONNECT", "IP_TTL", "MSG_OOB через Os.sendto",
    )

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    fun run(): List<Row> = listOf(
        row(OPERATIONS[0]) { tcpInfo() },
        row(OPERATIONS[1]) { guaranteedSplit() },
        row(OPERATIONS[2]) { reset(filter = false) },
        row(OPERATIONS[3]) { reset(filter = true) },
        row(OPERATIONS[4]) { splice() },
        row(OPERATIONS[5]) { md5() },
        row(OPERATIONS[6]) { maxSegment() },
        row(OPERATIONS[7]) { receiveBuffer() },
        row(OPERATIONS[8]) { fastOpen() },
        row(OPERATIONS[9]) { ttl() },
        row(OPERATIONS[10]) { urgent() },
    )

    /** Обычная передача после эксперимента: новая пара сокетов обменивается данными в обе стороны. */
    fun loopbackStillWorks(): String = pair { client, server ->
        client.getOutputStream().write(byteArrayOf(1, 2, 3))
        val request = ByteArray(3).also(DataInputStream(server.getInputStream())::readFully)
        server.getOutputStream().write(request)
        val response = ByteArray(3).also(DataInputStream(client.getInputStream())::readFully)
        if (response.contentEquals(byteArrayOf(1, 2, 3))) "ok" else "данные изменились"
    }

    private fun tcpInfo() = pair { client, _ ->
        val info = NativeSocketOps.withDescriptor(client) { NativeSocketOps.tcpInfo(it) }
        if (info[0] < 0) errno(-info[0]) else "ok notsent=${info[1]} retrans=${info[2]} rtt=${info[3]} мкс"
    }

    // Первая часть, ожидание tcpi_notsent_bytes == 0, затем остаток. Границы сегментов здесь не видны.
    private fun guaranteedSplit() = pair { client, server ->
        client.tcpNoDelay = true
        NativeSocketOps.withDescriptor(client) { fd ->
            val output = client.getOutputStream()
            output.write(ByteArray(100) { 1 })
            val started = System.nanoTime()
            var left: Int
            do {
                left = NativeSocketOps.notSent(fd)
                if (left <= 0) break
                Thread.sleep(1)
            } while (System.nanoTime() - started < 1_000_000_000L)
            val waitedMicros = (System.nanoTime() - started) / 1_000
            when {
                left < 0 -> errno(-left)
                left > 0 -> "нет: notsent=$left через 1 с"
                else -> {
                    output.write(ByteArray(200) { 2 })
                    DataInputStream(server.getInputStream()).readFully(ByteArray(300))
                    "ok ожидание $waitedMicros мкс, сервер получил 300 байт"
                }
            }
        }
    }

    // Сервер закрывает соединение с SO_LINGER 0 (RST). С фильтром клиент не должен увидеть сброс.
    private fun reset(filter: Boolean) = pair { client, server ->
        val attached = if (filter) NativeSocketOps.withDescriptor(client) { NativeSocketOps.attachRstFilter(it) } else 0
        if (attached < 0) return@pair errno(-attached)
        server.setSoLinger(true, 0)
        server.close()
        client.soTimeout = 500
        val outcome = try {
            "прочитано ${client.getInputStream().read()}"
        } catch (_: SocketTimeoutException) {
            "тишина 500 мс"
        } catch (error: SocketException) {
            "сброс" + ((error.cause as? ErrnoException)?.let { " errno ${it.errno}" } ?: " ${error.javaClass.simpleName}")
        }
        if (!filter) return@pair outcome
        val detached = NativeSocketOps.withDescriptor(client) { NativeSocketOps.detachFilter(it) }
        "${if (outcome == "тишина 500 мс") "ok RST отброшен" else "нет эффекта"} ($outcome); снятие ${code(detached)}"
    }

    private fun splice() = pair { client, server ->
        val sent = NativeSocketOps.withDescriptor(client) { NativeSocketOps.spliceProbe(it) }
        if (sent < 0) return@pair errno(-sent)
        val received = server.getInputStream().read()
        if (received == 'S'.code) "ok сервер получил байт" else "отправлено $sent, сервер получил $received"
    }

    private fun md5() = Socket().use { socket ->
        socket.reuseAddress // Android создаёт дескриптор лениво; чтение опции создаёт его.
        NativeSocketOps.withDescriptor(socket) { fd ->
            val enabled = NativeSocketOps.setMd5Sig(fd, loopback.address, true)
            if (enabled < 0) errno(-enabled) else "ok установлен, снятие ${code(NativeSocketOps.setMd5Sig(fd, loopback.address, false))}"
        }
    }

    private fun maxSegment() = pair(configure = { socket -> fileDescriptor(socket) { Os.setsockoptInt(it, OsConstants.IPPROTO_TCP, TCP_MAXSEG, 300) } }) { client, server ->
        val effective = fileDescriptor(client) { Os.getsockoptInt(it, OsConstants.IPPROTO_TCP, TCP_MAXSEG) }
        server.getOutputStream().write(ByteArray(4_000))
        DataInputStream(client.getInputStream()).readFully(ByteArray(4_000))
        "ok запрошено 300, после connect $effective, передача 4000 байт прошла"
    }

    private fun receiveBuffer() = pair(configure = { socket -> fileDescriptor(socket) { Os.setsockoptInt(it, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 2_048) } }) { client, server ->
        val effective = fileDescriptor(client) { Os.getsockoptInt(it, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF) }
        server.getOutputStream().write(ByteArray(20_000))
        DataInputStream(client.getInputStream()).readFully(ByteArray(20_000))
        "ok запрошено 2048, ядро $effective, передача 20000 байт прошла"
    }

    private fun fastOpen() = pair(configure = { socket -> fileDescriptor(socket) { Os.setsockoptInt(it, OsConstants.IPPROTO_TCP, TCP_FASTOPEN_CONNECT, 1) } }) { client, server ->
        client.getOutputStream().write(ByteArray(50) { 7 })
        DataInputStream(server.getInputStream()).readFully(ByteArray(50))
        "ok опция принята, передача прошла (данные в SYN видны только в PCAP)"
    }

    private fun ttl() = pair { client, server ->
        fileDescriptor(client) { fd ->
            val previous = Os.getsockoptInt(fd, OsConstants.IPPROTO_IP, OsConstants.IP_TTL)
            Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, OsConstants.IP_TTL, 5)
            val applied = Os.getsockoptInt(fd, OsConstants.IPPROTO_IP, OsConstants.IP_TTL)
            client.getOutputStream().write(ByteArray(10))
            DataInputStream(server.getInputStream()).readFully(ByteArray(10))
            Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, OsConstants.IP_TTL, previous)
            "ok было $previous, установлено $applied, восстановлено ${Os.getsockoptInt(fd, OsConstants.IPPROTO_IP, OsConstants.IP_TTL)}"
        }
    }

    private fun urgent() = pair { client, _ ->
        val sent = fileDescriptor(client) { Os.sendto(it, byteArrayOf(0x55), 0, 1, OsConstants.MSG_OOB, null, 0) }
        "ok отправлено $sent байт"
    }

    private fun row(operation: String, action: () -> String) = Row(
        operation,
        try {
            action()
        } catch (error: ErrnoException) {
            errno(error.errno)
        } catch (error: Exception) {
            "исключение ${error.javaClass.simpleName}" + ((error.cause as? ErrnoException)?.let { " errno ${it.errno}" } ?: "")
        },
    )

    private fun pair(configure: (Socket) -> Unit = {}, action: (Socket, Socket) -> String): String =
        ServerSocket(0, 1, loopback).use { listener ->
            listener.soTimeout = 3_000
            Socket().use { client ->
                client.reuseAddress // Дескриптор до connect нужен для опций, которые ставятся до подключения.
                configure(client)
                client.connect(InetSocketAddress(loopback, listener.localPort), 3_000)
                client.soTimeout = 3_000
                listener.accept().use { server ->
                    server.soTimeout = 3_000
                    action(client, server)
                }
            }
        }

    private fun <T> fileDescriptor(socket: Socket, action: (FileDescriptor) -> T): T =
        checkNotNull(ParcelFileDescriptor.fromSocket(socket)) { "У сокета нет дескриптора" }.use { action(it.fileDescriptor) }

    private fun errno(value: Int) = "errno $value"

    private fun code(value: Int) = if (value < 0) errno(-value) else "ok"
}
