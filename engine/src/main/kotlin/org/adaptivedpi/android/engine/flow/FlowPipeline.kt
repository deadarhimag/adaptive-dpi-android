package org.adaptivedpi.android.engine.flow

import org.adaptivedpi.android.engine.ClientHelloInspector
import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Источник байтов клиента. [read] ждёт не дольше [timeoutMillis]: по истечении бросает [SocketTimeoutException]. */
internal fun interface PrefixSource {
    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int
}

/** Накопленное начало потока. Байты в нём ровно те, что прислал клиент. */
internal class Captured(val bytes: ByteArray, val size: Int, val eof: Boolean)

/**
 * Захват начала TCP-потока только для подсчёта: байты не меняются и уходят серверу в том же порядке.
 * Не-TLS поток не ждёт. Для TLS накопление ограничено [ClientHelloInspector.MAX_PREFIX] и одним
 * монотонным сроком [captureNanos] на весь захват.
 */
internal class FlowPipeline(
    private val observer: FlowObserver,
    private val report: (Throwable) -> Unit,
    internal val clock: () -> Long = System::nanoTime,
    private val captureNanos: Long = TimeUnit.MILLISECONDS.toNanos(CAPTURE_MILLIS),
    private val classify: (ByteArray, Int) -> ClientHelloInspector.Result = { bytes, length -> ClientHelloInspector.inspect(bytes, length) },
) {
    private val salt = ByteArray(16).also(SecureRandom()::nextBytes)
    private val active = AtomicInteger()

    fun begin(key: FlowKey, connectNanos: Long): FlowTracker {
        val tracker = FlowTracker(this, hash(key), key.port, connectNanos)
        safely { observer.onActive(active.incrementAndGet()) }
        return tracker
    }

    fun connectFailed(key: FlowKey, connectNanos: Long, failure: FlowFailure) {
        safely {
            observer.onOutcome(
                FlowRecord(
                    hash(key), key.port, FlowKind.OTHER, null, FlowStage.CONNECT, failure, nanosToMillis(connectNanos), null,
                    0, 0, clientAbandoned = false, receiveBytesPerSecond = null,
                ),
            )
        }
    }

    fun udpEnded(record: UdpRecord) = safely { observer.onUdp(record) }

    fun internalRefusal(code: TransportCode) = safely { observer.onInternalRefusal(code) }

    /**
     * [first] - уже прочитанные [firstSize] байт клиента. Не-TLS возвращается сразу, без копирования и ожидания.
     * Ошибки чтения [source] выходят наружу, ошибки классификатора перехватываются: поток идёт обычным копированием.
     */
    fun capture(tracker: FlowTracker, first: ByteArray, firstSize: Int, source: PrefixSource): Captured {
        val kind = kindOf(first, firstSize)
        tracker.kind = kind
        if (kind != FlowKind.TLS) return Captured(first, firstSize, false)

        val prefix = ByteArray(ClientHelloInspector.MAX_PREFIX)
        var size = minOf(firstSize, prefix.size)
        first.copyInto(prefix, endIndex = size)
        var eof = false
        val deadline = clock() + captureNanos
        while (true) {
            val result = try {
                classify(prefix, size)
            } catch (error: Exception) {
                reportSafely(error)
                ClientHelloInspector.Result.Unknown
            }
            if (result is ClientHelloInspector.Result.Hello) {
                tracker.hello = FlowHello(result.serverName != null, result.encryptedHello, result.helloLength, result.recordCount)
                break
            }
            if (result !is ClientHelloInspector.Result.NeedMore || size >= prefix.size) break
            val remaining = deadline - clock()
            if (remaining <= 0) break
            val count = try {
                source.read(prefix, size, prefix.size - size, TimeUnit.NANOSECONDS.toMillis(remaining + 999_999).coerceAtLeast(1).toInt())
            } catch (_: SocketTimeoutException) {
                break
            }
            if (count < 0) {
                eof = true
                break
            }
            size += count
        }
        return if (firstSize > prefix.size) Captured(first, firstSize, false) else Captured(prefix, size, eof)
    }

    internal fun finished() = active.decrementAndGet()

    internal fun emit(record: FlowRecord) = safely { observer.onOutcome(record) }

    internal fun nanosToMillis(nanos: Long) = TimeUnit.NANOSECONDS.toMillis(nanos)

    private fun hash(key: FlowKey): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(key.address.address)
        digest.update((key.port ushr 8).toByte())
        digest.update(key.port.toByte())
        val bytes = digest.digest()
        return ((bytes[0].toInt() and 255) shl 24) or ((bytes[1].toInt() and 255) shl 16) or
            ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
    }

    // Наблюдатель не должен ломать соединение.
    private inline fun safely(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            reportSafely(error)
        }
    }

    private fun reportSafely(error: Exception) {
        try { report(error) } catch (_: Exception) { /* Сбой диагностики не отменяет передачу. */ }
    }

    private fun kindOf(bytes: ByteArray, size: Int): FlowKind {
        if (size == 0) return FlowKind.OTHER
        if (bytes[0].toInt() == 0x16) return FlowKind.TLS
        return if (HTTP_METHODS.any { method -> hasHttpPrefix(bytes, size, method) }) {
            FlowKind.HTTP
        } else {
            FlowKind.OTHER
        }
    }

    // Пассивный признак. Смотрим только доступную часть первой строки, не читаем дополнительные байты.
    private fun hasHttpPrefix(bytes: ByteArray, size: Int, method: ByteArray): Boolean {
        if (size < method.size || method.indices.any { bytes[it] != method[it] }) return false
        for (index in method.size until size) {
            val character = bytes[index].toInt()
            if (character == 13 || character == 10) return index > method.size
            if (character !in 32..126 || index == method.size && character == 32) return false
        }
        return true
    }

    companion object {
        const val CAPTURE_MILLIS = 250L
        private val HTTP_METHODS = listOf("GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ", "TRACE ")
            .map { it.toByteArray(Charsets.US_ASCII) }
    }
}

/** Счётчики одного соединения. Запросный и ответный потоки обновляют разные поля. */
internal class FlowTracker(
    private val owner: FlowPipeline,
    private val keyHash: Int,
    private val port: Int,
    private val connectNanos: Long,
) {
    @Volatile var kind = FlowKind.OTHER
    @Volatile var hello: FlowHello? = null
    private val toServer = AtomicLong()
    private val fromServer = AtomicLong()
    @Volatile private var helloSentAt: Long? = null
    @Volatile private var firstResponseAt: Long? = null
    @Volatile private var firstResponseBytes = 0L
    @Volatile private var lastResponseAt = 0L
    @Volatile private var clientSentAfterResponse = false
    @Volatile private var clientClosedAt: Long? = null
    private val failure = AtomicReference<Pair<FlowFailure, Long>?>()
    private val finished = AtomicBoolean()

    fun sentToServer(size: Int, writeStartedAt: Long) {
        toServer.addAndGet(size.toLong())
        if (firstResponseAt?.let { writeStartedAt > it } == true) clientSentAfterResponse = true
    }

    /** Первая порция клиента (ClientHello или начало запроса) передана серверу. */
    fun firstPortionSent() {
        if (helloSentAt == null) helloSentAt = owner.clock()
    }

    fun receivedFromServer(size: Int) {
        val now = owner.clock()
        val total = fromServer.addAndGet(size.toLong())
        if (firstResponseAt == null) {
            firstResponseBytes = total
            firstResponseAt = now
        }
        lastResponseAt = now
    }

    /** Наблюдалось аварийное завершение клиентского сокета. Обычный EOF подтверждает лишь полузакрытие. */
    fun clientClosed() {
        if (clientClosedAt == null) clientClosedAt = owner.clock()
    }

    fun failed(value: FlowFailure) {
        failure.compareAndSet(null, value to owner.clock())
    }

    /** Простой соединения закрыт релеем. Отказ ключа - только если сервер так и не ответил («тишина»). */
    fun idleTimeout() {
        if (fromServer.get() == 0L) failed(FlowFailure(TransportCategory.TIMEOUT, TransportCode.TIMEOUT, null))
    }

    fun finish() {
        if (!finished.compareAndSet(false, true)) return
        owner.finished()
        val bytesFromServer = fromServer.get()
        val failed = failure.get()
        val stage = when {
            bytesFromServer == 0L -> FlowStage.NO_RESPONSE
            clientSentAfterResponse -> FlowStage.EXCHANGED
            else -> FlowStage.FIRST_CHUNK
        }
        val firstResponse = firstResponseAt
        val clientClosed = clientClosedAt
        val abandoned = firstResponse != null && clientClosed != null && !clientSentAfterResponse &&
            failed?.first?.internal != true && clientClosed - firstResponse in 0L..maxOf(connectNanos, MIN_RTT_NANOS)
        val interval = firstResponse?.let { lastResponseAt - it } ?: 0L
        val rate = if (interval >= MIN_RATE_INTERVAL_NANOS && bytesFromServer > firstResponseBytes) {
            ((bytesFromServer - firstResponseBytes) * 1_000_000_000.0 / interval).toLong()
        } else {
            null
        }
        owner.emit(
            FlowRecord(
                keyHash, port, kind, hello, stage, failed?.first, owner.nanosToMillis(connectNanos),
                helloSentAt?.let { sent -> failed?.second?.minus(sent)?.takeIf { it >= 0 }?.let(owner::nanosToMillis) },
                toServer.get(), bytesFromServer, abandoned, rate,
            ),
        )
    }

    private companion object {
        const val MIN_RTT_NANOS = 1_000_000L
        const val MIN_RATE_INTERVAL_NANOS = 10_000_000L
    }
}
