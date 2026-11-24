package org.adaptivedpi.android.engine

import java.io.Closeable
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * [failed] и [unknown] - история попыток: предупреждение сохраняется и после повторного успешного закрытия.
 * [unconfirmed] - сокеты, освобождение которых сейчас не подтверждено; по нему решается, освобождены ли ресурсы.
 */
data class SocketCloseSummary(
    val closed: Long = 0,
    val alreadyClosed: Long = 0,
    val failed: Long = 0,
    val unknown: Long = 0,
    val unconfirmed: Long = 0,
) {
    val confirmed get() = unconfirmed == 0L
    operator fun plus(other: SocketCloseSummary) = SocketCloseSummary(
        closed + other.closed, alreadyClosed + other.alreadyClosed, failed + other.failed, unknown + other.unknown,
        unconfirmed + other.unconfirmed,
    )
}

internal class SocketClosures {
    private val closed = AtomicLong()
    private val alreadyClosed = AtomicLong()
    private val failed = AtomicLong()
    private val unknown = AtomicLong()
    // Записи держит владелец, пока закрытие не подтверждено: после удаления из таблицы сокетов проверить его больше негде.
    private val unconfirmed = ConcurrentLinkedQueue<Pending>()

    private class Pending(val operation: () -> Unit, val isClosed: () -> Boolean)

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
            unconfirmed.add(Pending(operation, isClosed))
            onError(error)
            return
        }
        if (isClosed()) {
            closed.incrementAndGet()
        } else {
            unknown.incrementAndGet()
            unconfirmed.add(Pending(operation, isClosed))
        }
    }

    /**
     * Повторяет закрытие ещё не подтверждённых сокетов. Повтор безопасен: `close()` у `Socket`, `DatagramSocket`
     * и `ServerSocket` идемпотентен, а у незакрытого сокета повторяет освобождение дескриптора.
     */
    fun retryUnconfirmed(onError: (Exception) -> Unit) {
        for (pending in unconfirmed.toList()) {
            if (!pending.isClosed()) {
                try {
                    pending.operation()
                } catch (error: Exception) {
                    onError(error)
                }
            }
            if (pending.isClosed()) unconfirmed.remove(pending)
        }
    }

    fun snapshot() = SocketCloseSummary(closed.get(), alreadyClosed.get(), failed.get(), unknown.get(), unconfirmed.size.toLong())
}
