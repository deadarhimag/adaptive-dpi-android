package org.adaptivedpi.android.engine

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong

/** Подготавливает ещё не подключённый сокет. Любой отказ должен завершаться исключением. */
interface OutboundSocketPolicy {
    fun prepare(socket: Socket)
    fun prepare(socket: DatagramSocket)
}

data class OutboundStatistics(
    val activeTcp: Int,
    val activeUdp: Int,
    val pending: Int,
    val sentBytes: Long,
    val receivedBytes: Long,
    val sentDatagrams: Long,
    val receivedDatagrams: Long,
    val failedOperations: Long,
    val openedTcp: Long,
    val openedUdp: Long,
) {
    val closedTcp get() = openedTcp - activeTcp
    val closedUdp get() = openedUdp - activeUdp
}

/** Исходящие сокеты одного сеанса. Вызовы ввода-вывода блокирующие, их выполняют рабочие потоки. */
class OutboundTransport(
    private val maxSockets: Int = 64,
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 15_000,
) : Closeable {
    private val lock = Any()
    private val sockets = LinkedHashMap<Closeable, Boolean>()
    private var network: OutboundSocketPolicy? = null
    private var closed = false
    private val sentBytes = AtomicLong()
    private val receivedBytes = AtomicLong()
    private val sentDatagrams = AtomicLong()
    private val receivedDatagrams = AtomicLong()
    private val failedOperations = AtomicLong()
    private var openedTcp = 0L
    private var openedUdp = 0L

    init {
        require(maxSockets in 1..1_024)
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
    }

    /**
     * Новый объект политики означает новое физическое подключение. null приостанавливает открытия.
     * Сокеты старого подключения закрываются, включая сокеты в процессе подключения.
     */
    fun updateNetwork(policy: OutboundSocketPolicy?) = synchronized(lock) {
        if (closed || network === policy) return@synchronized
        closeAll()
        network = policy
    }

    fun connectTcp(address: InetAddress, port: Int): TcpConnection {
        val endpoint = endpoint(address, port)
        val socket = open({ Socket(Proxy.NO_PROXY) }) { policy, candidate ->
            policy.prepare(candidate)
            candidate.soTimeout = readTimeoutMillis
            candidate.connect(endpoint, connectTimeoutMillis)
        }
        return TcpConnection(socket)
    }

    fun connectUdp(address: InetAddress, port: Int): UdpConnection {
        val endpoint = endpoint(address, port)
        val socket = open({ DatagramSocket(null) }) { policy, candidate ->
            policy.prepare(candidate)
            candidate.soTimeout = readTimeoutMillis
            candidate.connect(endpoint)
        }
        return UdpConnection(socket)
    }

    fun statistics(): OutboundStatistics = synchronized(lock) {
        OutboundStatistics(
            activeTcp = sockets.count { (socket, active) -> active && socket is Socket },
            activeUdp = sockets.count { (socket, active) -> active && socket is DatagramSocket },
            pending = sockets.count { !it.value },
            sentBytes = sentBytes.get(),
            receivedBytes = receivedBytes.get(),
            sentDatagrams = sentDatagrams.get(),
            receivedDatagrams = receivedDatagrams.get(),
            failedOperations = failedOperations.get(),
            openedTcp = openedTcp,
            openedUdp = openedUdp,
        )
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        network = null
        closeAll()
    }

    inner class TcpConnection internal constructor(private val socket: Socket) : Closeable {
        fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int = countFailures {
            val size = socket.getInputStream().read(buffer, offset, length)
            if (size > 0) receivedBytes.addAndGet(size.toLong())
            size
        }

        fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) = countFailures {
            socket.getOutputStream().write(buffer, offset, length)
            sentBytes.addAndGet(length.toLong())
            Unit
        }

        fun shutdownOutput() = countFailures { socket.shutdownOutput() }

        override fun close() = release(socket)
    }

    inner class UdpConnection internal constructor(private val socket: DatagramSocket) : Closeable {
        fun send(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
            require(length in 0..MAX_UDP_PAYLOAD)
            val datagram = DatagramPacket(buffer, offset, length)
            countFailures {
                socket.send(datagram)
                sentBytes.addAndGet(length.toLong())
                sentDatagrams.incrementAndGet()
            }
        }

        /** Буфер вмещает минимум на один байт больше ожидаемой датаграммы. */
        fun receive(buffer: ByteArray): Int {
            require(buffer.size in 1..MAX_UDP_PAYLOAD + 1)
            val datagram = DatagramPacket(buffer, buffer.size)
            return countFailures {
                socket.receive(datagram)
                if (datagram.length == buffer.size) throw SocketException("Датаграмма не поместилась в буфер.")
                receivedBytes.addAndGet(datagram.length.toLong())
                receivedDatagrams.incrementAndGet()
                datagram.length
            }
        }

        override fun close() = release(socket)
    }

    private fun endpoint(address: InetAddress, port: Int): InetSocketAddress {
        require(port in 1..65_535 && !address.isAnyLocalAddress)
        return InetSocketAddress(address, port)
    }

    private inline fun <S : Closeable> open(
        create: () -> S,
        connect: (OutboundSocketPolicy, S) -> Unit,
    ): S = countFailures {
        val (policy, socket) = synchronized(lock) {
            if (closed) throw SocketException("Исходящий транспорт остановлен.")
            val currentNetwork = network ?: throw SocketException("Физическая сеть недоступна.")
            if (sockets.size >= maxSockets) throw SocketException("Достигнут лимит исходящих сокетов.")
            currentNetwork to create().also { sockets[it] = false }
        }
        var connected = false
        try {
            connect(policy, socket)
            synchronized(lock) {
                if (!sockets.containsKey(socket)) throw SocketException("Исходящий сокет закрыт при смене сети.")
                sockets[socket] = true
                if (socket is Socket) openedTcp++ else openedUdp++
                connected = true
            }
            socket
        } finally {
            if (!connected) release(socket)
        }
    }

    private fun release(socket: Closeable) = synchronized(lock) {
        if (sockets.remove(socket) != null) closeSocket(socket)
    }

    private fun closeAll() {
        // SO_LINGER не включён. Закрытие дескриптора отменяет блокирующий ввод-вывод рабочих потоков.
        sockets.keys.forEach(::closeSocket)
        sockets.clear()
    }

    private fun closeSocket(socket: Closeable) {
        runCatching { countFailures(socket::close) }
    }

    private inline fun <T> countFailures(operation: () -> T): T = try {
        operation()
    } catch (error: Exception) {
        failedOperations.incrementAndGet()
        throw error
    }

    companion object {
        const val MAX_UDP_PAYLOAD = 65_507
    }
}
