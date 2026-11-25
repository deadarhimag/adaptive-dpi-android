package org.adaptivedpi.android.engine.flow

import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode
import java.net.InetAddress

/** Адрес и порт назначения. В записи исхода остаётся только хеш с солью сеанса, имён ресурсов нет. */
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

data class FlowFailure(val category: TransportCategory, val code: TransportCode, val errno: Int?) {
    /**
     * Ограничения самого приложения и отмены при остановке не являются отказом ключа
     * ([docs/ENGINE.md](ENGINE.md), раздел 3): иначе лимит релея выглядел бы как блокировка.
     */
    val internal: Boolean
        get() = category == TransportCategory.CANCELLED || code in INTERNAL_CODES

    companion object {
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
    /** Клиент закрыл соединение в пределах около одного RTT после первой порции ответа, ничего больше не отправив. */
    val clientAbandoned: Boolean,
    /** Байты в секунду после первой порции ответа; null, если интервал слишком короткий для измерения. */
    val receiveBytesPerSecond: Long?,
)

/** Итог UDP-ассоциации релея: число датаграмм в обе стороны. */
data class UdpRecord(val port: Int, val outDatagrams: Long, val inDatagrams: Long)

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
