package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.engine.RelayShutdownStatus
import org.adaptivedpi.android.engine.SocketCloseSummary
import java.util.UUID

internal enum class CleanupStep(val title: String) {
    NativeStop("сигнал остановки HEV"), NativeRelease("освобождение сеанса HEV"),
    RelayClose("закрытие релея"), RelayWait("завершение задач релея"), TunClose("закрытие TUN"),
    NetworkCallback("отключение наблюдения за сетью"), TransportSnapshot("получение счётчиков транспорта"),
    EngineMonitor("завершение наблюдения за движком"), EngineTunnel("закрытие интерфейса движка"),
    EngineStop("остановка движка"), ForegroundNotification("закрытие уведомления VPN"),
    ActiveMarker("запись состояния сеанса"), ServiceStop("завершение службы Android"),
    StartupDrain("завершение операции запуска"), NativeExit("выход рабочего цикла HEV"),
}

internal data class CleanupIssue(val step: CleanupStep, val exception: ExceptionKind)

internal data class VpnCleanupDetails(
    val session: UUID?,
    val elapsedMillis: Long,
    val tunClosed: Boolean?,
    val nativeReleased: Boolean?,
    val relayAtDeadline: Int?,
    val relay: RelayShutdownStatus?,
    val activeConnections: Int?,
    val pendingConnections: Int?,
    val issues: List<CleanupIssue>,
    val sockets: SocketCloseSummary? = null,
    val waitingFor: CleanupStep? = null,
    val stepMillis: Long? = null,
    val slowSteps: Map<CleanupStep, Long> = emptyMap(),
) {
    val incomplete get() = tunClosed == false || nativeReleased == false || relay?.terminated == false ||
        sockets?.confirmed == false || waitingFor != null ||
        (activeConnections ?: 0) > 0 || (pendingConnections ?: 0) > 0 ||
        issues.any { it.step != CleanupStep.ActiveMarker && it.step != CleanupStep.TransportSnapshot }

    fun text(): String = buildString {
        appendLine("Остановка сеанса: ${session ?: "—"}. Время: $elapsedMillis мс.")
        waitingFor?.let { appendLine("Незавершённый этап: $it (${stepMillis ?: "—"} мс).") }
        slowSteps.forEach { (step, millis) -> appendLine("Длительный этап $step: $millis мс.") }
        appendLine("TUN закрыт: ${tunClosed ?: "—"}. Нативный сеанс освобождён: ${nativeReleased ?: "—"}.")
        appendLine("Соединения: активные ${activeConnections ?: "—"}, ожидающие ${pendingConnections ?: "—"}.")
        if (relayAtDeadline != null) {
            appendLine("Релей после ожидания: ${if (relayAtDeadline == 0) "завершён" else "RELAY_STOP, ${steps(relayAtDeadline)}"}.")
        }
        relay?.let {
            appendLine("Релей при завершении службы: ${steps(it.pendingMask)}.")
            appendLine("Задачи релея (активные/в очереди): запросы ${it.requestsActive}/${it.requestsQueued}, ответы ${it.responsesActive}/${it.responsesQueued}.")
        }
        sockets?.let {
            appendLine("Сокеты: закрыты ${it.closed}, ранее закрыты ${it.alreadyClosed}, ошибки закрытия ${it.failed}, неизвестно ${it.unknown}.")
        }
        issues.forEach { appendLine("Этап ${it.step}: EXCEPTION (${it.exception}).") }
    }.trimEnd()

    fun encode(): String = listOf(
        session, elapsedMillis, tunClosed, nativeReleased, relayAtDeadline, activeConnections, pendingConnections,
        relay?.let { listOf(it.pendingMask, it.requestsActive, it.requestsQueued, it.responsesActive, it.responsesQueued).joinToString(",") },
        issues.joinToString(",") { "${it.step}:${it.exception}" },
        sockets?.let { listOf(it.closed, it.alreadyClosed, it.failed, it.unknown).joinToString(",") }, waitingFor, stepMillis,
        slowSteps.entries.joinToString(",") { "${it.key}:${it.value}" },
    ).joinToString(";") { it?.toString() ?: "-" }

    private fun steps(mask: Int): String = if (mask == 0) "завершён" else buildList {
        if (mask and 1 != 0) add("приём подключений")
        if (mask and 2 != 0) add("пул запросов")
        if (mask and 4 != 0) add("пул ответов")
        if (mask and 8 != 0) add("закрытие сокетов и пулов")
    }.joinToString(", ")

    companion object {
        fun decode(value: String): VpnCleanupDetails {
            val parts = value.split(';').map { it.takeUnless { part -> part == "-" } }
            require(parts.size == 9 || parts.size in 12..13)
            val relay = parts[7]?.split(',')?.map(String::toInt)?.let {
                RelayShutdownStatus(it[0], it[1], it[2], it[3], it[4])
            }
            val issues = parts[8].orEmpty().split(',').filter(String::isNotEmpty).take(CleanupStep.entries.size).map {
                CleanupIssue(CleanupStep.valueOf(it.substringBefore(':')), ExceptionKind.valueOf(it.substringAfter(':')))
            }
            val sockets = parts.getOrNull(9)?.split(',')?.map(String::toLong)?.let {
                require(it.size == 4 && it.all { count -> count >= 0 })
                SocketCloseSummary(it[0], it[1], it[2], it[3])
            }
            return VpnCleanupDetails(
                parts[0]?.let(UUID::fromString), checkNotNull(parts[1]).toLong(), parts[2]?.toBooleanStrict(),
                parts[3]?.toBooleanStrict(), parts[4]?.toInt(), relay, parts[5]?.toInt(), parts[6]?.toInt(), issues,
                sockets, parts.getOrNull(10)?.let(CleanupStep::valueOf), parts.getOrNull(11)?.toLong(),
                parts.getOrNull(12).orEmpty().split(',').filter(String::isNotEmpty).take(CleanupStep.entries.size).associate {
                    CleanupStep.valueOf(it.substringBefore(':')) to it.substringAfter(':').toLong()
                },
            )
        }
    }
}
