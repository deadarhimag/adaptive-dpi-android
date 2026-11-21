package org.adaptivedpi.android.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OutboundTransportTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val localPolicy = object : OutboundSocketPolicy {
        override fun prepare(socket: Socket) {
            assertFalse(socket.isConnected)
            assertFalse(socket.isBound)
        }

        override fun prepare(socket: DatagramSocket) {
            assertFalse(socket.isConnected)
            assertFalse(socket.isBound)
        }
    }

    @Test
    fun tcpCarriesBytesInBothDirectionsAfterPolicyAndPreservesHalfClose() {
        val request = byteArrayOf(0, 12, -1, 42)
        val response = byteArrayOf(42, -1, 12, 0)
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, loopback).use { listener ->
                listener.soTimeout = TIMEOUT
                val server = executor.submit {
                    listener.accept().use { peer ->
                        peer.soTimeout = TIMEOUT
                        assertArrayEquals(request, peer.getInputStream().readNBytes(request.size))
                        assertEquals(-1, peer.getInputStream().read())
                        peer.getOutputStream().write(response)
                    }
                }
                transport().use { transport ->
                    transport.connectTcp(loopback, listener.localPort).use { connection ->
                        assertEquals(1, transport.statistics().activeTcp)
                        connection.write(request)
                        connection.shutdownOutput()
                        val actual = ByteArray(response.size + 1)
                        var length = 0
                        while (length < actual.size) {
                            val read = connection.read(actual, length)
                            if (read == -1) break
                            length += read
                        }
                        assertArrayEquals(response, actual.copyOf(length))
                    }
                    assertEquals(0, transport.statistics().activeTcp)
                    assertEquals(request.size.toLong(), transport.statistics().sentBytes)
                    assertEquals(response.size.toLong(), transport.statistics().receivedBytes)
                    assertEquals(0L, transport.statistics().failedOperations)
                }
                server.get(TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun udpForwardsDnsWireBytesToLocalFixtureAndCountsDatagrams() {
        // Один A-запрос example.test с идентификатором 0x1234. Сервер работает только на loopback.
        val query = byteArrayOf(
            0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 101, 120, 97, 109, 112, 108, 101, 4, 116, 101, 115, 116, 0, 0, 1, 0, 1,
        )
        val answer = query.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
        } + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192.toByte(), 0, 2, 45)

        udpServer().use { peer ->
            transport().use { transport ->
                transport.connectUdp(loopback, peer.localPort).use { connection ->
                    assertEquals(1, transport.statistics().activeUdp)
                    connection.send(query)
                    val request = DatagramPacket(ByteArray(512), 512)
                    peer.receive(request)
                    assertArrayEquals(query, request.data.copyOf(request.length))
                    peer.send(DatagramPacket(answer, answer.size, request.socketAddress))
                    val buffer = ByteArray(512)
                    assertArrayEquals(answer, buffer.copyOf(connection.receive(buffer)))
                }
                val stats = transport.statistics()
                assertEquals(0, stats.activeUdp)
                assertEquals(1L, stats.sentDatagrams)
                assertEquals(1L, stats.receivedDatagrams)
                assertEquals(query.size.toLong(), stats.sentBytes)
                assertEquals(answer.size.toLong(), stats.receivedBytes)
            }
        }
    }

    @Test
    fun policyRejectionClosesTcpAndUdpWithoutContactingThePeer() {
        lateinit var tcp: Socket
        lateinit var udp: DatagramSocket
        val rejected = object : OutboundSocketPolicy {
            override fun prepare(socket: Socket) {
                tcp = socket
                throw SocketException("Отказ защиты.")
            }

            override fun prepare(socket: DatagramSocket) {
                udp = socket
                throw SocketException("Отказ защиты.")
            }
        }
        ServerSocket(0, 1, loopback).use { peer ->
            peer.soTimeout = 150
            udpServer().use { udpPeer ->
                udpPeer.soTimeout = 150
                transport().use { transport ->
                    transport.updateNetwork(rejected)
                    assertThrows(SocketException::class.java) { transport.connectTcp(loopback, peer.localPort) }
                    assertThrows(SocketException::class.java) { transport.connectUdp(loopback, udpPeer.localPort) }
                    assertTrue(tcp.isClosed)
                    assertTrue(udp.isClosed)
                    assertEquals(0, transport.statistics().pending)
                    assertEquals(2L, transport.statistics().failedOperations)
                    assertThrows(SocketTimeoutException::class.java) { peer.accept() }
                    assertThrows(SocketTimeoutException::class.java) { udpPeer.receive(DatagramPacket(ByteArray(8), 8)) }
                }
            }
        }
    }

    @Test
    fun networkLossClosesBlockedReadAndRestoredNetworkCanOpenNewFlows() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            udpServer().use { peer ->
                transport().use { transport ->
                    val first = transport.connectUdp(loopback, peer.localPort)
                    val reading = CountDownLatch(1)
                    val pendingRead = executor.submit {
                        reading.countDown()
                        assertThrows(IOException::class.java) { first.receive(ByteArray(64)) }
                    }
                    assertTrue(reading.await(TIMEOUT.toLong(), TimeUnit.MILLISECONDS))
                    transport.updateNetwork(null)
                    pendingRead.get(TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
                    assertEquals(0, transport.statistics().activeUdp)
                    assertThrows(SocketException::class.java) { transport.connectUdp(loopback, peer.localPort) }
                    transport.updateNetwork(localPolicy)
                    transport.connectUdp(loopback, peer.localPort).use { restored ->
                        assertEquals(1, transport.statistics().activeUdp)
                        first.close()
                        restored.send(byteArrayOf(7))
                        val packet = DatagramPacket(ByteArray(8), 8)
                        peer.receive(packet)
                        assertArrayEquals(byteArrayOf(7), packet.data.copyOf(packet.length))
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun inProgressConnectionsConsumeTheSharedBudgetAndAreInvalidatedOnNetworkChange() {
        val preparing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val blocked = object : OutboundSocketPolicy by localPolicy {
            override fun prepare(socket: Socket) {
                preparing.countDown()
                assertTrue(resume.await(TIMEOUT.toLong(), TimeUnit.MILLISECONDS))
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            udpServer().use { peer ->
                transport(maxSockets = 1).use { transport ->
                    transport.updateNetwork(blocked)
                    val connection = executor.submit {
                        assertThrows(IOException::class.java) { transport.connectTcp(loopback, peer.localPort) }
                    }
                    assertTrue(preparing.await(TIMEOUT.toLong(), TimeUnit.MILLISECONDS))
                    assertEquals(1, transport.statistics().pending)
                    assertThrows(SocketException::class.java) { transport.connectUdp(loopback, peer.localPort) }
                    transport.updateNetwork(localPolicy)
                    resume.countDown()
                    connection.get(TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
                    assertEquals(0, transport.statistics().pending)
                    transport.connectUdp(loopback, peer.localPort).use {
                        assertEquals(1, transport.statistics().activeUdp)
                        assertThrows(SocketException::class.java) { transport.connectUdp(loopback, peer.localPort) }
                    }
                }
            }
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun udpRejectsExcessAndTruncationAndCanContinueAfterTheError() {
        udpServer().use { peer ->
            transport().use { transport ->
                transport.connectUdp(loopback, peer.localPort).use { connection ->
                    assertThrows(IllegalArgumentException::class.java) {
                        connection.send(ByteArray(OutboundTransport.MAX_UDP_PAYLOAD + 1))
                    }
                    connection.send(byteArrayOf(1))
                    val packet = DatagramPacket(ByteArray(8), 8)
                    peer.receive(packet)
                    val bytes = byteArrayOf(11, 12, 13)
                    peer.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                    assertThrows(SocketException::class.java) { connection.receive(ByteArray(2)) }
                    assertEquals(0L, transport.statistics().receivedDatagrams)
                    peer.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                    val buffer = ByteArray(4)
                    assertArrayEquals(bytes, buffer.copyOf(connection.receive(buffer)))
                    assertEquals(1L, transport.statistics().failedOperations)
                }
            }
        }
    }

    @Test
    fun stopIsIdempotentAndANewSessionCanSendAgain() {
        udpServer().use { peer ->
            val stopped = transport()
            val connection = stopped.connectUdp(loopback, peer.localPort)
            stopped.close()
            stopped.close()
            assertEquals(0, stopped.statistics().activeUdp)
            stopped.updateNetwork(localPolicy)
            assertThrows(SocketException::class.java) { stopped.connectUdp(loopback, peer.localPort) }
            assertThrows(IOException::class.java) { connection.send(byteArrayOf(1)) }
            connection.close()

            transport().use { restarted ->
                restarted.connectUdp(loopback, peer.localPort).use { fresh ->
                    fresh.send(byteArrayOf(8))
                    val packet = DatagramPacket(ByteArray(8), 8)
                    peer.receive(packet)
                    assertArrayEquals(byteArrayOf(8), packet.data.copyOf(packet.length))
                }
            }
        }
    }

    private fun transport(maxSockets: Int = 64) =
        OutboundTransport(maxSockets, connectTimeoutMillis = TIMEOUT, readTimeoutMillis = 30_000).apply {
            updateNetwork(localPolicy)
        }

    private fun udpServer() = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = TIMEOUT }

    private companion object {
        const val TIMEOUT = 5_000
    }
}
