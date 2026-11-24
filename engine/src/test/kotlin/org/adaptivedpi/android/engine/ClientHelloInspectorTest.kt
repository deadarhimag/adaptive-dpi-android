package org.adaptivedpi.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext

class ClientHelloInspectorTest {
    @Test
    fun readsRealHelloAcrossInputAndRecordBoundaries() {
        val engine = SSLContext.getDefault().createSSLEngine("fixture.example", 443).apply {
            useClientMode = true
            sslParameters = sslParameters.apply {
                serverNames = listOf(SNIHostName("fixture.example"))
                applicationProtocols = arrayOf("h2", "http/1.1")
            }
            beginHandshake()
        }
        val packet = ByteBuffer.allocate(engine.session.packetBufferSize)
        assertTrue(engine.wrap(ByteBuffer.allocate(0), packet).bytesProduced() > 9)
        val bytes = packet.array().copyOf(packet.position())
        val expected = ClientHelloInspector.Result.Hello("fixture.example", listOf("h2", "http/1.1"), false)
        assertEquals(expected, ClientHelloInspector.inspect(bytes))

        val end = 5 + ((bytes[3].toInt() and 255) shl 8) + (bytes[4].toInt() and 255)
        val message = bytes.copyOfRange(5, end)
        val fragmented = record(message.copyOfRange(0, 2)) + record(message.copyOfRange(2, message.size))
        for (length in 0 until fragmented.size) {
            assertEquals(ClientHelloInspector.Result.NeedMore, ClientHelloInspector.inspect(fragmented.copyOf(length)))
        }
        assertEquals(expected, ClientHelloInspector.inspect(fragmented))
        assertEquals(expected, ClientHelloInspector.inspect(record(message + byteArrayOf(2, 0, 0, 0))))
    }

    @Test
    fun rejectsInvalidLengthsAndSuppressesOuterEchMetadata() {
        val base = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(0, 0, 2, 0x13, 1, 1, 0)
        assertEquals(ClientHelloInspector.Result.Hello(null, emptyList(), false), ClientHelloInspector.inspect(hello(base)))
        val outer = byteArrayOf(0, 7, 0xfe.toByte(), 0x0d, 0, 3, 0, 0, 0)
        assertEquals(ClientHelloInspector.Result.Hello(null, emptyList(), true), ClientHelloInspector.inspect(hello(base + outer)))
        assertEquals(ClientHelloInspector.Result.Unknown, ClientHelloInspector.inspect(hello(base + byteArrayOf(0, 8, 0))))
        assertEquals(ClientHelloInspector.Result.Unknown, ClientHelloInspector.inspect(ByteArray(ClientHelloInspector.MAX_PREFIX + 1)))
        assertEquals(ClientHelloInspector.Result.Unknown, ClientHelloInspector.inspect(byteArrayOf(22, 3, 3, 0x7f, 0x7f)))
        assertEquals(ClientHelloInspector.Result.Unknown, ClientHelloInspector.inspect("GET /".toByteArray()))
    }

    @Test
    fun malformedInputsProduceOnlyBoundedResults() {
        val random = java.util.Random(0x544c53)
        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(256)).also(random::nextBytes)
            if (bytes.isNotEmpty()) bytes[0] = 22
            ClientHelloInspector.inspect(bytes)
        }
    }

    private fun hello(body: ByteArray) = record(byteArrayOf(1, 0, 0, body.size.toByte()) + body)

    private fun record(body: ByteArray) =
        byteArrayOf(22, 3, 3, (body.size ushr 8).toByte(), body.size.toByte()) + body
}
