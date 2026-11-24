package org.adaptivedpi.android.engine

import java.io.FilterInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Один бюджет на весь обмен аутентификации, включая ожидание рабочего потока. */
internal class RelayHandshakeInput(
    input: InputStream,
    private val setTimeoutMillis: (Int) -> Unit,
    acceptedAt: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) : FilterInputStream(input) {
    private val deadline = acceptedAt + TimeUnit.SECONDS.toNanos(5)

    override fun read(): Int {
        updateTimeout()
        return super.read()
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length != 0) updateTimeout()
        return super.read(bytes, offset, length)
    }

    private fun updateTimeout() {
        val remaining = deadline - nanoTime()
        if (remaining <= 0) throw SocketTimeoutException()
        setTimeoutMillis(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).toInt())
    }
}
