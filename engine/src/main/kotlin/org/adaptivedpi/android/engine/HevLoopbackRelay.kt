package org.adaptivedpi.android.engine

import org.adaptivedpi.android.engine.flow.FlowFailure
import org.adaptivedpi.android.engine.flow.FlowKey
import org.adaptivedpi.android.engine.flow.FlowKind
import org.adaptivedpi.android.engine.flow.FlowObserver
import org.adaptivedpi.android.engine.flow.FlowPipeline
import org.adaptivedpi.android.engine.flow.FlowTracker
import org.adaptivedpi.android.engine.flow.PrefixSource
import org.adaptivedpi.android.engine.flow.UdpRecord
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class RelayShutdownStatus(
    val pendingMask: Int,
    val requestsActive: Int,
    val requestsQueued: Int,
    val responsesActive: Int,
    val responsesQueued: Int,
) {
    val terminated get() = pendingMask == 0
}

/** Одноразовый локальный интерфейс HEV. UDP проходит здесь в кадрах поверх TCP. */
class HevLoopbackRelay internal constructor(
    private val transport: OutboundTransport,
    private val destination: InetAddress?,
    maxSessions: Int,
    private val failures: TransportFailureReporter,
    startAccepting: (ServerSocket, Thread) -> Unit,
    tcpIdleTimeoutMillis: Long = 60_000,
    flows: FlowObserver = FlowObserver.None,
    flowCaptureMillis: Long = FlowPipeline.CAPTURE_MILLIS,
    flowClassify: ((ByteArray, Int) -> ClientHelloInspector.Result)? = null,
    private val onListenerFailure: () -> Unit,
) : Closeable {
    constructor(
        transport: OutboundTransport,
        destination: InetAddress?,
        maxSessions: Int = 16,
        failures: TransportFailureReporter = TransportFailureReporter(),
        flows: FlowObserver = FlowObserver.None,
        onListenerFailure: () -> Unit,
    ) : this(
        transport, destination, maxSessions, failures, { _, thread -> thread.start() }, flows = flows,
        onListenerFailure = onListenerFailure,
    )

    init { require(maxSessions > 0 && tcpIdleTimeoutMillis > 0) }

    private val lock = Any()
    private val relayId = relayIds.incrementAndGet()
    private val idleThreadName = "HEV TCP idle #$relayId"
    private val requestThreadName = "HEV request #$relayId"
    private val responseThreadName = "HEV response #$relayId"
    private val tcpIdleNanos = TimeUnit.MILLISECONDS.toNanos(tcpIdleTimeoutMillis)
    private val idleTimers = ScheduledThreadPoolExecutor(1) { task -> Thread(task, idleThreadName) }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val permits = Semaphore(maxSessions)
    // Неаутентифицированных подключений не больше preAuthLimit: молчащие клиенты не занимают слоты,
    // а при переполнении закрывается самое старое, поэтому новый клиент не ждёт их тайм-аута.
    // Лимит равен maxSessions: пачка из maxSessions честных клиентов (HEV открывает их одновременно)
    // не вытесняет друг друга, а молчащие клиенты как самые старые вытесняются первыми.
    private val preAuthLimit = maxSessions
    private val unauthenticated = ArrayDeque<Peer>()
    private val clients = mutableSetOf<Peer>()
    private val openClients = AtomicInteger()
    // Слот соединения резервирует место в обоих пулах и выдаётся после аутентификации. Пул запросов
    // дополнительно вмещает неаутентифицированные подключения. Очереди покрывают возврат потока
    // из предыдущей задачи в пул, а подключений в работе остаётся не больше maxSessions.
    private val requests = RelayPool(maxSessions + preAuthLimit, requestThreadName)
    private val responses = RelayPool(maxSessions, responseThreadName)
    private val secret = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val closures = SocketClosures()
    // Захват начала потока только считает: байты клиента уходят серверу без изменений. Ошибка конвейера не рвёт соединение.
    private val pipeline = FlowPipeline(
        flows, { error -> failures.recordError(FLOW, error, fallback = TransportCategory.INTERNAL) },
        captureNanos = TimeUnit.MILLISECONDS.toNanos(flowCaptureMillis),
        classify = flowClassify ?: { bytes, length -> ClientHelloInspector.inspect(bytes, length) },
    )
    private val closeCompleted = CountDownLatch(1)
    @Volatile private var closing = false
    @Volatile private var drainWait: Pair<Thread, String>? = null
    private val acceptThread = Thread(::accept, "HEV accept #$relayId")
    private val listener = ServerSocket(0, maxSessions, loopback)

    val port: Int = listener.localPort
    val configSecret: String get() = secret
    val isTerminated: Boolean get() = pendingShutdownSteps() == 0

    init {
        try {
            startAccepting(listener, acceptThread)
        } catch (failure: Throwable) {
            closing = true
            try {
                listener.close()
            } catch (closeError: Exception) {
                failure.addSuppressed(closeError)
                failures.recordError(CLOSE, closeError, fallback = TransportCategory.CLEANUP)
            }
            throw failure
        }
    }

    private fun accept() {
        try {
            while (!closing) {
                // Приём не зависит от слотов: слот нужен только после аутентификации.
                val peer = Peer(listener.accept())
                openClients.incrementAndGet()
                var scheduled = false
                var evicted: Peer? = null
                synchronized(lock) {
                    if (!closing) {
                        clients.add(peer)
                        unauthenticated.addLast(peer)
                        if (unauthenticated.size > preAuthLimit) evicted = unauthenticated.removeFirst()
                        try {
                            requests.execute { handle(peer) }
                            scheduled = true
                        } catch (_: RejectedExecutionException) {
                            reportRejected(ACCEPT)
                            clients.remove(peer)
                            unauthenticated.remove(peer)
                        }
                    }
                }
                evicted?.let(::closePeer)
                if (!scheduled) closePeer(peer)
            }
        } catch (error: Exception) {
            failures.recordError(ACCEPT, error, if (closing) TransportCancellation.SESSION else null)
            if (!closing) onListenerFailure()
        }
    }

    private fun handle(peer: Peer) {
        val socket = peer.socket
        try {
            if (peer.cancellation.get() != null) return
            val (input, output) = observe(AUTH, peer) {
                socket.soTimeout = 5_000
                DataInputStream(socket.getInputStream()) to DataOutputStream(socket.getOutputStream())
            }
            observe(AUTH, peer) {
                val handshake = DataInputStream(RelayHandshakeInput(input, socket::setSoTimeout, peer.acceptedAt))
                authenticate(handshake, output)
                socket.soTimeout = 5_000
            }
            synchronized(lock) { unauthenticated.remove(peer) }
            // Слот выдаётся только аутентифицированному клиенту. Ожидание ограничено временем,
            // после которого HEV уже прекратил ждать ответ на CONNECT.
            if (!permits.tryAcquire(SLOT_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                failures.record(TransportFailure(ACCEPT, TransportCategory.LIMIT, TransportCode.SESSION_LIMIT))
                pipeline.internalRefusal(TransportCode.SESSION_LIMIT)
                return
            }
            peer.holdsPermit = true
            val (command, endpoint) = observe(CONTROL_READ, peer) {
                requireProtocol(input.readUnsignedByte() == 5)
                val command = input.readUnsignedByte()
                requireProtocol(input.readUnsignedByte() == 0)
                val endpoint = readEndpoint(input)
                requireProtocol(command == 1 || command == 5)
                socket.soTimeout = if (command == 1) 0 else 60_000
                command to endpoint
            }
            when (command) {
                1 -> tcp(peer, input, output, endpoint)
                5 -> udp(peer, input, output)
            }
        } catch (_: Exception) {
            // Отказ регистрирует владелец операции. Ошибки внешних сокетов уже учтены в OutboundTransport.
        } finally {
            closePeer(peer)
            synchronized(lock) {
                clients.remove(peer)
                unauthenticated.remove(peer)
            }
            if (peer.holdsPermit) permits.release()
        }
    }

    private fun authenticate(input: DataInputStream, output: DataOutputStream) {
        requireProtocol(input.readUnsignedByte() == 5)
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        if (2.toByte() !in methods) {
            output.write(byteArrayOf(5, -1))
            throw ProtocolException("Для локального релея нужна аутентификация.")
        }
        output.write(byteArrayOf(5, 2))
        requireProtocol(input.readUnsignedByte() == 1)
        val user = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val password = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val accepted = MessageDigest.isEqual(USER, user) &&
            MessageDigest.isEqual(secret.toByteArray(Charsets.US_ASCII), password)
        output.write(byteArrayOf(1, if (accepted) 0 else 1))
        requireProtocol(accepted)
    }

    private fun tcp(peer: Peer, input: DataInputStream, output: DataOutputStream, endpoint: Endpoint) {
        observe(TCP_READ, peer) { checkDestination(endpoint) }
        val key = FlowKey(endpoint.address, endpoint.port)
        val connectStarted = System.nanoTime()
        val connection = try {
            transport.connectTcp(endpoint.address, endpoint.port)
        } catch (error: IOException) {
            // Исход подключения учитывается отдельно: сам отказ уже записан транспортом.
            pipeline.connectFailed(key, System.nanoTime() - connectStarted, flowFailure(failures.classify(CONNECT, error)))
            observe(CONTROL_WRITE, peer) { reply(output, 5) }
            throw error
        }
        val connectNanos = System.nanoTime() - connectStarted
        connection.use {
            connection.useRelayIdleTimeout()
            val idle = TcpIdle {
                try {
                    failures.record(TransportFailure(TCP_IDLE, TransportCategory.TIMEOUT, TransportCode.TIMEOUT))
                    peer.flow?.failed(FlowFailure(TransportCategory.TIMEOUT, TransportCode.TIMEOUT, null))
                } finally {
                    connection.close()
                    closePeer(peer)
                }
            }
            val flow = pipeline.begin(key, connectNanos)
            peer.flow = flow
            var response: Future<*>? = null
            try {
                observe(CONTROL_WRITE, peer) { reply(output, 0) }
                idle.start()
                response = submit(TCP_SCHEDULE) {
                    try {
                        val buffer = ByteArray(TCP_BUFFER)
                        while (true) {
                            val size = connection.read(buffer)
                            if (size < 0) break
                            flow.receivedFromServer(size)
                            observe(TCP_WRITE, peer) { output.write(buffer, 0, size) }
                            idle.forwarded()
                        }
                        observe(TCP_HALF_CLOSE, peer, fallback = TransportCategory.CLEANUP) { peer.socket.shutdownOutput() }
                    } catch (error: Exception) {
                        closePeer(peer)
                        throw error
                    }
                }
                val buffer = ByteArray(TCP_BUFFER)
                var first = true
                while (true) {
                    val size = readClient(peer, input, buffer, flow)
                    if (size < 0) {
                        peer.eof = true
                        break
                    }
                    if (first) {
                        first = false
                        val captured = observe(TCP_READ, peer) {
                            val source = PrefixSource { target, offset, length, timeoutMillis ->
                                peer.socket.soTimeout = timeoutMillis
                                input.read(target, offset, length)
                            }
                            pipeline.capture(flow, buffer, size, source).also {
                                if (flow.kind == FlowKind.TLS) peer.socket.soTimeout = 0
                            }
                        }
                        connection.write(captured.bytes, 0, captured.size)
                        flow.sentToServer(captured.size)
                        flow.firstPortionSent()
                        idle.forwarded()
                        if (captured.eof) {
                            peer.eof = true
                            flow.clientClosed()
                            break
                        }
                    } else {
                        connection.write(buffer, 0, size)
                        flow.sentToServer(size)
                        idle.forwarded()
                    }
                }
                connection.shutdownOutput()
                response.get()
            } finally {
                idle.close()
                if (response != null) {
                    finishDirections(peer, connection, response)
                }
                flow.finish()
            }
        }
    }

    /** Чтение от клиента. Закрытие клиентом отмечается в исходе, закрытие самим релеем - нет. */
    private fun readClient(peer: Peer, input: DataInputStream, buffer: ByteArray, flow: FlowTracker): Int {
        val size = try {
            observe(TCP_READ, peer) { input.read(buffer) }
        } catch (error: Exception) {
            if (peer.cancellation.get() == null) flow.clientClosed()
            throw error
        }
        if (size < 0) flow.clientClosed()
        return size
    }

    private fun flowFailure(failure: TransportFailure) = FlowFailure(failure.category, failure.code, failure.errno)

    private inner class TcpIdle(private val onTimeout: () -> Unit) : Closeable {
        private var lastForwarded = 0L
        private var nextCheck: ScheduledFuture<*>? = null
        private var finished = false

        @Synchronized
        fun start() {
            lastForwarded = System.nanoTime()
            schedule(tcpIdleNanos)
        }

        @Synchronized
        fun forwarded() {
            lastForwarded = System.nanoTime()
        }

        private fun schedule(delayNanos: Long) = synchronized(lock) {
            if (!closing) nextCheck = idleTimers.schedule(::checkDeadline, delayNanos, TimeUnit.NANOSECONDS)
        }

        private fun checkDeadline() {
            synchronized(this) {
                if (finished || closing) return
                val remaining = tcpIdleNanos - (System.nanoTime() - lastForwarded)
                if (remaining > 0) {
                    schedule(remaining)
                    return
                }
                finished = true
            }
            onTimeout()
        }

        @Synchronized
        override fun close() {
            finished = true
            nextCheck?.cancel(false)
        }
    }

    private fun udp(peer: Peer, input: DataInputStream, output: DataOutputStream) {
        observe(CONTROL_WRITE, peer) { reply(output, 0) }
        val payload = ByteArray(MAX_DATAGRAM)
        val first = observe(UDP_READ, peer) {
            readDatagram(peer, input, payload)
        } ?: return
        transport.connectUdp(first.endpoint.address, first.endpoint.port).use { connection ->
            val received = AtomicLong()
            var sent = 0L
            val response = submit(UDP_SCHEDULE) {
                try {
                    val buffer = ByteArray(MAX_DATAGRAM + 1)
                    while (peer.cancellation.get() == null) {
                        val size = try {
                            connection.receive(buffer)
                        } catch (error: TransportSocketException) {
                            // Отбрасываем одну слишком большую датаграмму. Ассоциация сохраняется.
                            if (error.code == TransportCode.DATAGRAM_LIMIT) continue
                            throw error
                        }
                        received.incrementAndGet()
                        observe(UDP_WRITE, peer) {
                            output.writeShort(size)
                            output.writeByte(10)
                            output.writeByte(1)
                            output.write(first.endpoint.address.address)
                            output.writeShort(first.endpoint.port)
                            output.write(buffer, 0, size)
                        }
                    }
                } catch (error: Exception) {
                    closePeer(peer)
                    throw error
                }
            }
            try {
                connection.send(payload, length = first.length)
                sent++
                while (peer.cancellation.get() == null) {
                    val next = observe(UDP_READ, peer) {
                        readDatagram(peer, input, payload, first.endpoint)
                    } ?: break
                    connection.send(payload, length = next.length)
                    sent++
                }
            } finally {
                finishDirections(peer, connection, response)
                pipeline.udpEnded(UdpRecord(first.endpoint.port, sent, received.get()))
            }
        }
    }

    private fun readDatagram(peer: Peer, input: DataInputStream, payload: ByteArray, expected: Endpoint? = null): Frame? {
        while (peer.cancellation.get() == null) {
            val firstByte = input.read()
            if (firstByte == -1) return null
            try {
                val length = firstByte * 256 + input.readUnsignedByte()
                requireProtocol(length <= OutboundTransport.MAX_UDP_PAYLOAD)
                val endpoint = readEndpoint(input, input.readUnsignedByte())
                if (expected == null) checkDestination(endpoint) else requireProtocol(endpoint == expected)
                if (length <= payload.size) {
                    input.readFully(payload, 0, length)
                    return Frame(endpoint, length)
                }
                discardDatagram(peer.socket, input, payload, length)
                failures.record(TransportFailure(UDP_READ, TransportCategory.LIMIT, TransportCode.DATAGRAM_LIMIT))
            } catch (error: EOFException) {
                throw ProtocolException("Неполный UDP-кадр.").apply { initCause(error) }
            }
        }
        return null
    }

    private fun discardDatagram(socket: Socket, input: DataInputStream, buffer: ByteArray, length: Int) {
        val originalTimeout = socket.soTimeout
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var remaining = length
        while (remaining > 0) {
            val timeLeft = deadline - System.nanoTime()
            if (timeLeft <= 0) throw SocketTimeoutException("Не завершено чтение крупного UDP-кадра.")
            socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(timeLeft).coerceAtLeast(1).toInt()
            val count = input.read(buffer, 0, minOf(remaining, buffer.size))
            if (count < 0) throw EOFException()
            remaining -= count
        }
        socket.soTimeout = originalTimeout
    }

    private fun readEndpoint(input: DataInputStream, headerLength: Int? = null): Endpoint {
        val size = when (input.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            else -> throw ProtocolException("Локальный релей принимает числовые адреса.")
        }
        requireProtocol(headerLength == null || headerLength == size + 6)
        val address = InetAddress.getByAddress(ByteArray(size).also(input::readFully))
        return Endpoint(address, input.readUnsignedShort())
    }

    private fun checkDestination(endpoint: Endpoint) {
        val address = endpoint.address
        requireProtocol(endpoint.port != 0 && if (destination != null) address == destination else {
            address is Inet4Address && !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                !address.isMulticastAddress && (address.address[0].toInt() and 255) < 240
        })
    }

    private fun reply(output: DataOutputStream, status: Byte) {
        output.write(byteArrayOf(5, status, 0, 1, 0, 0, 0, 0, 0, 0))
    }

    private inline fun <T> observe(
        site: TransportSite,
        peer: Peer,
        fallback: TransportCategory = TransportCategory.UNKNOWN,
        operation: () -> T,
    ): T = try {
        operation()
    } catch (error: Exception) {
        val failure = failures.classify(site, error, peer.cancellation.get(), fallback, peer.eof)
        failures.record(failure)
        // Завершение записи после EOF партнёра - обычный конец соединения, а не отказ.
        if (failure.code != TransportCode.PEER_EOF) peer.flow?.failed(flowFailure(failure))
        throw error
    }

    private fun submit(site: TransportSite, operation: () -> Unit) = try {
        responses.submit(operation)
    } catch (error: RejectedExecutionException) {
        reportRejected(site)
        throw error
    }

    private fun reportRejected(site: TransportSite) {
        if (closing) {
            failures.record(TransportFailure(site, TransportCategory.CANCELLED, TransportCode.SESSION_CANCELLED))
        } else {
            failures.record(TransportFailure(site, TransportCategory.INTERNAL, TransportCode.WORKER_REJECTED))
            pipeline.internalRefusal(TransportCode.WORKER_REJECTED)
        }
    }

    private fun finishDirections(peer: Peer, connection: Closeable, response: Future<*>) {
        connection.close()
        closePeer(peer)
        // При закрытии потока ждём ответ, прежде чем вернуть его слот. При остановке
        // новых потоков уже не будет и завершение обоих пулов проверяется отдельно.
        if (closing) return
        try {
            response.get()
        } catch (_: ExecutionException) {
            // Ошибку уже записал поток, выполнивший ввод-вывод.
        }
    }

    private fun closePeer(peer: Peer) {
        val reason = if (closing) TransportCancellation.SESSION else TransportCancellation.FLOW
        if (peer.cancellation.compareAndSet(null, reason)) {
            try {
                closeRelay(peer.socket)
            } finally {
                if (peer.socket.isClosed) openClients.decrementAndGet()
            }
        }
    }

    private fun closeRelay(socket: Closeable) {
        closures.close(socket) {
            failures.recordError(CLOSE, it, fallback = TransportCategory.CLEANUP)
        }
    }

    override fun close() {
        val peers = synchronized(lock) {
            if (closing) return
            closing = true
            idleTimers.shutdown()
            clients.toList()
        }
        try {
            closeRelay(listener)
            acceptThread.interrupt()
            transport.close()
        } finally {
            peers.forEach(::closePeer)
            requests.stopWorkers()
            responses.stopWorkers()
            closeCompleted.countDown()
        }
    }

    /** Вызывается рабочим потоком после закрытия и до освобождения сеанса. */
    fun awaitShutdown(timeoutMillis: Long = 4_000): Boolean = awaitShutdownResult(timeoutMillis).terminated

    fun awaitShutdownResult(timeoutMillis: Long = 4_000): RelayShutdownStatus {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        fun remaining() = (deadline - System.nanoTime()).coerceAtLeast(0)
        // Повторный close() возвращается, пока первый вызов ещё закрывает ресурсы.
        closeCompleted.await(remaining(), TimeUnit.NANOSECONDS)
        if (acceptThread.isAlive) remaining().takeIf { it > 0 }?.let {
            TimeUnit.NANOSECONDS.timedJoin(acceptThread, it)
        }
        requests.awaitTermination(remaining(), TimeUnit.NANOSECONDS)
        responses.awaitTermination(remaining(), TimeUnit.NANOSECONDS)
        idleTimers.awaitTermination(remaining(), TimeUnit.NANOSECONDS)
        return shutdownStatus()
    }

    fun shutdownStatus() = RelayShutdownStatus(
        pendingShutdownSteps(), requests.activeCount, requests.queue.size, responses.activeCount, responses.queue.size,
    )

    fun socketClosures(): SocketCloseSummary = closures.snapshot() + transport.socketClosures()

    /** Повтор закрытия сокетов релея и исходящего транспорта, освобождение которых не подтвердилось. */
    fun retryUnconfirmedClosures() {
        closures.retryUnconfirmed { failures.recordError(CLOSE, it, fallback = TransportCategory.CLEANUP) }
        transport.retryUnconfirmedClosures()
    }

    /** Владелец ждёт фактического освобождения на фоновом потоке. */
    fun awaitDrained() {
        val owner = Thread.currentThread()
        try {
            drainWait = owner to "closeCompleted.await"
            closeCompleted.await()
            drainWait = owner to "acceptThread.join"
            acceptThread.join()
            drainWait = owner to "requests.awaitTermination"
            requests.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
            drainWait = owner to "responses.awaitTermination"
            responses.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
            drainWait = owner to "idleTimers.awaitTermination"
            idleTimers.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        } finally {
            drainWait = null
        }
    }

    /** Диагностический снимок без блокировки клиентов или ожидания сетевого ввода-вывода. */
    fun shutdownDiagnosticLines(includeStacks: Boolean, hevThread: Thread? = null): List<String> = buildList {
        val wait = drainWait
        val status = shutdownStatus()
        add("relay=$relayId wait=${wait?.second ?: "none"} waitThreadId=${wait?.first?.id ?: "none"} " +
            "pendingMask=${status.pendingMask} requestsActive=${status.requestsActive} requestsQueued=${status.requestsQueued} " +
            "responsesActive=${status.responsesActive} responsesQueued=${status.responsesQueued} " +
            "acceptThread=${acceptThread.state} requests=${requests.shutdownState()} responses=${responses.shutdownState()} " +
            "idleTimers=${idleTimers.shutdownState()} idleActive=${idleTimers.activeCount} idleQueued=${idleTimers.queue.size} " +
            "openClients=${openClients.get()}")
        if (includeStacks) {
            val names = setOf(requestThreadName, responseThreadName, idleThreadName)
            Thread.getAllStackTraces().entries.asSequence()
                .filter { (thread, _) ->
                    thread === acceptThread || thread === wait?.first || thread === hevThread || thread.name in names
                }
                .sortedBy { it.key.id }
                .forEach { (thread, stack) ->
                    val name = if (thread === wait?.first) "RelayWait (${thread.name})" else thread.name
                    // Только код и номера строк, без Throwable, аргументов или имён сокетов.
                    val frames = stack.take(20).joinToString(" <- ") {
                        "${it.className}.${it.methodName}:${it.lineNumber}".take(140)
                    }
                    add("thread=$name id=${thread.id} state=${thread.state} daemon=${thread.isDaemon} " +
                        "interrupted=${thread.isInterrupted} stack=$frames")
                }
        }
    }

    private fun ThreadPoolExecutor.shutdownState() = when {
        isTerminated -> "TERMINATED"
        isShutdown -> "SHUTDOWN"
        else -> "RUNNING"
    }

    private fun pendingShutdownSteps(): Int {
        var pending = 0
        if (acceptThread.isAlive) pending = pending or 1
        if (!requests.isTerminated) pending = pending or 2
        if (!responses.isTerminated) pending = pending or 4
        if (closeCompleted.count != 0L) pending = pending or 8
        if (!idleTimers.isTerminated) pending = pending or 16
        return pending
    }

    private class Peer(val socket: Socket) {
        val acceptedAt = System.nanoTime()
        val cancellation = AtomicReference<TransportCancellation?>()
        @Volatile var eof = false
        @Volatile var holdsPermit = false
        @Volatile var flow: FlowTracker? = null
    }

    private class RelayPool(size: Int, name: String) : ThreadPoolExecutor(
        size, size, 15, TimeUnit.SECONDS, ArrayBlockingQueue(size), { task -> Thread(task, name) },
    ) {
        private val active = ConcurrentHashMap.newKeySet<Thread>()

        init { allowCoreThreadTimeOut(true) }

        override fun beforeExecute(thread: Thread, task: Runnable) {
            super.beforeExecute(thread, task)
            active.add(thread)
        }

        override fun afterExecute(task: Runnable, error: Throwable?) {
            active.remove(Thread.currentThread())
            super.afterExecute(task, error)
        }

        fun stopWorkers() {
            shutdown()
            // Закрытые сокеты отменяют ввод-вывод. Interrupt также будит ожидание
            // Future в запросе, которое могло начаться до команды остановки.
            active.forEach(Thread::interrupt)
        }
    }

    private data class Endpoint(val address: InetAddress, val port: Int)
    private data class Frame(val endpoint: Endpoint, val length: Int)

    private companion object {
        val relayIds = AtomicInteger()
        val USER = "adaptive".toByteArray(Charsets.US_ASCII)
        const val TCP_BUFFER = 8 * 1_024
        const val SLOT_WAIT_MILLIS = 5_000L
        // UDP_BUF_SIZE закреплённой версии HEV. lwIP может фрагментировать IPv4 при MTU 1280.
        const val MAX_DATAGRAM = 1_500
        val ACCEPT = TransportSite(TransportSubsystem.HEV, TransportOperation.ACCEPT)
        val AUTH = TransportSite(TransportSubsystem.HEV, TransportOperation.AUTHENTICATE)
        val CLOSE = TransportSite(TransportSubsystem.HEV, TransportOperation.CLOSE)
        val FLOW = TransportSite(TransportSubsystem.HEV, TransportOperation.RUN, TransportProtocol.TCP)
        val CONNECT = TransportSite(TransportSubsystem.TCP, TransportOperation.CONNECT, TransportProtocol.TCP, TransportDirection.OUTBOUND)
        val CONTROL_READ = TransportSite(TransportSubsystem.HEV, TransportOperation.READ)
        val CONTROL_WRITE = TransportSite(TransportSubsystem.HEV, TransportOperation.WRITE)
        val TCP_READ = TransportSite(TransportSubsystem.HEV, TransportOperation.READ, TransportProtocol.TCP, TransportDirection.OUTBOUND)
        val TCP_IDLE = TransportSite(TransportSubsystem.HEV, TransportOperation.READ, TransportProtocol.TCP)
        val TCP_WRITE = TransportSite(TransportSubsystem.HEV, TransportOperation.WRITE, TransportProtocol.TCP, TransportDirection.INBOUND)
        val TCP_HALF_CLOSE = TransportSite(TransportSubsystem.HEV, TransportOperation.SHUTDOWN_OUTPUT, TransportProtocol.TCP, TransportDirection.INBOUND)
        val TCP_SCHEDULE = TransportSite(TransportSubsystem.HEV, TransportOperation.SCHEDULE, TransportProtocol.TCP)
        val UDP_SCHEDULE = TransportSite(TransportSubsystem.HEV, TransportOperation.SCHEDULE, TransportProtocol.UDP)
        val UDP_READ = TransportSite(TransportSubsystem.HEV, TransportOperation.READ, TransportProtocol.UDP, TransportDirection.OUTBOUND)
        val UDP_WRITE = TransportSite(TransportSubsystem.HEV, TransportOperation.WRITE, TransportProtocol.UDP, TransportDirection.INBOUND)

        fun requireProtocol(condition: Boolean) {
            if (!condition) throw ProtocolException("Недопустимые данные локального релея.")
        }
    }
}
