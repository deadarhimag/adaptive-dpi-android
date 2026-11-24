package org.adaptivedpi.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class RelayHandshakeInputTest {
    @Test
    fun successfulReadDoesNotResetDeadline() {
        var now = 0L
        val observed = mutableListOf<Int>()
        val input = RelayHandshakeInput(ByteArrayInputStream(byteArrayOf(1, 2, 3)), { observed.add(it) }, now) { now }
        assertEquals(1, input.read())
        now = TimeUnit.SECONDS.toNanos(4)
        assertEquals(1, input.read(ByteArray(1), 0, 1))
        now = TimeUnit.SECONDS.toNanos(5)
        assertThrows(SocketTimeoutException::class.java) { input.read() }
        assertEquals(listOf(5000, 1000), observed)
    }
}
