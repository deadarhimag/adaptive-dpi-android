package org.adaptivedpi.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket

class SocketClosuresTest {
    @Test
    fun confirmedAndRepeatedCloseAllowRestart() {
        val closures = SocketClosures()
        val socket = Socket()
        repeat(2) { closures.close(socket) { throw AssertionError(it) } }
        assertEquals(SocketCloseSummary(closed = 1, alreadyClosed = 1), closures.snapshot())
        assertTrue(closures.snapshot().confirmed)
    }

    @Test
    fun failedAndUnverifiedCloseRemainVisibleInResult() {
        val closures = SocketClosures()
        val error = IOException("Ошибка закрытия")
        var reported: Exception? = null
        var closed = false
        closures.close({ closed = true; throw error }, { closed }) { reported = it }
        closures.close({}, { false }) { throw AssertionError(it) }
        assertEquals(error, reported)
        assertEquals(SocketCloseSummary(failed = 1, unknown = 1), closures.snapshot())
        assertFalse(closures.snapshot().confirmed)
    }
}
