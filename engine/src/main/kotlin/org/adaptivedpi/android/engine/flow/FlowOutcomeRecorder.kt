package org.adaptivedpi.android.engine.flow

import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode

/** Агрегаты исходов за сеанс. Только числа: имён ресурсов, адресов и содержимого нет. */
data class FlowAggregate(
    val tcpFlows: Long,
    val tlsWithName: Long,
    val tlsWithEch: Long,
    val tlsWithoutName: Long,
    val tlsUnparsed: Long,
    val http: Long,
    val otherTcp: Long,
    val helloCount: Long,
    val helloMinLength: Int,
    val helloMaxLength: Int,
    val helloTotalLength: Long,
    val helloRecords1: Long,
    val helloRecords2: Long,
    val helloRecords3Plus: Long,
    val stages: Map<FlowStage, Long>,
    val failuresByCategory: Map<TransportCategory, Long>,
    val failuresByErrno: Map<Int, Long>,
    val internalRefusals: Map<TransportCode, Long>,
    val clientAbandoned: Long,
    val rateFlows: Long,
    val rateMinBytesPerSecond: Long,
    val rateMaxBytesPerSecond: Long,
    /** Ключ - степень двойки: число соединений со скоростью приёма в [2^k, 2^(k+1)) байт в секунду. */
    val rateBuckets: Map<Int, Long>,
    val peakActive: Int,
    val udpAssociations: Long,
    val udpOut: Long,
    val udpIn: Long,
    val udp443Associations: Long,
    val udp443Out: Long,
    val udp443In: Long,
    /** Ассоциации, где ушло не меньше 4 датаграмм и пришло не больше одной (признак «UDP без ответа»). */
    val udpUnanswered: Long,
    val udp443Unanswered: Long,
) {
    fun text(relayLimit: Int?): String = buildString {
        appendLine("Пассивная телеметрия сеанса (без имён и адресов ресурсов)")
        appendLine("TCP-соединений $tcpFlows: TLS с именем $tlsWithName, TLS с ECH $tlsWithEch, TLS без имени $tlsWithoutName, " +
            "TLS без разобранного ClientHello $tlsUnparsed, HTTP $http, прочие $otherTcp.")
        if (helloCount > 0) {
            appendLine("ClientHello $helloCount: размер от $helloMinLength до $helloMaxLength, в среднем ${helloTotalLength / helloCount} байт; " +
                "записей TLS: 1 - $helloRecords1, 2 - $helloRecords2, 3 и больше - $helloRecords3Plus.")
        }
        appendLine("Стадия завершения: " + FlowStage.entries.joinToString(", ") { "${it.name} ${stages[it] ?: 0}" } + ".")
        appendLine("Отказы ключей по категориям: " + counts(failuresByCategory.mapKeys { it.key.name }) + ".")
        appendLine("Отказы по errno: " + counts(failuresByErrno.mapKeys { it.key.toString() }) + ".")
        appendLine("Внутренние отказы релея (не отказы ключей): " + counts(internalRefusals.mapKeys { it.key.name }) + ".")
        appendLine("Клиент бросил соединение сразу после первой порции ответа: $clientAbandoned.")
        append("Скорость приёма: соединений $rateFlows")
        if (rateFlows > 0) {
            append(", от $rateMinBytesPerSecond до $rateMaxBytesPerSecond байт/с, по степеням двойки: ")
            append(counts(rateBuckets.mapKeys { "2^${it.key}" }))
        }
        appendLine(".")
        appendLine("Пик одновременных TCP-соединений: $peakActive" + (relayLimit?.let { " из лимита $it" } ?: "") + ".")
        appendLine("UDP: ассоциаций $udpAssociations, датаграмм исходящих $udpOut, входящих $udpIn, без ответа $udpUnanswered. " +
            "UDP/443: ассоциаций $udp443Associations, исходящих $udp443Out, входящих $udp443In, без ответа $udp443Unanswered.")
    }.trimEnd()

    private fun counts(values: Map<String, Long>) = if (values.isEmpty()) "нет" else values.entries.joinToString(", ") { "${it.key} ${it.value}" }
}

/**
 * Копит исходы в памяти. Вызовы короткие и берут один монитор: они не ждут сети и диска, как требует [FlowObserver].
 */
class FlowOutcomeRecorder : FlowObserver {
    private val lock = Any()
    private var tcpFlows = 0L
    private var tlsWithName = 0L
    private var tlsWithEch = 0L
    private var tlsWithoutName = 0L
    private var tlsUnparsed = 0L
    private var http = 0L
    private var otherTcp = 0L
    private var helloCount = 0L
    private var helloMin = Int.MAX_VALUE
    private var helloMax = 0
    private var helloTotal = 0L
    private var records1 = 0L
    private var records2 = 0L
    private var records3 = 0L
    private val stages = HashMap<FlowStage, Long>()
    private val byCategory = HashMap<TransportCategory, Long>()
    private val byErrno = HashMap<Int, Long>()
    private val internal = HashMap<TransportCode, Long>()
    private var abandoned = 0L
    private var rateFlows = 0L
    private var rateMin = Long.MAX_VALUE
    private var rateMax = 0L
    private val rateBuckets = HashMap<Int, Long>()
    private var peak = 0
    private var udpAssociations = 0L
    private var udpOut = 0L
    private var udpIn = 0L
    private var udp443Associations = 0L
    private var udp443Out = 0L
    private var udp443In = 0L
    private var udpUnanswered = 0L
    private var udp443Unanswered = 0L

    override fun onOutcome(outcome: FlowRecord) {
        synchronized(lock) {
            tcpFlows++
            when (outcome.kind) {
                FlowKind.TLS -> countTls(outcome.hello)
                FlowKind.HTTP -> http++
                FlowKind.OTHER -> otherTcp++
            }
            stages.merge(outcome.stage, 1, Long::plus)
            outcome.failure?.let { failure ->
                if (failure.internal) {
                    internal.merge(failure.code, 1, Long::plus)
                } else {
                    byCategory.merge(failure.category, 1, Long::plus)
                    failure.errno?.let { byErrno.merge(it, 1, Long::plus) }
                }
            }
            if (outcome.clientAbandoned) abandoned++
            outcome.receiveBytesPerSecond?.takeIf { it > 0 }?.let { rate ->
                rateFlows++
                rateMin = minOf(rateMin, rate)
                rateMax = maxOf(rateMax, rate)
                rateBuckets.merge(63 - java.lang.Long.numberOfLeadingZeros(rate), 1, Long::plus)
            }
        }
    }

    private fun countTls(hello: FlowHello?) {
        when {
            hello == null -> tlsUnparsed++
            hello.ech -> tlsWithEch++
            hello.hasName -> tlsWithName++
            else -> tlsWithoutName++
        }
        if (hello == null) return
        helloCount++
        helloMin = minOf(helloMin, hello.length)
        helloMax = maxOf(helloMax, hello.length)
        helloTotal += hello.length
        when {
            hello.records <= 1 -> records1++
            hello.records == 2 -> records2++
            else -> records3++
        }
    }

    override fun onUdp(association: UdpRecord) {
        synchronized(lock) {
            val unanswered = association.outDatagrams >= UNANSWERED_OUT && association.inDatagrams <= 1
            udpAssociations++
            udpOut += association.outDatagrams
            udpIn += association.inDatagrams
            if (unanswered) udpUnanswered++
            if (association.port == 443) {
                udp443Associations++
                udp443Out += association.outDatagrams
                udp443In += association.inDatagrams
                if (unanswered) udp443Unanswered++
            }
        }
    }

    override fun onInternalRefusal(code: TransportCode) {
        synchronized(lock) { internal.merge(code, 1, Long::plus) }
    }

    override fun onActive(active: Int) {
        synchronized(lock) { if (active > peak) peak = active }
    }

    fun snapshot(): FlowAggregate = synchronized(lock) {
        FlowAggregate(
            tcpFlows, tlsWithName, tlsWithEch, tlsWithoutName, tlsUnparsed, http, otherTcp,
            helloCount, if (helloCount == 0L) 0 else helloMin, helloMax, helloTotal, records1, records2, records3,
            stages.toMap(), byCategory.toMap(), byErrno.toMap(), internal.toMap(), abandoned,
            rateFlows, if (rateFlows == 0L) 0 else rateMin, rateMax, rateBuckets.toSortedMap(),
            peak, udpAssociations, udpOut, udpIn, udp443Associations, udp443Out, udp443In, udpUnanswered, udp443Unanswered,
        )
    }

    private companion object {
        const val UNANSWERED_OUT = 4L
    }
}
