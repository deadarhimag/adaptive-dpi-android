package org.adaptivedpi.android.engine

import java.io.Closeable
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
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

    @Volatile private var draining = false

    private class Pending(val operation: () -> Unit, val isClosed: () -> Boolean) {
        val retried = AtomicBoolean()
    }

    fun close(socket: Closeable, operation: (Closeable) -> Unit = Closeable::close, onError: (Exception) -> Unit) {
        close({ operation(socket) }, {
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
            try {
                onError(error)
            } finally {
                retain(Pending(operation, isClosed), onError)
            }
            return
        }
        if (isClosed()) {
            closed.incrementAndGet()
        } else {
            unknown.incrementAndGet()
            retain(Pending(operation, isClosed), onError)
        }
    }

    private fun retain(pending: Pending, onError: (Exception) -> Unit) {
        unconfirmed.add(pending)
        // Закрытие из завершающегося потока тоже может освободить ожидающий его поток.
        if (draining) retry(pending, onError)
    }

    /** Один повтор на сокет. Закрытия, начавшиеся после снимка очереди, обработает retain. */
    fun retryUnconfirmed(onError: (Exception) -> Unit) {
        draining = true
        unconfirmed.toList().forEach { retry(it, onError) }
    }

    private fun retry(pending: Pending, onError: (Exception) -> Unit) {
        if (!pending.isClosed() && pending.retried.compareAndSet(false, true)) {
            try {
                pending.operation()
            } catch (error: Exception) {
                onError(error)
            }
        }
        if (pending.isClosed()) unconfirmed.remove(pending)
    }

    fun snapshot() = SocketCloseSummary(closed.get(), alreadyClosed.get(), failed.get(), unknown.get(), unconfirmed.size.toLong())
}
