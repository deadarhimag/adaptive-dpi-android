package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportDisposition
import org.adaptivedpi.android.engine.TransportFailure
import org.adaptivedpi.android.vpn.VpnState
import java.time.Instant
import java.util.UUID

internal enum class TransportDropSource(val label: String) {
    NATIVE("буфер HEV"), BUSY("занята очередь Kotlin"), LIMIT("лимит ключей Kotlin"), STORAGE("хранилище"),
}

@ConsistentCopyVisibility
data class TransportLogEntry internal constructor(
    val sessionId: UUID,
    val firstTime: Instant,
    val lastTime: Instant,
    val vpnState: VpnState,
    val failure: TransportFailure,
    val repeats: Long,
) {
    fun text(): String = with(failure) {
        val label = if (errno == null && codeLabel != "unknown") "$codeLabel, errno=unknown" else codeLabel
        "$firstTime — $lastTime · ${category.level} · ${site.subsystem} / ${site.operation} / $label · " +
            "${category.title} (${category.name}, ${category.disposition}) · Повторений $repeats · " +
            "${site.protocol}/${site.direction} · VPN ${vpnState.name} · Сеанс $sessionId"
    }
}

@ConsistentCopyVisibility
data class TransportSessionSummary internal constructor(
    val sessionId: UUID,
    val byCategory: Map<TransportCategory, Long>,
    val merged: Long,
    val dropped: Long,
    val lastFailure: Instant?,
    val finished: Boolean,
    internal val dropsBySource: Map<TransportDropSource, Long> = emptyMap(),
    /** Агрегаты пассивной телеметрии сеанса (только числа), если сеанс их сохранил. */
    internal val flowText: String? = null,
) {
    val total get() = byCategory.values.sum()
    val expected get() = count(TransportDisposition.EXPECTED)
    val unexpected get() = count(TransportDisposition.UNEXPECTED)
    val unknown get() = count(TransportDisposition.UNKNOWN)

    private fun count(disposition: TransportDisposition) = byCategory.entries.sumOf { (category, count) ->
        if (category.disposition == disposition) count else 0
    }

    fun text(): String = buildString {
        appendLine("Сеанс $sessionId (${if (finished) "завершён" else "текущий"})")
        appendLine("Всего отказов операций $total. Ожидаемые $expected, неожиданные $unexpected, неизвестные $unknown.")
        appendLine("Объединено повторов $merged. Пропущено событий $dropped.")
        if (dropsBySource.isNotEmpty()) {
            appendLine("Пропуски по источникам: ${dropsBySource.entries.joinToString { "${it.key.label} ${it.value}" }}.")
        }
        appendLine("Последний отказ ${lastFailure ?: "—"}")
        for ((category, count) in byCategory) appendLine("${category.title} (${category.name}) $count")
        flowText?.let {
            appendLine()
            appendLine(it)
        }
    }.trimEnd()
}

@ConsistentCopyVisibility
data class TransportJournalView internal constructor(
    val entries: List<TransportLogEntry> = emptyList(),
    val session: TransportSessionSummary? = null,
    val bytes: Long = 0,
    val storedRecords: Int = 0,
    val ready: Boolean = false,
    val storageError: Boolean = false,
)
