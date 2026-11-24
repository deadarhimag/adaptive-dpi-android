package org.adaptivedpi.android.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ClientHelloSplitWriterTest {
    @Test
    fun preservesEveryByteWhenClientHelloPrefixArrivesInParts() {
        val writes = mutableListOf<ByteArray>()
        val writer = ClientHelloSplitWriter { data, offset, count -> writes.add(data.copyOfRange(offset, offset + count)) }
        val input = byteArrayOf(22, 3, 3, 0, 4, 1, 0, 0, 0)
        assertFalse(writer.write(input.copyOfRange(0, 2), 2))
        assertFalse(writer.write(input.copyOfRange(2, 5), 3))
        assertTrue(writer.write(input.copyOfRange(5, input.size), 4))
        assertTrue(writer.write(byteArrayOf(42), 1))
        writer.finish()
        assertEquals(listOf(3, 3, 3, 1), writes.map(ByteArray::size))
        assertArrayEquals(input + byteArrayOf(42), writes.fold(byteArrayOf()) { bytes, next -> bytes + next })
    }

    @Test
    fun preservesShortNonTlsInputAndDoesNotRetryFailedWrite() {
        val source = byteArrayOf(7, 8, 9)
        val writes = mutableListOf<ByteArray>()
        val short = ClientHelloSplitWriter { data, offset, count -> writes.add(data.copyOfRange(offset, offset + count)) }
        assertFalse(short.write(source, source.size))
        short.finish()
        short.finish()
        assertArrayEquals(source, writes.single())

        val failed = ClientHelloSplitWriter { _, _, _ -> writes.add(byteArrayOf(1)); throw IOException() }
        assertThrows(IOException::class.java) { failed.write(byteArrayOf(22, 3, 3, 0, 1, 1), 6) }
        failed.finish()
        assertEquals(2, writes.size)
    }
}
