package org.adaptivedpi.android.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext

class HevLoopbackRelayTest {
    private val ip = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val policy = object : OutboundSocketPolicy {
        override fun prepare(socket: Socket) = Unit
        override fun prepare(socket: DatagramSocket) = Unit
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
        relay(maxSessions = 32) { relay, _ ->
            val silent = (0 until 32).map { client(relay) }
            try {
                client(relay).use { legit ->
                    legit.soTimeout = 1_000
                    val input = DataInputStream(legit.getInputStream())
                    val output = DataOutputStream(legit.getOutputStream())
                    val started = System.nanoTime()
                    assertEquals(0, authenticate(relay.configSecret, input, output))
                    assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1))
                }
            } finally {
                silent.forEach { it.close() }
            }
        }
    }

    @Test(timeout = 10_000)
    fun unauthenticatedOverflowClosesOldestConnectionAndKeepsNewerOnes() {
        // При maxSessions = 4 одновременно допускаются два неаутентифицированных подключения.
        relay(maxSessions = 4) { relay, _ ->
            val silent = (0 until 3).map { client(relay) }
            try {
                assertEquals(-1, silent[0].getInputStream().read())
                silent[1].soTimeout = 150
                assertThrows(SocketTimeoutException::class.java) { silent[1].getInputStream().read() }
                silent[2].soTimeout = 150
                assertThrows(SocketTimeoutException::class.java) { silent[2].getInputStream().read() }
            } finally {
                silent.forEach { it.close() }
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
                    val sockets = mutableListOf<Socket>()
                    try {
                        repeat(8) { index ->
                            val socket = client(relay).also(sockets::add)
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
                    } finally {
                        sockets.forEach(Socket::close)
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
        test: (HevLoopbackRelay, OutboundTransport) -> Unit,
    ) {
        val transport = OutboundTransport(readTimeoutMillis = readTimeoutMillis, failures = failures).apply { updateNetwork(policy) }
        val relay = HevLoopbackRelay(
            transport, destination, maxSessions, failures, { _, thread -> thread.start() },
            tcpIdleTimeoutMillis,
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

    private fun client(relay: HevLoopbackRelay) = Socket(ip, relay.port).apply { soTimeout = 3_000 }

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
}
