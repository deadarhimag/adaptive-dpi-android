package org.adaptivedpi.android.engine

import java.io.Closeable
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

data class SocketCloseSummary(
    val closed: Long = 0,
    val alreadyClosed: Long = 0,
    val failed: Long = 0,
    val unknown: Long = 0,
) {
    val confirmed get() = failed == 0L && unknown == 0L
    operator fun plus(other: SocketCloseSummary) = SocketCloseSummary(
        closed + other.closed, alreadyClosed + other.alreadyClosed, failed + other.failed, unknown + other.unknown,
    )
}

internal class SocketClosures {
    private val closed = AtomicLong()
    private val alreadyClosed = AtomicLong()
    private val failed = AtomicLong()
    private val unknown = AtomicLong()

    fun close(socket: Closeable, onError: (Exception) -> Unit) {
        close(socket::close, {
            when (socket) {
                is Socket -> socket.isClosed
                is DatagramSocket -> socket.isClosed
                is ServerSocket -> socket.isClosed
                else -> false
            }
        }, onError)
    }

    internal fun close(operation: () -> Unit, isClosed: () -> Boolean, onError: (Exception) -> Unit) {
        if (isClosed()) {
            alreadyClosed.incrementAndGet()
            return
        }
        try {
            operation()
        } catch (error: Exception) {
            failed.incrementAndGet()
            onError(error)
            return
        }
        if (isClosed()) closed.incrementAndGet() else unknown.incrementAndGet()
    }

    fun snapshot() = SocketCloseSummary(closed.get(), alreadyClosed.get(), failed.get(), unknown.get())
}
