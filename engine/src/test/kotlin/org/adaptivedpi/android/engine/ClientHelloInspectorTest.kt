package org.adaptivedpi.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext

class ClientHelloInspectorTest {
    private fun realHello(name: String = "fixture.example"): ByteArray {
        val engine = SSLContext.getDefault().createSSLEngine(name, 443).apply {
            useClientMode = true
            sslParameters = sslParameters.apply {
                serverNames = listOf(SNIHostName(name))
                applicationProtocols = arrayOf("h2", "http/1.1")
            }
            beginHandshake()
        }
        val packet = ByteBuffer.allocate(engine.session.packetBufferSize)
        assertTrue(engine.wrap(ByteBuffer.allocate(0), packet).bytesProduced() > 9)
        return packet.array().copyOf(packet.position())
    }

    private fun hello(bytes: ByteArray) = ClientHelloInspector.inspect(bytes) as ClientHelloInspector.Result.Hello

    private fun messageOf(record: ByteArray): ByteArray {
        val end = 5 + ((record[3].toInt() and 255) shl 8) + (record[4].toInt() and 255)
        return record.copyOfRange(5, end)
    }

    @Test
    fun readsRealHelloAcrossInputAndRecordBoundaries() {
        val bytes = realHello()
        val result = hello(bytes)
        assertEquals("fixture.example", result.serverName)
        assertEquals(listOf("h2", "http/1.1"), result.alpn)
        assertEquals(false, result.encryptedHello)
        assertEquals(NameIdentification.CERTAIN, result.identification)

        val message = messageOf(bytes)
        val fragmented = record(message.copyOfRange(0, 2)) + record(message.copyOfRange(2, message.size))
        for (length in 0 until fragmented.size) {
            assertEquals(ClientHelloInspector.Result.NeedMore, ClientHelloInspector.inspect(fragmented.copyOf(length)))
        }
        val joined = hello(fragmented)
        assertEquals(result.serverName, joined.serverName)
        assertEquals(result.alpn, joined.alpn)
        assertEquals(hello(record(message + byteArrayOf(2, 0, 0, 0))).serverName, result.serverName)

        // Сообщение целиком, но объявленная запись длиннее: ждать её конца, а не возвращать Hello.
        val longer = record(message + byteArrayOf(2, 0, 0, 0))
        val withoutTail = longer.copyOf(longer.size - 4)
        assertEquals(ClientHelloInspector.Result.NeedMore, ClientHelloInspector.inspect(withoutTail))
        assertEquals(message.size, hello(longer).helloLength)
    }

    @Test
    fun nameRangesAndOffsetsPointAtTheNameBytesEvenWhenARecordBoundaryCutsIt() {
        val bytes = realHello()
        val single = hello(bytes)
        val name = "fixture.example".toByteArray()
        assertEquals(1, single.recordCount)
        assertEquals(1, single.sniRanges.size)
        val range = single.sniRanges.single()
        assertEquals(name.size, range.length)
        assertEquals(name.toList(), bytes.copyOfRange(range.start, range.start + range.length).toList())
        assertEquals(bytes[2].toInt() and 255, single.recordVersion)
        // sniext - начало расширения server_name: тип 0, затем длина.
        val extension = checkNotNull(single.sniExtensionOffset)
        assertEquals(listOf<Byte>(0, 0), bytes.copyOfRange(extension, extension + 2).toList())

        val message = messageOf(bytes)
        val nameInMessage = range.start - 5
        for (cuts in listOf(listOf(nameInMessage + 3), listOf(nameInMessage + 3, nameInMessage + 8))) {
            val bounds = listOf(0) + cuts + message.size
            val stream = bounds.zipWithNext { from, to -> record(message.copyOfRange(from, to)) }.reduce { first, second -> first + second }
            val result = hello(stream)
            assertEquals("fixture.example", result.serverName)
            assertEquals(cuts.size + 1, result.recordCount)
            assertEquals(cuts.size + 1, result.sniRanges.size)
            val collected = result.sniRanges.flatMap { stream.copyOfRange(it.start, it.start + it.length).toList() }
            assertEquals(name.toList(), collected)
            // Между участками лежит заголовок следующей записи.
            result.sniRanges.zipWithNext().forEach { (first, second) -> assertEquals(first.start + first.length + 5, second.start) }
            // Позиция по обе стороны стыка указывает на байт имени, а не заголовка.
            for (offset in nameInMessage until nameInMessage + name.size) {
                assertEquals(message[offset], stream[result.streamOffset(offset)])
            }
            // Начало расширения server_name лежит до первого разреза, поэтому его позиция в потоке не сместилась.
            assertEquals(extension, result.sniExtensionOffset)
        }
    }

    @Test
    fun echIsReportedSeparatelyFromTheVisibleNameAndIdentification() {
        val base = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(0, 0, 2, 0x13, 1, 1, 0)
        val plain = hello(helloRecord(base + extensions(sni("outer.example"))))
        assertEquals(NameIdentification.CERTAIN, plain.identification)
        assertEquals(false, plain.encryptedHello)

        val ech = echExtension()
        val withEch = hello(helloRecord(base + extensions(sni("outer.example"), ech)))
        assertEquals("outer.example", withEch.serverName)
        assertEquals(true, withEch.encryptedHello)
        assertEquals(NameIdentification.AMBIGUOUS_ECH, withEch.identification)

        val noName = hello(helloRecord(base + extensions(ech)))
        assertNull(noName.serverName)
        assertEquals(true, noName.encryptedHello)
        assertEquals(NameIdentification.NONE, noName.identification)
        assertTrue(noName.sniRanges.isEmpty())
        assertNull(noName.sniExtensionOffset)
    }

    @Test
    fun sameHelloThroughRecordsAndThroughAssembledMessageGivesTheSameFeatures() {
        val bytes = realHello()
        val viaRecords = hello(bytes)
        val message = messageOf(bytes)
        val direct = ClientHelloInspector.inspectHandshake(message, StreamMap.identity(message.size)) as ClientHelloInspector.Result.Hello
        assertEquals(viaRecords.serverName, direct.serverName)
        assertEquals(viaRecords.alpn, direct.alpn)
        assertEquals(viaRecords.encryptedHello, direct.encryptedHello)
        assertEquals(viaRecords.identification, direct.identification)
        assertEquals(viaRecords.helloLength, direct.helloLength)
        // Без заголовка записи координаты сообщения сдвинуты ровно на его длину.
        assertEquals(viaRecords.sniRanges.single().start - 5, direct.sniRanges.single().start)
        assertEquals(ClientHelloInspector.Result.NeedMore, ClientHelloInspector.inspectHandshake(message.copyOf(message.size - 1), StreamMap.identity(message.size)))
        assertEquals(ClientHelloInspector.Result.Unknown, ClientHelloInspector.inspectHandshake(byteArrayOf(22, 0, 0, 1, 0), StreamMap.identity(5)))
    }

    @Test
    fun rejectsInvalidLengthsAndKeepsNoNameWithoutExtensions() {
        val base = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(0, 0, 2, 0x13, 1, 1, 0)
        val bare = hello(helloRecord(base))
        assertNull(bare.serverName)
        assertEquals(NameIdentification.NONE, bare.identification)
        assertEquals(ClientHelloInspector.Result.Unknown, ClientHelloInspector.inspect(helloRecord(base + byteArrayOf(0, 8, 0))))
        assertEquals(ClientHelloInspector.Result.Unknown, ClientHelloInspector.inspect(ByteArray(ClientHelloInspector.MAX_PREFIX + 1)))
        assertEquals(ClientHelloInspector.Result.Unknown, ClientHelloInspector.inspect(byteArrayOf(22, 3, 3, 0x7f, 0x7f)))
        assertEquals(ClientHelloInspector.Result.Unknown, ClientHelloInspector.inspect("GET /".toByteArray()))
    }

    @Test
    fun randomAndMutatedPrefixesNeverThrowAndStayWithinTheBuffer() {
        val random = Random(0x544c53)
        val valid = realHello()
        val recordsOf = listOf(valid, record(messageOf(valid).copyOfRange(0, 40)) + record(messageOf(valid).copyOfRange(40, messageOf(valid).size)))
        repeat(10_000) { iteration ->
            val bytes = when (iteration % 3) {
                0 -> ByteArray(random.nextInt(ClientHelloInspector.MAX_PREFIX)).also(random::nextBytes).also { if (it.isNotEmpty()) it[0] = 22 }
                1 -> recordsOf[random.nextInt(recordsOf.size)].copyOf().also { copy ->
                    repeat(1 + random.nextInt(4)) { copy[random.nextInt(copy.size)] = random.nextInt().toByte() }
                }
                else -> recordsOf[random.nextInt(recordsOf.size)].let { it.copyOf(random.nextInt(it.size + 1)) }
            }
            val result = ClientHelloInspector.inspect(bytes)
            if (result is ClientHelloInspector.Result.Hello) {
                for (range in result.sniRanges) assertTrue(range.start >= 0 && range.start + range.length <= bytes.size)
            }
        }
    }

    @Test
    fun parsingTimeGrowsLinearlyWithTheInputSize() {
        fun timeOf(padding: Int): Long {
            val base = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(0, 0, 2, 0x13, 1, 1, 0)
            val pad = byteArrayOf(0, 21, (padding ushr 8).toByte(), padding.toByte()) + ByteArray(padding)
            val bytes = helloRecord(base + extensions(sni("fixture.example"), pad))
            repeat(2_000) { ClientHelloInspector.inspect(bytes) }
            val samples = LongArray(9) {
                val start = System.nanoTime()
                repeat(400) { ClientHelloInspector.inspect(bytes) }
                System.nanoTime() - start
            }
            return samples.sorted()[samples.size / 2]
        }
        val small = timeOf(6_000)
        val large = timeOf(12_000)
        assertTrue("удвоение размера: $small -> $large нс", large <= 3 * small + 2_000_000)
    }

    private fun sni(name: String): ByteArray {
        val bytes = name.toByteArray()
        val entry = byteArrayOf(0, (bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes
        val list = byteArrayOf((entry.size ushr 8).toByte(), entry.size.toByte()) + entry
        return byteArrayOf(0, 0, (list.size ushr 8).toByte(), list.size.toByte()) + list
    }

    private fun echExtension() = byteArrayOf(0xfe.toByte(), 0x0d, 0, 3, 0, 0, 0)

    private fun extensions(vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { all, part -> all + part }
        return byteArrayOf((body.size ushr 8).toByte(), body.size.toByte()) + body
    }

    private fun helloRecord(body: ByteArray): ByteArray {
        val size = body.size
        return record(byteArrayOf(1, (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + body)
    }

    private fun record(body: ByteArray) =
        byteArrayOf(22, 3, 3, (body.size ushr 8).toByte(), body.size.toByte()) + body
}
