package org.adaptivedpi.android.engine

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
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
    private val failures: TransportFailureReporter = TransportFailureReporter(),
) : Closeable {
    private val lock = Any()
    private val tcpConnectGate = Any()
    private val sockets = LinkedHashMap<Closeable, SocketLease<*>>()
    private var network: OutboundSocketPolicy? = null
    private var closed = false
    @Volatile private var accepting = true
    private val sentBytes = AtomicLong()
    private val receivedBytes = AtomicLong()
    private val sentDatagrams = AtomicLong()
    private val receivedDatagrams = AtomicLong()
    private val failedOperations = AtomicLong()
    private val closures = SocketClosures()
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
        closeAll(TransportCancellation.NETWORK)
        network = policy
    }

    fun connectTcp(address: InetAddress, port: Int): TcpConnection {
        val endpoint = endpoint(address, port)
        val site = TransportSite(if (port == 53) TransportSubsystem.DNS else TransportSubsystem.TCP, TransportOperation.CONNECT,
            TransportProtocol.TCP, TransportDirection.OUTBOUND)
        val socket = open(site, { Socket(Proxy.NO_PROXY) }, OutboundSocketPolicy::prepare) { candidate ->
            candidate.connect(endpoint, connectTimeoutMillis)
        }
        return TcpConnection(socket, site)
    }

    fun connectUdp(address: InetAddress, port: Int): UdpConnection {
        val endpoint = endpoint(address, port)
        val site = TransportSite(if (port == 53) TransportSubsystem.DNS else TransportSubsystem.UDP, TransportOperation.CONNECT,
            TransportProtocol.UDP, TransportDirection.OUTBOUND)
        val socket = open(site, { DatagramSocket(null) }, OutboundSocketPolicy::prepare) { candidate ->
            candidate.connect(endpoint)
        }
        return UdpConnection(socket, site)
    }

    fun statistics(): OutboundStatistics = synchronized(lock) {
        OutboundStatistics(
            activeTcp = sockets.count { (socket, lease) -> lease.active && socket is Socket },
            activeUdp = sockets.count { (socket, lease) -> lease.active && socket is DatagramSocket },
            pending = sockets.count { !it.value.active },
            sentBytes = sentBytes.get(),
            receivedBytes = receivedBytes.get(),
            sentDatagrams = sentDatagrams.get(),
            receivedDatagrams = receivedDatagrams.get(),
            failedOperations = failedOperations.get(),
            openedTcp = openedTcp,
            openedUdp = openedUdp,
        )
    }

    fun socketClosures(): SocketCloseSummary = closures.snapshot()

    fun stopAcceptingNewConnections() = synchronized(tcpConnectGate) { accepting = false }

    /** Фоновая часть Stop. Активные соединения по-прежнему закрывает владелец релея. */
    fun closePendingTcpConnections() = synchronized(lock) {
        check(!accepting)
        sockets.values.filter { it.socket is Socket && !it.active }.forEach {
            sockets.remove(it.socket)
            closeSocket(it, TransportCancellation.SESSION)
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        stopAcceptingNewConnections()
        closed = true
        network = null
        closeAll(TransportCancellation.SESSION)
    }

    inner class TcpConnection internal constructor(private val lease: SocketLease<Socket>, site: TransportSite) : Closeable {
        private val socket = lease.socket
        private val readSite = site.copy(operation = TransportOperation.READ, direction = TransportDirection.INBOUND)
        private val writeSite = site.copy(operation = TransportOperation.WRITE)
        private val closeSite = site.copy(operation = TransportOperation.SHUTDOWN_OUTPUT)
        @Volatile private var peerEof = false

        internal fun useRelayIdleTimeout() = countFailures(readSite, { lease.cancellation }) {
            socket.soTimeout = 0
        }

        fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int = countFailures(
            readSite, { lease.cancellation },
        ) {
            val size = socket.getInputStream().read(buffer, offset, length)
            if (size > 0) receivedBytes.addAndGet(size.toLong())
            if (size < 0) peerEof = true
            size
        }

        fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) = countFailures(
            writeSite, { lease.cancellation },
        ) {
            socket.getOutputStream().write(buffer, offset, length)
            sentBytes.addAndGet(length.toLong())
            Unit
        }

        fun shutdownOutput() = countFailures(closeSite, { lease.cancellation }, TransportCategory.CLEANUP, { peerEof }) {
            socket.shutdownOutput()
        }

        override fun close() = release(lease)
    }

    inner class UdpConnection internal constructor(private val lease: SocketLease<DatagramSocket>, site: TransportSite) : Closeable {
        private val socket = lease.socket
        private val sendSite = site.copy(operation = TransportOperation.SEND)
        private val receiveSite = site.copy(operation = TransportOperation.RECEIVE, direction = TransportDirection.INBOUND)

        fun send(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
            require(length in 0..MAX_UDP_PAYLOAD)
            val datagram = DatagramPacket(buffer, offset, length)
            countFailures(sendSite, { lease.cancellation }) {
                socket.send(datagram)
                sentBytes.addAndGet(length.toLong())
                sentDatagrams.incrementAndGet()
            }
        }

        /** Буфер вмещает минимум на один байт больше ожидаемой датаграммы. */
        fun receive(buffer: ByteArray): Int {
            require(buffer.size in 1..MAX_UDP_PAYLOAD + 1)
            val datagram = DatagramPacket(buffer, buffer.size)
            return countFailures(receiveSite, { lease.cancellation }) {
                socket.receive(datagram)
                if (datagram.length == buffer.size) throw TransportSocketException(TransportCode.DATAGRAM_LIMIT, TransportCategory.LIMIT)
                receivedBytes.addAndGet(datagram.length.toLong())
                receivedDatagrams.incrementAndGet()
                datagram.length
            }
        }

        override fun close() = release(lease)
    }

    private fun endpoint(address: InetAddress, port: Int): InetSocketAddress {
        require(port in 1..65_535 && !address.isAnyLocalAddress)
        return InetSocketAddress(address, port)
    }

    private inline fun <S : Closeable> open(
        site: TransportSite,
        create: () -> S,
        prepare: (OutboundSocketPolicy, S) -> Unit,
        connect: (S) -> Unit,
    ): SocketLease<S> {
        val setup = site.copy(subsystem = TransportSubsystem.SOCKET, operation = TransportOperation.INIT, direction = TransportDirection.CONTROL)
        val (policy, lease) = countFailures(setup, fallback = TransportCategory.SOCKET_SETUP) {
            synchronized(lock) {
                if (!accepting) throw TransportSocketException(TransportCode.SESSION_CANCELLED, TransportCategory.CANCELLED)
                val currentNetwork = network ?: throw TransportSocketException(TransportCode.NO_PHYSICAL_NETWORK, TransportCategory.UNREACHABLE)
                if (sockets.size >= maxSockets) throw TransportSocketException(TransportCode.SOCKET_LIMIT, TransportCategory.LIMIT)
                currentNetwork to SocketLease(create()).also { sockets[it.socket] = it }
            }
        }
        val socket = lease.socket
        var connected = false
        try {
            countFailures(setup, { lease.cancellation }, TransportCategory.SOCKET_SETUP) {
                prepare(policy, socket)
                when (socket) {
                    is Socket -> socket.soTimeout = readTimeoutMillis
                    is DatagramSocket -> socket.soTimeout = readTimeoutMillis
                }
            }
            return countFailures(site, { lease.cancellation }) {
                if (socket is Socket) synchronized(tcpConnectGate) {
                    // Допуск упорядочен со Stop. Сам блокирующий connect выполняется вне замков.
                    checkConnectAllowed(lease)
                }
                connect(socket)
                synchronized(lock) {
                    if (socket is Socket) synchronized(tcpConnectGate) { activate(lease) } else activate(lease)
                    connected = true
                }
                lease
            }
        } catch (error: IOException) {
            if (socket is Socket) {
                val reason = connectCancellation(lease)
                val reported = error as? TransportSocketException
                if (reason != null && reported?.code != reason.code) {
                    throw TransportSocketException(reason.code, TransportCategory.CANCELLED, reported?.operation, error)
                }
            }
            throw error
        } finally {
            if (!connected) release(lease)
        }
    }

    private fun connectCancellation(lease: SocketLease<*>) =
        lease.cancellation ?: if (!accepting) TransportCancellation.SESSION else null

    private fun checkConnectAllowed(lease: SocketLease<*>) {
        connectCancellation(lease)?.let { throw TransportSocketException(it.code, TransportCategory.CANCELLED) }
    }

    private fun activate(lease: SocketLease<*>) {
        checkConnectAllowed(lease)
        lease.active = true
        if (lease.socket is Socket) openedTcp++ else openedUdp++
    }

    private fun release(lease: SocketLease<*>) = synchronized(lock) {
        if (sockets.remove(lease.socket) != null) closeSocket(lease, TransportCancellation.FLOW)
    }

    private fun closeAll(reason: TransportCancellation) {
        // SO_LINGER не включён. Закрытие дескриптора отменяет блокирующий ввод-вывод рабочих потоков.
        sockets.values.forEach { closeSocket(it, reason) }
        sockets.clear()
    }

    private fun closeSocket(lease: SocketLease<*>, reason: TransportCancellation) {
        lease.cancellation = reason
        val socket = lease.socket
        val protocol = if (socket is Socket) TransportProtocol.TCP else TransportProtocol.UDP
        closures.close(socket) { error ->
            failedOperations.incrementAndGet()
            failures.recordError(TransportSite(TransportSubsystem.SOCKET, TransportOperation.CLOSE, protocol), error,
                fallback = TransportCategory.CLEANUP)
        }
    }

    private inline fun <T> countFailures(
        site: TransportSite,
        cancellation: () -> TransportCancellation? = { null },
        fallback: TransportCategory = TransportCategory.UNKNOWN,
        peerEof: () -> Boolean = { false },
        operation: () -> T,
    ): T = try {
        operation()
    } catch (error: Exception) {
        failedOperations.incrementAndGet()
        failures.recordError(site, error, cancellation(), fallback, peerEof())
        throw error
    }

    internal class SocketLease<S : Closeable>(val socket: S) {
        var active = false // Под lock исходящего транспорта.
        @Volatile var cancellation: TransportCancellation? = null
    }

    companion object {
        const val MAX_UDP_PAYLOAD = 65_507
    }
}
