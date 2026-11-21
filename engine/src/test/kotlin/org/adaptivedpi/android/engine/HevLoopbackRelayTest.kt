package org.adaptivedpi.android.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

class HevLoopbackRelayTest {
    private val ip = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val policy = object : OutboundSocketPolicy {
        override fun prepare(socket: Socket) = Unit
        override fun prepare(socket: DatagramSocket) = Unit
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

    private fun relay(test: (HevLoopbackRelay, OutboundTransport) -> Unit) {
        val transport = OutboundTransport().apply { updateNetwork(policy) }
        val relay = HevLoopbackRelay(transport, ip) { error("Неожиданная остановка релея") }
        relay.use { test(it, transport) }
        assertTrue(relay.awaitShutdown())
        assertEquals(0, transport.statistics().activeTcp + transport.statistics().activeUdp)
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
