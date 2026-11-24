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
        assertEquals(SocketCloseSummary(failed = 1, unknown = 1, unconfirmed = 2), closures.snapshot())
        assertFalse(closures.snapshot().confirmed)
    }

    @Test
    fun confirmedRetryReleasesTheBlockButKeepsTheFirstFailureAsWarning() {
        val closures = SocketClosures()
        var attempts = 0
        var closed = false
        val reported = mutableListOf<Exception>()
        closures.close({ attempts++; throw IOException("первая попытка") }, { closed }) { reported += it }
        assertFalse(closures.snapshot().confirmed)

        closures.retryUnconfirmed { reported += it }
        assertFalse("Повтор с ошибкой оставляет сокет неподтверждённым", closures.snapshot().confirmed)

        closures.close({ throw AssertionError("не тот сокет") }, { true }) { throw AssertionError(it) }
        closed = true
        closures.retryUnconfirmed { reported += it }
        assertEquals(SocketCloseSummary(alreadyClosed = 1, failed = 1), closures.snapshot())
        assertTrue(closures.snapshot().confirmed)
        assertEquals(2, attempts)
        assertEquals(2, reported.size)
    }

    @Test
    fun retryClosesARealSocketThatWasNotClosed() {
        val closures = SocketClosures()
        val socket = Socket()
        var first = true
        // Первая попытка закрытия даёт сбой, повтор той же операции освобождает сокет.
        closures.close({ if (first) { first = false; throw IOException("сбой") }; socket.close() }, socket::isClosed) {}
        assertFalse(closures.snapshot().confirmed)
        closures.retryUnconfirmed { throw AssertionError(it) }
        assertTrue(socket.isClosed)
        assertTrue(closures.snapshot().confirmed)
    }
}
