package org.adaptivedpi.android.engine

import org.adaptivedpi.android.engine.flow.FlowObserver
import org.adaptivedpi.android.engine.flow.UdpEnd
import org.adaptivedpi.android.engine.flow.UdpRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext

class HevLoopbackRelayTest {
    private val ip = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val policy = object : OutboundSocketPolicy {
        override fun prepare(socket: Socket) = Unit
        override fun prepare(socket: DatagramSocket) = Unit
    }

    @Test
    fun drainRetriesListenerCloseBeforeJoiningAcceptThread() {
        lateinit var listener: ServerSocket
        val listening = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var attempts = 0
        OutboundTransport().use { transport ->
            val relay = HevLoopbackRelay(
                transport, ip, 1, TransportFailureReporter(),
                { socket, thread -> listener = socket; thread.start() },
                closeRelaySocket = { socket ->
                    if (socket === listener && ++attempts <= 2) throw IOException("Временный отказ закрытия")
                    socket.close()
                },
            ) { error("Неожиданная остановка релея") }
            try {
                client(relay).use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    assertEquals(0, authenticate(relay.configSecret, input, output))
                    listening.countDown()
                }
                val stop = executor.submit {
                    assertTrue(listening.await(1, TimeUnit.SECONDS))
                    relay.close()
                    relay.awaitDrained()
                }
                stop.get(2, TimeUnit.SECONDS)
                assertTrue(relay.isTerminated)
                assertTrue(relay.socketClosures().confirmed)
                assertEquals(3, attempts)
            } finally {
                listener.close()
                executor.shutdownNow()
                relay.close()
            }
        }
    }

    @Test
    fun drainReportsPermanentListenerFailureWithoutWaitingForAnImpossibleJoin() {
        lateinit var listener: ServerSocket
        val executor = Executors.newSingleThreadExecutor()
        var attempts = 0
        OutboundTransport().use { transport ->
            val relay = HevLoopbackRelay(
                transport, ip, 1, TransportFailureReporter(),
                { socket, thread -> listener = socket; thread.start() },
                closeRelaySocket = { socket ->
                    if (socket === listener) {
                        attempts++
                        throw IOException("Постоянный отказ закрытия")
                    }
                    socket.close()
                },
            ) { error("Неожиданная остановка релея") }
            try {
                executor.submit { relay.close(); relay.awaitDrained() }.get(2, TimeUnit.SECONDS)
                assertEquals(3, attempts)
                assertFalse(relay.isTerminated)
                assertFalse(relay.socketClosures().confirmed)
                assertTrue(relay.shutdownStatus().pendingMask and 8 != 0)
                listener.close()
                relay.awaitDrained()
                assertTrue(relay.isTerminated)
                assertEquals(3, attempts)
            } finally {
                listener.close()
                relay.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun peerCountDropsAfterConfirmedCloseEvenWhenCloseThrowsAfterward() {
        for (throwAfterClose in listOf(false, true)) {
            val attempts = AtomicInteger()
            relay(closeSocket = { socket ->
                socket.close()
                if (socket is Socket) {
                    attempts.incrementAndGet()
                    if (throwAfterClose) throw IOException("Отказ после закрытия")
                }
            }) { relay, _ ->
                client(relay).use { client ->
                    assertEquals(0, authenticate(relay.configSecret, client))
                    assertEquals(1, relay.diagnosticOpenClients())
                    relay.close()
                    assertEquals(0, relay.diagnosticOpenClients())
                    relay.awaitDrained()
                    relay.close()
                    assertEquals(0, relay.diagnosticOpenClients())
                    assertEquals(1, attempts.get())
                }
            }
        }
    }

    @Test(timeout = 10_000)
    fun peerCountDropsOnceWhenRetryClosesSocketDuringConcurrentStop() {
        val attempts = AtomicInteger()
        val retryEntered = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val accepted = AtomicReference<Socket>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            relay(closeSocket = { socket ->
                if (socket is Socket) {
                    accepted.set(socket)
                    if (attempts.incrementAndGet() == 1) throw IOException("Отказ до закрытия")
                    retryEntered.countDown()
                    check(releaseRetry.await(3, TimeUnit.SECONDS))
                }
                socket.close()
            }) { relay, _ ->
                client(relay).use { client ->
                    try {
                        assertEquals(0, authenticate(relay.configSecret, client))
                        assertEquals(1, relay.diagnosticOpenClients())
                        val stop = executor.submit { relay.close(); relay.awaitDrained() }
                        assertTrue(retryEntered.await(3, TimeUnit.SECONDS))
                        relay.close()
                        assertFalse(accepted.get().isClosed)
                        assertFalse(relay.socketClosures().confirmed)
                        assertEquals(1, relay.diagnosticOpenClients())
                        releaseRetry.countDown()
                        stop.get(3, TimeUnit.SECONDS)
                        assertTrue(accepted.get().isClosed)
                        assertEquals(-1, client.getInputStream().read())
                        assertTrue(relay.isTerminated)
                        assertEquals(0, relay.diagnosticOpenClients())
                        relay.close()
                        relay.awaitDrained()
                        assertEquals(0, relay.diagnosticOpenClients())
                        assertEquals(2, attempts.get())
                    } finally {
                        releaseRetry.countDown()
                        accepted.get()?.close()
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test(timeout = 10_000)
    fun peerCountKeepsExhaustedSocketUntilClosureIsConfirmed() {
        val accepted = AtomicReference<Socket>()
        val attempts = AtomicInteger()
        relay(closeSocket = { socket ->
            if (socket is Socket) {
                accepted.set(socket)
                attempts.incrementAndGet()
                return@relay
            }
            socket.close()
        }) { relay, _ ->
            client(relay).use { client ->
                try {
                    assertEquals(0, authenticate(relay.configSecret, client))
                    relay.close()
                    relay.awaitDrained()
                    val peer = accepted.get()
                    assertEquals(3, attempts.get())
                    assertFalse(peer.isClosed)
                    assertFalse(relay.isTerminated)
                    assertEquals(1, relay.diagnosticOpenClients())
                    peer.close()
                    relay.awaitDrained()
                    assertTrue(relay.isTerminated)
                    assertEquals(0, relay.diagnosticOpenClients())
                    assertEquals(3, attempts.get())
                } finally {
                    accepted.get()?.close()
                }
            }
        }
    }

    @Test
    fun udpKeepsForwardingOneWayTrafficBeyondReceiveTimeout() {
        DatagramSocket(InetSocketAddress(ip, 0)).use { server ->
            server.soTimeout = 1_000
            relay(readTimeoutMillis = 160) { relay, _ ->
                client(relay).use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    assertEquals(0, authenticate(relay.configSecret, input, output))
                    command(output, 5, 0)
                    assertEquals(0, reply(input))
                    repeat(8) { index ->
                        frame(output, server.localPort, byteArrayOf(index.toByte()))
                        val packet = DatagramPacket(ByteArray(1), 1)
                        server.receive(packet)
                        assertEquals(index, packet.data[0].toInt())
                        Thread.sleep(45)
                    }
                }
            }
        }
    }

    @Test
    fun incomingUdpKeepsAssociationThenStopRecordsBothDirectionsOnce() {
        val outcomes = ConcurrentLinkedQueue<UdpRecord>()
        val observer = object : FlowObserver by FlowObserver.None {
            override fun onUdp(association: UdpRecord) { outcomes.add(association) }
        }
        DatagramSocket(InetSocketAddress(ip, 0)).use { server ->
            server.soTimeout = 1_000
            relay(readTimeoutMillis = 120, udpIdleTimeoutMillis = 220, flows = observer) { relay, _ ->
                client(relay).use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    assertEquals(0, authenticate(relay.configSecret, input, output))
                    command(output, 5, 0)
                    assertEquals(0, reply(input))
                    frame(output, server.localPort, byteArrayOf(9))
                    val peer = DatagramPacket(ByteArray(1), 1)
                    server.receive(peer)
                    repeat(8) { index ->
                        Thread.sleep(45)
                        server.send(DatagramPacket(byteArrayOf(index.toByte()), 1, peer.socketAddress))
                        assertArrayEquals(byteArrayOf(index.toByte()), response(input))
                    }
                    relay.close()
                    relay.awaitDrained()
                    assertEquals(listOf(UdpRecord(server.localPort, 1, 8, UdpEnd.CANCELLED)), outcomes.toList())
                }
            }
        }
    }

    @Test
    fun failedStartOfAcceptThreadClosesTheListener() {
        val failure = IllegalThreadStateException("Тестовый отказ старта потока")
        var listener: ServerSocket? = null
        OutboundTransport().use { transport ->
            val thrown = assertThrows(IllegalThreadStateException::class.java) {
                HevLoopbackRelay(transport, ip, 1, TransportFailureReporter(), { socket, _ ->
                    listener = socket
                    throw failure
                }) { error("Обработчик не должен запуститься") }
            }
            assertEquals(failure, thrown)
            assertTrue(checkNotNull(listener).isClosed)
        }
    }

    @Test
    fun tcpHandshakeTransfersPayloadAndClosesBothDirections() {
        val payload = byteArrayOf(23, -14, 92)
        ServerSocket(0, 1, ip).use { listener ->
            listener.soTimeout = 3_000
            relay { relay, _ ->
                client(relay).use { client ->
                    val input = DataInputStream(client.getInputStream())
                    val output = DataOutputStream(client.getOutputStream())
                    assertEquals(0, authenticate(relay.configSecret, input, output))
                    command(output, 1, listener.localPort)
                    assertEquals(0, reply(input))
                    listener.accept().use { peer ->
                        peer.soTimeout = 3_000
                        output.write(payload)
                        client.shutdownOutput()
                        assertArrayEquals(payload, DataInputStream(peer.getInputStream()).readExact(payload.size))
                        assertEquals(-1, peer.getInputStream().read())
                        peer.getOutputStream().write(payload)
                        peer.shutdownOutput()
                        assertArrayEquals(payload, input.readExact(payload.size))
                        assertEquals(-1, input.read())
                    }
                }
            }
        }
    }

    @Test(timeout = 85_000)
    fun activeDownloadSurvivesSixtySecondsOfSilenceInRequestDirection() {
        val failures = ConcurrentLinkedQueue<TransportFailure>()
        val sender = Executors.newSingleThreadScheduledExecutor()
        val marker = 42
        try {
            ServerSocket(0, 1, ip).use { listener ->
                listener.soTimeout = 3_000
                relay(failures = TransportFailureReporter(sink = failures::add)) { relay, transport ->
                    client(relay).use { client ->
                        val input = DataInputStream(client.getInputStream())
                        val output = DataOutputStream(client.getOutputStream())
                        assertEquals(0, authenticate(relay.configSecret, input, output))
                        command(output, 1, listener.localPort)
                        assertEquals(0, reply(input))
                        val connectedAt = System.nanoTime()
                        client.soTimeout = 5_000

                        listener.accept().use { peer ->
                            val stream = peer.getOutputStream()
                            // Сервер продолжает попытки отправки за 60 с. Обратное направление остаётся открытым и молчит.
                            val sending = sender.scheduleAtFixedRate({ stream.write(marker) }, 0, 1, TimeUnit.SECONDS)
                            try {
                                val deadline = connectedAt + TimeUnit.SECONDS.toNanos(65)
                                var received = 0
                                do {
                                    assertEquals("Загрузка прервалась после $received байт", marker, input.read())
                                    received++
                                } while (System.nanoTime() < deadline)
                                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectedAt)
                                assertTrue("До контрольного срока должны поступать данные, получено $received", received >= 60)
                                assertFalse(failures.any { it.code == TransportCode.TIMEOUT })
                                assertEquals(0L, transport.statistics().sentBytes)
                                assertEquals(1, transport.statistics().activeTcp)
                                println("TCP: загрузка активна через ${elapsed} мс, получено $received байт")
                            } finally {
                                sending.cancel(false)
                            }
                        }
                    }
                }
            }
        } finally {
            sender.shutdownNow()
            assertTrue(sender.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 12_000)
    fun activeUploadOutlivesBothReadTimeoutsThenIdleClosesBothSockets() {
        val failures = ConcurrentLinkedQueue<TransportFailure>()
        val sender = Executors.newSingleThreadScheduledExecutor()
        val idleTimeoutMillis = 1_000L
        val marker = 43
        try {
            ServerSocket(0, 1, ip).use { listener ->
                listener.soTimeout = 3_000
                relay(
                    failures = TransportFailureReporter(sink = failures::add),
                    tcpIdleTimeoutMillis = idleTimeoutMillis,
                    readTimeoutMillis = 500,
                ) { relay, transport ->
                    client(relay).use { client ->
                        val input = DataInputStream(client.getInputStream())
                        val output = DataOutputStream(client.getOutputStream())
                        assertEquals(0, authenticate(relay.configSecret, input, output))
                        command(output, 1, listener.localPort)
                        assertEquals(0, reply(input))
                        listener.accept().use { peer ->
                            peer.soTimeout = 3_000
                            val peerInput = peer.getInputStream()
                            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500)
                            val lastSendStarted = AtomicLong()
                            val sending = sender.scheduleAtFixedRate({
                                lastSendStarted.set(System.nanoTime())
                                output.write(marker)
                            }, 0, 100, TimeUnit.MILLISECONDS)
                            try {
                                do {
                                    assertEquals(marker, peerInput.read())
                                } while (System.nanoTime() < until)
                                assertEquals(1, transport.statistics().activeTcp)
                                assertFalse(failures.any { it.code == TransportCode.TIMEOUT })
                            } finally {
                                sending.cancel(false)
                            }
                            // Барьер исполнителя гарантирует, что поток отправки больше не пишет.
                            sender.submit {}.get(2, TimeUnit.SECONDS)
                            while (true) {
                                val value = peerInput.read()
                                if (value == -1) break
                                assertEquals(marker, value)
                            }
                            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastSendStarted.get())
                            assertTrue("Соединение закрылось через $elapsed мс после начала последней отправки", elapsed >= idleTimeoutMillis)
                            assertEquals(-1, input.read())
                            val timeout = failures.single { it.code == TransportCode.TIMEOUT }
                            assertEquals(TransportCategory.TIMEOUT, timeout.category)
                            assertEquals(TransportSite(TransportSubsystem.HEV, TransportOperation.READ, TransportProtocol.TCP), timeout.site)
                            assertEquals(0, transport.statistics().activeTcp)
                            assertEquals(SocketCloseSummary(closed = 1), transport.socketClosures())
                        }
                    }
                }
            }
        } finally {
            sender.shutdownNow()
            assertTrue(sender.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun hevUdpFramesGoThroughRealDatagramSocketWithoutChangingContents() {
        val payload = byteArrayOf(-1, 43, 9)
        DatagramSocket(InetSocketAddress(ip, 0)).use { peer ->
            peer.soTimeout = 3_000
            relay { relay, _ ->
                client(relay).use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    assertEquals(0, authenticate(relay.configSecret, input, output))
                    command(output, 5, 0)
                    assertEquals(0, reply(input))
                    output.writeShort(payload.size)
                    output.writeByte(10)
                    endpoint(output, peer.localPort)
                    output.write(payload)

                    val datagram = DatagramPacket(ByteArray(16), 16)
                    peer.receive(datagram)
                    assertArrayEquals(payload, datagram.data.copyOf(datagram.length))
                    peer.send(DatagramPacket(payload, payload.size, datagram.socketAddress))
                    assertEquals(payload.size, input.readUnsignedShort())
                    assertEquals(10, input.readUnsignedByte())
                    assertEquals(1, input.readUnsignedByte())
                    assertArrayEquals(ip.address, input.readExact(4))
                    assertEquals(peer.localPort, input.readUnsignedShort())
                    assertArrayEquals(payload, input.readExact(payload.size))
                }
            }
        }
    }

    @Test
    fun wrongCredentialAndOtherDestinationNeverOpenOutboundSockets() {
        relay { relay, transport ->
            client(relay).use { client ->
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())
                assertEquals(1, authenticate("wrong", input, output))
                assertEquals(-1, input.read())
            }
            client(relay).use { client ->
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())
                assertEquals(0, authenticate(relay.configSecret, input, output))
                output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 2, 0, 80))
                assertEquals(-1, input.read())
            }
            assertEquals(0, transport.statistics().activeTcp)
            assertEquals(0L, transport.statistics().failedOperations)
        }
    }

    @Test
    fun oversizedFrameIsRejectedBeforeOpeningADatagramSocket() {
        relay { relay, transport ->
            client(relay).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                assertEquals(0, authenticate(relay.configSecret, input, output))
                command(output, 5, 0)
                assertEquals(0, reply(input))
                output.writeShort(65_535)
                output.writeByte(10)
                assertEquals(-1, input.read())
            }
            assertEquals(0, transport.statistics().activeUdp)
            assertEquals(0L, transport.statistics().sentDatagrams)
        }
    }

    @Test
    fun applicationRelayRejectsLoopbackMulticastAndIpv6BeforeOpeningSockets() {
        relay(destination = null) { relay, transport ->
            for (address in listOf("127.0.0.1", "224.0.0.1", "255.255.255.255", "2001:db8::2")) {
                client(relay).use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    assertEquals(0, authenticate(relay.configSecret, input, output))
                    val bytes = InetAddress.getByName(address).address
                    output.write(byteArrayOf(5, 1, 0))
                    output.writeByte(if (bytes.size == 4) 1 else 4)
                    output.write(bytes)
                    output.writeShort(80)
                    assertEquals(-1, input.read())
                }
            }
            assertEquals(0L, transport.statistics().openedTcp)
            assertEquals(0L, transport.statistics().failedOperations)
        }
    }

    @Test(timeout = 10_000)
    fun saturatedRelayAuthenticatesQueuedConnectionAndWaitsForSlotBeforeCommand() {
        val failures = ConcurrentLinkedQueue<TransportFailure>()
        ServerSocket(0, 2, ip).use { target ->
            relay(maxSessions = 1, failures = TransportFailureReporter(sink = failures::add)) { relay, _ ->
                client(relay).use { first ->
                    val firstInput = DataInputStream(first.getInputStream())
                    val firstOutput = DataOutputStream(first.getOutputStream())
                    // Первый клиент после аутентификации держит единственный слот.
                    assertEquals(0, authenticate(relay.configSecret, firstInput, firstOutput))
                    client(relay).use { second ->
                        val input = DataInputStream(second.getInputStream())
                        val output = DataOutputStream(second.getOutputStream())
                        // Аутентификация не требует слота и проходит при занятом слоте.
                        assertEquals(0, authenticate(relay.configSecret, input, output))
                        command(output, 1, target.localPort)
                        second.soTimeout = 150
                        assertThrows(SocketTimeoutException::class.java) { reply(input) }
                        first.close()
                        second.soTimeout = 3_000
                        assertEquals(0, reply(input))
                    }
                }
                assertFalse(failures.any { it.code == TransportCode.WORKER_REJECTED || it.code == TransportCode.SESSION_LIMIT })
            }
        }
    }

    @Test(timeout = 10_000)
    fun silentClientsDoNotBlockAuthenticationOfLegitimateClient() {
        val registrations = PreAuthRegistrations()
        relay(maxSessions = 32, onPreAuthRegistered = registrations::record) { relay, _ ->
            val address = InetSocketAddress(ip, relay.port)
            RelayClients().use { clients ->
                val setupStarted = System.nanoTime()
                val silent = Array(32) { clients.connect(address) }
                registrations.await(*silent)
                val (pending, availableSlots) = relay.preAuthState()
                assertEquals(silent.map { it.localSocketAddress }.toSet(), pending)
                assertEquals(32, availableSlots)

                val started = System.nanoTime()
                val legit = clients.connect(address)
                legit.soTimeout = 1_000
                assertEquals(0, authenticate(relay.configSecret, legit))
                val authenticated = System.nanoTime()
                assertTrue(authenticated - started < TimeUnit.SECONDS.toNanos(1))
                assertBeforeHandshakeTimeout(setupStarted, authenticated)
            }
        }
    }

    @Test(timeout = 10_000)
    fun unauthenticatedOverflowClosesOldestConnectionAndKeepsNewerOnes() {
        // При maxSessions = 2 одновременно допускаются два неаутентифицированных подключения.
        val registrations = PreAuthRegistrations()
        lateinit var acceptThread: Thread
        relay(
            maxSessions = 2,
            startAccepting = { _, thread -> acceptThread = thread },
            onPreAuthRegistered = registrations::record,
        ) { relay, _ ->
            val address = InetSocketAddress(ip, relay.port)
            RelayClients().use { clients ->
                val setupStarted = System.nanoTime()
                val first = clients.connect(address)
                assertEquals(Thread.State.NEW, acceptThread.state)
                acceptThread.start()
                registrations.await(first)

                val second = clients.connect(address)
                registrations.await(second)
                assertEquals(setOf(first.localSocketAddress, second.localSocketAddress), relay.preAuthState().first)

                val third = clients.connect(address)
                registrations.await(third)
                assertEquals(-1, first.getInputStream().read())
                assertBeforeHandshakeTimeout(setupStarted, System.nanoTime())
                assertEquals(0, authenticate(relay.configSecret, second))
                assertEquals(0, authenticate(relay.configSecret, third))
            }
        }
    }

    @Test
    fun clientScopeClosesSocketsWhenAnyConnectionFails() {
        for (failureIndex in 0..2) {
            ServerSocket(0, 3, ip).use { listener ->
                val address = InetSocketAddress(ip, listener.localPort)
                val clients = mutableListOf<Socket>()
                val refusal = ConnectException("Управляемый отказ подключения")
                val owner = RelayClients {
                    val index = clients.size
                    object : Socket() {
                        override fun connect(endpoint: SocketAddress) {
                            if (index == failureIndex) throw refusal
                            super.connect(endpoint)
                        }
                    }.also(clients::add)
                }
                val error = assertThrows(ConnectException::class.java) {
                    owner.use { owned -> repeat(3) { owned.connect(address) } }
                }
                assertSame(refusal, error)
                assertEquals(failureIndex + 1, clients.size)
                assertEquals(failureIndex, clients.count(Socket::isConnected))
                assertTrue(clients.all(Socket::isClosed))
            }
        }
    }

    @Test
    fun clientScopeClosesConnectedSocketWhenConfigurationFails() {
        ServerSocket(0, 1, ip).use { listener ->
            val optionFailure = SocketException("Управляемый отказ настройки")
            val socket = object : Socket() {
                override fun setSoTimeout(timeout: Int) = throw optionFailure
            }
            val error = assertThrows(SocketException::class.java) {
                RelayClients { socket }.use { it.connect(InetSocketAddress(ip, listener.localPort)) }
            }
            assertSame(optionFailure, error)
            assertTrue(socket.isConnected)
            assertTrue(socket.isClosed)
        }
    }

    @Test
    fun clientScopeClosesSocketsWhenRegistrationOrAssertionFails() {
        ServerSocket(0, 2, ip).use { listener ->
            val address = InetSocketAddress(ip, listener.localPort)
            lateinit var waiting: Socket
            val missing = assertThrows(IllegalStateException::class.java) {
                RelayClients().use { clients ->
                    waiting = clients.connect(address)
                    PreAuthRegistrations().await(waiting, timeoutMillis = 0)
                }
            }
            assertTrue(missing.message.orEmpty().startsWith("Нет регистрации"))
            assertTrue(waiting.isClosed)

            lateinit var checked: Socket
            val assertion = assertThrows(AssertionError::class.java) {
                RelayClients().use { clients ->
                    checked = clients.connect(address)
                    assertTrue("Управляемое падение проверки", checked.isClosed)
                }
            }
            assertEquals("Управляемое падение проверки", assertion.message)
            assertTrue(checked.isClosed)
        }
    }

    @Test
    fun clientScopeAttemptsAllClosesAndPreservesOriginalFailure() {
        for (primary in listOf(null, AssertionError("Основное исключение"))) {
            ServerSocket(0, 3, ip).use { listener ->
                val address = InetSocketAddress(ip, listener.localPort)
                val failures = listOf(null, IOException("Второй сокет"), IOException("Третий сокет"))
                val sockets = failures.map { failure ->
                    object : Socket() {
                        override fun close() {
                            super.close()
                            if (failure != null) throw failure
                        }
                    }
                }
                val available = sockets.iterator()
                val error = assertThrows(Throwable::class.java) {
                    RelayClients(available::next).use { clients ->
                        repeat(3) { clients.connect(address) }
                        if (primary != null) throw primary
                    }
                }
                assertSame(primary ?: failures[2], error)
                val cleanup = if (primary != null) error.suppressed.single() else error
                assertSame(failures[2], cleanup)
                assertSame(failures[1], cleanup.suppressed.single())
                assertTrue(sockets.all(Socket::isClosed))
            }
        }
    }

    @Test
    fun mixedTcpAndUdpChurnKeepsBothDirectionsAndReleasesWorkersOnEveryStop() {
        val peers = Executors.newFixedThreadPool(2)
        val clients = Executors.newFixedThreadPool(6)
        val failures = ConcurrentLinkedQueue<TransportFailure>()
        try {
            repeat(3) {
                ServerSocket(0, 6, ip).use { tcp ->
                    DatagramSocket(InetSocketAddress(ip, 0)).use { udp ->
                        tcp.soTimeout = 8_000
                        udp.soTimeout = 8_000
                        val tcpServer = peers.submit {
                            repeat(24) {
                                tcp.accept().use { peer ->
                                    peer.soTimeout = 4_000
                                    val byte = peer.getInputStream().read()
                                    check(byte >= 0)
                                    peer.getOutputStream().write(byte)
                                }
                            }
                        }
                        val udpServer = peers.submit {
                            repeat(24) {
                                val datagram = DatagramPacket(ByteArray(16), 16)
                                udp.receive(datagram)
                                udp.send(datagram)
                            }
                        }
                        relay(maxSessions = 6, failures = TransportFailureReporter(sink = failures::add)) { relay, _ ->
                            val flows = (0 until 48).map { index ->
                                clients.submit {
                                    client(relay).use { socket ->
                                        val input = DataInputStream(socket.getInputStream())
                                        val output = DataOutputStream(socket.getOutputStream())
                                        assertEquals(0, authenticate(relay.configSecret, input, output))
                                        if (index % 2 == 0) {
                                            command(output, 1, tcp.localPort)
                                            assertEquals(0, reply(input))
                                            output.writeByte(index)
                                            socket.shutdownOutput()
                                            assertEquals(index, input.readUnsignedByte())
                                        } else {
                                            command(output, 5, 0)
                                            assertEquals(0, reply(input))
                                            frame(output, udp.localPort, byteArrayOf(index.toByte()))
                                            assertArrayEquals(byteArrayOf(index.toByte()), response(input))
                                        }
                                    }
                                }
                            }
                            flows.forEach { it.get(10, TimeUnit.SECONDS) }
                            tcpServer.get(3, TimeUnit.SECONDS)
                            udpServer.get(3, TimeUnit.SECONDS)
                            relay.close()
                            relay.close()
                        }
                    }
                }
            }
            assertFalse(failures.any { it.code == TransportCode.WORKER_REJECTED || it.category == TransportCategory.CLEANUP })
        } finally {
            clients.shutdownNow()
            peers.shutdownNow()
        }
    }

    @Test
    fun auditMixedLoopbackTrafficTransfersOneThousandTcpAndUdpAndFiveHundredDnsExchanges() {
        val serverThreads = Executors.newFixedThreadPool(3)
        val clients = Executors.newFixedThreadPool(8)
        val failures = ConcurrentLinkedQueue<TransportFailure>()
        val tcpCount = 1_000
        val udpCount = 1_000
        val dnsCount = 500
        val question = byteArrayOf(5, 97, 117, 100, 105, 116, 4, 116, 101, 115, 116, 0, 0, 1, 0, 1)
        fun query(id: Int) = ByteBuffer.allocate(12 + question.size).putShort(id.toShort())
            .putShort(0x0100).putShort(1).putShort(0).putShort(0).putShort(0).put(question).array()
        fun answer(query: ByteArray) = query.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
        } + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192.toByte(), 0, 2, 10)

        try {
            ServerSocket(0, 16, ip).use { tcp ->
                DatagramSocket(InetSocketAddress(ip, 0)).use { udp ->
                    DatagramSocket(InetSocketAddress(ip, 0)).use { dns ->
                        tcp.soTimeout = 8_000
                        val fixtures = listOf(
                            serverThreads.submit {
                                repeat(tcpCount) {
                                    tcp.accept().use { peer ->
                                        peer.soTimeout = 5_000
                                        val payload = DataInputStream(peer.getInputStream()).readExact(4)
                                        assertEquals(-1, peer.getInputStream().read())
                                        // Два вызова записи. Границы чтения TCP могут отличаться.
                                        peer.getOutputStream().write(payload, 0, 2)
                                        peer.getOutputStream().write(payload, 2, 2)
                                    }
                                }
                            },
                            serverThreads.submit { serveDatagrams(udp, udpCount) { it } },
                            serverThreads.submit {
                                serveDatagrams(dns, dnsCount) { payload ->
                                    val id = ByteBuffer.wrap(payload).short.toInt()
                                    assertArrayEquals(query(id), payload)
                                    answer(payload)
                                }
                            },
                        )
                        relay(maxSessions = 8, failures = TransportFailureReporter(sink = failures::add)) { relay, transport ->
                            repeat(100) { batch ->
                                // Ограничиваем очередь генератора и перемешиваем протоколы в каждом пакете.
                                val jobs = (0 until 25).map { index ->
                                    clients.submit {
                                        val kind = index % 5
                                        val id = batch * 25 + index
                                        client(relay).use { socket ->
                                            socket.soTimeout = 6_000
                                            val input = DataInputStream(socket.getInputStream())
                                            val output = DataOutputStream(socket.getOutputStream())
                                            assertEquals(0, authenticate(relay.configSecret, input, output))
                                            val data = ByteBuffer.allocate(4).putInt(id).array()
                                            if (kind < 2) {
                                                command(output, 1, tcp.localPort)
                                                assertEquals(0, reply(input))
                                                output.write(data, 0, 1)
                                                output.write(data, 1, 3)
                                                socket.shutdownOutput()
                                                assertArrayEquals(data, input.readExact(data.size))
                                                assertEquals(-1, input.read())
                                            } else {
                                                command(output, 5, 0)
                                                assertEquals(0, reply(input))
                                                if (kind == 4) {
                                                    val request = query(id)
                                                    frame(output, dns.localPort, request)
                                                    assertArrayEquals(answer(request), response(input))
                                                } else {
                                                    frame(output, udp.localPort, data)
                                                    assertArrayEquals(data, response(input))
                                                }
                                            }
                                        }
                                    }
                                }
                                jobs.forEach { it.get(20, TimeUnit.SECONDS) }
                            }
                            fixtures.forEach { it.get(3, TimeUnit.SECONDS) }
                            relay.close()
                            relay.close()
                            assertEquals(RelayShutdownStatus(0, 0, 0, 0, 0), relay.awaitShutdownResult())
                            val stats = transport.statistics()
                            assertEquals(tcpCount.toLong(), stats.closedTcp)
                            assertEquals((udpCount + dnsCount).toLong(), stats.closedUdp)
                            assertEquals((udpCount + dnsCount).toLong(), stats.receivedDatagrams)
                            assertEquals(0, stats.pending)
                        }
                    }
                }
            }
            assertFalse(failures.any { it.code == TransportCode.WORKER_REJECTED || it.category == TransportCategory.CLEANUP })
        } finally {
            clients.shutdownNow()
            serverThreads.shutdownNow()
        }
    }

    private fun serveDatagrams(socket: DatagramSocket, count: Int, respond: (ByteArray) -> ByteArray) {
        socket.soTimeout = 8_000
        repeat(count) {
            val packet = DatagramPacket(ByteArray(128), 128)
            socket.receive(packet)
            val response = respond(packet.data.copyOf(packet.length))
            socket.send(DatagramPacket(response, response.size, packet.socketAddress))
        }
    }

    @Test
    fun auditConcurrentStopsDrainEightUdpAssociationsWaitingForReplies() {
        val callers = Executors.newFixedThreadPool(2)
        val failures = ConcurrentLinkedQueue<TransportFailure>()
        try {
            DatagramSocket(InetSocketAddress(ip, 0)).use { server ->
                server.soTimeout = 3_000
                relay(maxSessions = 8, failures = TransportFailureReporter(sink = failures::add)) { relay, transport ->
                    val address = InetSocketAddress(ip, relay.port)
                    RelayClients().use { clients ->
                        repeat(8) { index ->
                            val socket = clients.connect(address)
                            val input = DataInputStream(socket.getInputStream())
                            val output = DataOutputStream(socket.getOutputStream())
                            assertEquals(0, authenticate(relay.configSecret, input, output))
                            command(output, 5, 0)
                            assertEquals(0, reply(input))
                            frame(output, server.localPort, byteArrayOf(index.toByte()))
                        }
                        val received = List(8) {
                            val packet = DatagramPacket(ByteArray(1), 1)
                            server.receive(packet)
                            packet.data.single().toInt()
                        }
                        assertEquals((0..7).toSet(), received.toSet())
                        assertEquals(8, transport.statistics().activeUdp)

                        val ready = CountDownLatch(2)
                        val start = CountDownLatch(1)
                        val stops = List(2) {
                            callers.submit {
                                ready.countDown()
                                check(start.await(3, TimeUnit.SECONDS))
                                relay.close()
                            }
                        }
                        assertTrue(ready.await(2, TimeUnit.SECONDS))
                        start.countDown()
                        stops.forEach { it.get(3, TimeUnit.SECONDS) }
                        assertEquals(RelayShutdownStatus(0, 0, 0, 0, 0), relay.awaitShutdownResult())
                        assertEquals(8L, transport.statistics().closedUdp)
                    }
                }
            }
            assertFalse(failures.any { it.category == TransportCategory.CLEANUP || it.code == TransportCode.WORKER_REJECTED })
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun oversizedUdpReplyIsDroppedAndFollowingLargeValidReplyKeepsAssociation() {
        val failures = ConcurrentLinkedQueue<TransportFailure>()
        DatagramSocket(InetSocketAddress(ip, 0)).use { server ->
            server.soTimeout = 3_000
            relay(failures = TransportFailureReporter(sink = failures::add)) { relay, _ ->
                client(relay).use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    assertEquals(0, authenticate(relay.configSecret, input, output))
                    command(output, 5, 0)
                    assertEquals(0, reply(input))
                    frame(output, server.localPort, byteArrayOf(1))
                    val packet = DatagramPacket(ByteArray(16), 16)
                    server.receive(packet)
                    val oversized = ByteArray(1_600)
                    server.send(DatagramPacket(oversized, oversized.size, packet.socketAddress))
                    val valid = ByteArray(1_400) { it.toByte() }
                    server.send(DatagramPacket(valid, valid.size, packet.socketAddress))
                    assertArrayEquals(valid, response(input))
                    assertEquals(1, failures.count { it.code == TransportCode.DATAGRAM_LIMIT })
                }
            }
        }
    }

    @Test(timeout = 15_000)
    fun oversizedOutboundUdpFrameIsDroppedAndSameAssociationStillExchangesData() {
        val failures = ConcurrentLinkedQueue<TransportFailure>()
        DatagramSocket(InetSocketAddress(ip, 0)).use { server ->
            server.soTimeout = 3_000
            relay(failures = TransportFailureReporter(sink = failures::add)) { relay, transport ->
                client(relay).use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    assertEquals(0, authenticate(relay.configSecret, input, output))
                    command(output, 5, 0)
                    assertEquals(0, reply(input))

                    val valid = ByteArray(1_500) { it.toByte() }
                    frame(output, server.localPort, valid)
                    val packet = DatagramPacket(ByteArray(1_501), 1_501)
                    server.receive(packet)
                    assertArrayEquals(valid, packet.data.copyOf(packet.length))
                    server.send(DatagramPacket(valid, valid.size, packet.socketAddress))
                    assertArrayEquals(valid, response(input))
                    assertEquals(1, transport.statistics().activeUdp)

                    val origin = packet.socketAddress
                    frame(output, server.localPort, ByteArray(1_501))
                    frame(output, server.localPort, byteArrayOf(17))
                    packet.length = packet.data.size
                    server.receive(packet)
                    assertEquals(origin, packet.socketAddress)
                    assertArrayEquals(byteArrayOf(17), packet.data.copyOf(packet.length))
                    server.send(DatagramPacket(byteArrayOf(18), 1, origin))
                    assertArrayEquals(byteArrayOf(18), response(input))
                    val rejected = failures.single { it.code == TransportCode.DATAGRAM_LIMIT }
                    assertEquals(TransportCategory.LIMIT, rejected.category)
                    assertEquals(
                        TransportSite(TransportSubsystem.HEV, TransportOperation.READ,
                            TransportProtocol.UDP, TransportDirection.OUTBOUND),
                        rejected.site,
                    )
                    assertFalse(failures.any { it.code == TransportCode.RELAY_PROTOCOL })
                    assertEquals(1, transport.statistics().activeUdp)
                    assertEquals(1L, transport.statistics().openedUdp)
                    assertEquals(2L, transport.statistics().sentDatagrams)
                }
            }
        }
    }

    @Test
    fun truncatedOversizedUdpFrameIsAProtocolError() {
        val failures = ConcurrentLinkedQueue<TransportFailure>()
        relay(failures = TransportFailureReporter(sink = failures::add)) { relay, transport ->
            client(relay).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                assertEquals(0, authenticate(relay.configSecret, input, output))
                command(output, 5, 0)
                assertEquals(0, reply(input))
                output.writeShort(1_501)
                output.writeByte(10)
                endpoint(output, 9)
                output.write(1)
                socket.shutdownOutput()
                assertEquals(-1, input.read())
                assertEquals(0L, transport.statistics().openedUdp)
                assertEquals(1, failures.count { it.code == TransportCode.RELAY_PROTOCOL })
                assertFalse(failures.any { it.code == TransportCode.DATAGRAM_LIMIT })
            }
        }
    }

    @Test
    fun stopClosesActiveTcpAndUdpAndDrainsBothPools() {
        ServerSocket(0, 1, ip).use { tcp ->
            DatagramSocket(InetSocketAddress(ip, 0)).use { udp ->
                tcp.soTimeout = 3_000
                udp.soTimeout = 3_000
                relay { relay, transport ->
                    client(relay).use { tcpClient ->
                        client(relay).use { udpClient ->
                            for ((socket, protocol, port) in listOf(Triple(tcpClient, 1, tcp.localPort), Triple(udpClient, 5, 0))) {
                                val input = DataInputStream(socket.getInputStream())
                                val output = DataOutputStream(socket.getOutputStream())
                                assertEquals(0, authenticate(relay.configSecret, input, output))
                                command(output, protocol, port)
                                assertEquals(0, reply(input))
                            }
                            tcp.accept().use {
                                frame(DataOutputStream(udpClient.getOutputStream()), udp.localPort, byteArrayOf(5))
                                udp.receive(DatagramPacket(ByteArray(1), 1))
                                assertEquals(1, transport.statistics().activeTcp)
                                assertEquals(1, transport.statistics().activeUdp)
                                relay.close()
                                relay.close()
                                assertEquals(RelayShutdownStatus(0, 0, 0, 0, 0), relay.awaitShutdownResult())
                                assertEquals(0, transport.statistics().pending)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun closingTheRelayAlsoSignalsWorkersThatAreWaitingOutsideSocketIo() {
        val entered = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val transport = OutboundTransport().apply {
            updateNetwork(object : OutboundSocketPolicy {
                override fun prepare(socket: Socket) {
                    entered.countDown()
                    try {
                        check(waiting.await(6, TimeUnit.SECONDS))
                    } catch (error: InterruptedException) {
                        interrupted.countDown()
                        throw error
                    }
                }
                override fun prepare(socket: DatagramSocket) = Unit
            })
        }
        val relay = HevLoopbackRelay(transport, ip, maxSessions = 1) { error("Отказ приёма подключений") }
        try {
            client(relay).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                assertEquals(0, authenticate(relay.configSecret, input, output))
                command(output, 1, 12345)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                relay.close()
                assertTrue(relay.awaitShutdown(3_000))
                assertEquals(0L, interrupted.count)
            }
        } finally {
            waiting.countDown()
            relay.close()
            assertTrue(relay.awaitShutdown())
        }
    }

    @Test
    fun observationsShowUnfinishedPoolAndLaterReleaseWithoutFalseCleanupError() {
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<TransportFailure>()
        val reporter = TransportFailureReporter(sink = failures::add)
        val transport = OutboundTransport(failures = reporter)
        transport.updateNetwork(object : OutboundSocketPolicy {
            override fun prepare(socket: Socket) {
                entered.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (true) {
                    try {
                        check(leave.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS))
                        return
                    } catch (_: InterruptedException) {
                        // Воспроизводим системный вызов, который не завершился после interrupt.
                        interrupted.countDown()
                    }
                }
            }
            override fun prepare(socket: DatagramSocket) = Unit
        })
        val relay = HevLoopbackRelay(transport, ip, maxSessions = 1, failures = reporter) { error("Unexpected listener failure") }
        val drain = Thread(relay::awaitDrained, "Test relay wait").apply { isDaemon = true }
        try {
            client(relay).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                assertEquals(0, authenticate(relay.configSecret, input, output))
                command(output, 1, 12345)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                relay.close()
                relay.close()
                assertTrue(interrupted.await(2, TimeUnit.SECONDS))
                val atDeadline = relay.awaitShutdownResult(100)
                assertFalse(atDeadline.terminated)
                assertEquals(1, atDeadline.requestsActive)
                assertFalse(relay.isTerminated)
                assertFalse(relay.awaitShutdown(100))
                assertEquals(2, atDeadline.pendingMask)
                val afterClose = relay.shutdownDiagnosticLines(includeStacks = false).single()
                assertTrue(afterClose.contains("requests=SHUTDOWN"))
                assertTrue(afterClose.contains("openClients=0"))
                assertTrue(afterClose.contains("idleQueued=0"))

                drain.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                var wait = relay.shutdownDiagnosticLines(includeStacks = false).single()
                while (!wait.contains("wait=requests.awaitTermination") && System.nanoTime() < deadline) {
                    Thread.yield()
                    wait = relay.shutdownDiagnosticLines(includeStacks = false).single()
                }
                assertTrue(wait, wait.contains("wait=requests.awaitTermination waitThreadId=${drain.id}"))
                val snapshot = relay.shutdownDiagnosticLines(includeStacks = true)
                val request = snapshot.single { it.startsWith("thread=HEV request #") }
                assertTrue(request, request.contains("daemon=") && request.contains("interrupted="))
                assertTrue(request, request.substringAfter(" stack=").split(" <- ").size <= 20)
                assertTrue(request, request.contains(".prepare:"))
                assertTrue(snapshot.any { it.startsWith("thread=RelayWait (Test relay wait) id=${drain.id} ") })
                assertTrue(snapshot.none { it.contains(relay.configSecret) })
                assertFalse(failures.any { it.code == TransportCode.RELAY_STOP })
            }
        } finally {
            leave.countDown()
            drain.join(3_000)
            assertFalse(drain.isAlive)
            relay.close()
            assertTrue(relay.awaitShutdown())
            assertTrue(relay.isTerminated)
            assertEquals(RelayShutdownStatus(0, 0, 0, 0, 0), relay.shutdownStatus())
        }
        assertFalse(failures.any { it.code == TransportCode.RELAY_STOP })
    }

    private fun relay(
        destination: InetAddress? = ip,
        maxSessions: Int = 16,
        failures: TransportFailureReporter = TransportFailureReporter(),
        tcpIdleTimeoutMillis: Long = 60_000,
        readTimeoutMillis: Int = 15_000,
        udpIdleTimeoutMillis: Long = 60_000,
        flows: FlowObserver = FlowObserver.None,
        startAccepting: (ServerSocket, Thread) -> Unit = { _, thread -> thread.start() },
        onPreAuthRegistered: ((SocketAddress) -> Unit)? = null,
        closeSocket: (Closeable) -> Unit = Closeable::close,
        test: (HevLoopbackRelay, OutboundTransport) -> Unit,
    ) {
        val transport = OutboundTransport(readTimeoutMillis = readTimeoutMillis, failures = failures).apply { updateNetwork(policy) }
        val relay = HevLoopbackRelay(
            transport, destination, maxSessions, failures, startAccepting,
            tcpIdleTimeoutMillis, flows = flows, udpIdleTimeoutMillis = udpIdleTimeoutMillis,
            onPreAuthRegistered = onPreAuthRegistered, closeRelaySocket = closeSocket,
        ) {
            error("Неожиданная остановка релея")
        }
        relay.use { test(it, transport) }
        assertTrue(relay.awaitShutdown())
        assertTrue(relay.isTerminated)
        assertEquals(0, transport.statistics().activeTcp + transport.statistics().activeUdp)
    }

    private fun frame(output: DataOutputStream, port: Int, payload: ByteArray) {
        output.writeShort(payload.size)
        output.writeByte(10)
        endpoint(output, port)
        output.write(payload)
    }

    private fun response(input: DataInputStream): ByteArray {
        val length = input.readUnsignedShort()
        assertEquals(10, input.readUnsignedByte())
        input.readExact(7)
        return input.readExact(length)
    }

    private fun client(relay: HevLoopbackRelay): Socket {
        val socket = Socket()
        try {
            socket.connectToRelay(InetSocketAddress(ip, relay.port))
            return socket
        } catch (failure: Throwable) {
            socket.use { throw failure }
        }
    }

    private fun authenticate(secret: String, socket: Socket) =
        authenticate(secret, DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()))

    private fun HevLoopbackRelay.diagnosticOpenClients() =
        shutdownDiagnosticLines(includeStacks = false).single().substringAfterLast(" openClients=").toInt()

    private fun assertBeforeHandshakeTimeout(started: Long, finished: Long) {
        // Оставляем запас до пятисекундного дедлайна релея, включая округление тайм-аута чтения.
        assertTrue("Молчащие клиенты могли истечь по тайм-ауту", finished - started < TimeUnit.SECONDS.toNanos(4))
    }

    private fun authenticate(secret: String, input: DataInputStream, output: DataOutputStream): Int {
        output.write(byteArrayOf(5, 1, 2))
        assertArrayEquals(byteArrayOf(5, 2), input.readExact(2))
        output.write(byteArrayOf(1, 8))
        output.write("adaptive".toByteArray())
        output.writeByte(secret.length)
        output.write(secret.toByteArray())
        assertEquals(1, input.readUnsignedByte())
        return input.readUnsignedByte()
    }

    private fun command(output: DataOutputStream, command: Int, port: Int) {
        output.write(byteArrayOf(5, command.toByte(), 0))
        endpoint(output, port)
    }

    private fun endpoint(output: DataOutputStream, port: Int) {
        output.writeByte(1)
        output.write(ip.address)
        output.writeShort(port)
    }

    private fun reply(input: DataInputStream) = input.readExact(10)[1].toInt()

    private fun DataInputStream.readExact(length: Int) = ByteArray(length).also(::readFully)

    private class PreAuthRegistrations {
        private val registered = LinkedBlockingQueue<SocketAddress>()

        fun record(address: SocketAddress) {
            registered.add(address)
        }

        fun await(vararg clients: Socket, timeoutMillis: Long = 3_000) {
            val pending = clients.mapTo(mutableSetOf()) { it.localSocketAddress }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            repeat(clients.size) {
                val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
                val address = checkNotNull(registered.poll(remaining, TimeUnit.NANOSECONDS)) {
                    "Нет регистрации клиентов: $pending"
                }
                check(pending.remove(address)) { "Неожиданная регистрация: $address" }
            }
        }
    }

    private class RelayClients(private val newSocket: () -> Socket = ::Socket) : Closeable {
        private val sockets = mutableListOf<Socket>()

        fun connect(address: InetSocketAddress): Socket = newSocket().also { socket ->
            sockets.add(socket)
            socket.connectToRelay(address)
        }

        override fun close() {
            var failure: Throwable? = null
            for (socket in sockets.asReversed()) {
                try {
                    socket.close()
                } catch (error: Throwable) {
                    val first = failure
                    if (first == null) failure = error
                    else if (first !== error) first.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    private companion object {
        fun Socket.connectToRelay(address: InetSocketAddress) {
            connect(address)
            soTimeout = 3_000
        }
    }
}
