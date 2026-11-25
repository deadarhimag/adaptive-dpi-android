package org.adaptivedpi.android.engine.flow

import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode
import org.adaptivedpi.android.engine.TransportOperation
import java.net.InetAddress

/** Адрес и порт потока. В исходе остаётся диагностический хеш с солью сеанса, не идентификатор ресурса. */
class FlowKey(val address: InetAddress, val port: Int)

enum class FlowKind { TLS, HTTP, OTHER }

/** Где закончилось соединение. */
enum class FlowStage {
    /** TCP-подключение не удалось. */
    CONNECT,
    /** Подключено, ответа сервера не было. */
    NO_RESPONSE,
    /** Сервер ответил, клиент после этого ничего не отправил. */
    FIRST_CHUNK,
    /** После ответа сервера клиент тоже отправлял данные. */
    EXCHANGED,
}

/** Признаки ClientHello без имени: есть ли SNI, есть ли расширение ECH, размер сообщения и число TLS-записей. */
data class FlowHello(val hasName: Boolean, val ech: Boolean, val length: Int, val records: Int)

data class FlowFailure(
    val category: TransportCategory,
    val code: TransportCode,
    val errno: Int?,
    val operation: TransportOperation? = null,
) {
    /**
     * Ограничения самого приложения и отмены при остановке не являются отказом ключа
     * ([docs/ENGINE.md](ENGINE.md), раздел 3): иначе лимит релея выглядел бы как блокировка.
     */
    val internal: Boolean
        get() = category in INTERNAL_CATEGORIES || operation in LOCAL_OPERATIONS || code in INTERNAL_CODES

    companion object {
        private val INTERNAL_CATEGORIES = setOf(
            TransportCategory.CANCELLED, TransportCategory.PROTECTION, TransportCategory.SOCKET_SETUP,
            TransportCategory.LIMIT, TransportCategory.INTERNAL, TransportCategory.CLEANUP,
            TransportCategory.TUN_IO, TransportCategory.HEV_INIT, TransportCategory.RELAY_PROTOCOL,
        )
        private val LOCAL_OPERATIONS = setOf(TransportOperation.INIT, TransportOperation.PROTECT, TransportOperation.BIND, TransportOperation.SCHEDULE)
        private val INTERNAL_CODES = setOf(
            TransportCode.SESSION_LIMIT, TransportCode.SOCKET_LIMIT, TransportCode.WORKER_REJECTED, TransportCode.NO_PHYSICAL_NETWORK,
        )
    }
}

/** Исход одного TCP-соединения. [millisToFailure] отсчитывается от отправки первой порции, [connectMillis] - время установления TCP. */
data class FlowRecord(
    val keyHash: Int,
    val port: Int,
    val kind: FlowKind,
    val hello: FlowHello?,
    val stage: FlowStage,
    val failure: FlowFailure?,
    val connectMillis: Long,
    val millisToFailure: Long?,
    val bytesToServer: Long,
    val bytesFromServer: Long,
    /** Аварийное закрытие клиентского сокета около первого ответа. Прикладной исход неизвестен. */
    val clientAbandoned: Boolean,
    /** Байты в секунду после первой порции ответа; null, если интервал слишком короткий для измерения. */
    val receiveBytesPerSecond: Long?,
)

/** Итог UDP-ассоциации релея: число датаграмм в обе стороны. */
data class UdpRecord(
    val port: Int, val outDatagrams: Long, val inDatagrams: Long,
    val end: UdpEnd = UdpEnd.FINISHED,
)

enum class UdpEnd { FINISHED, IDLE, ERROR, CANCELLED }

/** Не блокирует: вызывается из потоков соединений релея. */
interface FlowObserver {
    fun onOutcome(outcome: FlowRecord)
    fun onUdp(association: UdpRecord) = Unit
    /** Отказ самого релея (лимиты, перегрузка): не отказ ключа. */
    fun onInternalRefusal(code: TransportCode) = Unit
    /** Число одновременных TCP-соединений после открытия нового. */
    fun onActive(active: Int) = Unit

    companion object {
        val None: FlowObserver = object : FlowObserver {
            override fun onOutcome(outcome: FlowRecord) = Unit
        }
    }
}
