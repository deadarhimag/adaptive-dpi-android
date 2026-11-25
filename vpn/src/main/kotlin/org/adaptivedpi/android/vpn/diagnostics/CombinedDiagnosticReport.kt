package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.vpn.VpnState
import org.adaptivedpi.android.vpn.TransportSnapshot
import java.time.format.DateTimeFormatter

/** Сведения, однозначно определяющие сборку: у нескольких CI APK может быть одна `versionName`. */
data class BuildIdentity(
    val versionCode: Long?,
    val buildNumber: String?,
    val gitRevision: String?,
    val localChanges: Boolean,
)

private val buildTokenPattern = Regex("[A-Za-z0-9._-]{1,40}")

private fun safeBuildToken(value: String?) = value?.takeIf(buildTokenPattern::matches) ?: "неизвестен"

fun combinedDiagnosticReport(
    appVersion: String?,
    androidVersion: String?,
    androidApi: Int,
    deviceModel: String?,
    vpnState: VpnState,
    vpnHistory: VpnEventHistoryState,
    errors: List<DiagnosticEvent>,
    transport: TransportSnapshot? = null,
    transportJournal: TransportJournalView? = null,
    build: BuildIdentity? = null,
    unreadableErrors: Long = 0,
): String = buildString {
    appendLine("Диагностика adaptive-dpi-android")
    appendLine("Версия приложения: ${DiagnosticEvent.safeVersion(appVersion)}")
    if (build != null) {
        val run = if (build.buildNumber == "local") "локальная сборка" else "запуск CI ${safeBuildToken(build.buildNumber)}"
        val changes = if (build.localChanges) ", есть локальные изменения" else ""
        appendLine("Сборка: код версии ${build.versionCode ?: "неизвестен"}, $run, коммит ${safeBuildToken(build.gitRevision)}$changes")
    }
    appendLine("Версия Android: ${DiagnosticEvent.safeVersion(androidVersion)} (API $androidApi)")
    val model = deviceModel.orEmpty().filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        .take(100).trim().ifEmpty { "неизвестна" }
    appendLine("Модель устройства: $model")
    appendLine("Состояние VPN: ${vpnState.name}")
    val engine = when {
        vpnState == VpnState.TransportStarting -> "инициализируется"
        transport?.running == true -> "экспериментальный локальный транспорт запущен"
        transport != null -> "экспериментальный локальный транспорт остановлен"
        else -> "пока не подключён"
    }
    appendLine("Сетевой движок: $engine")
    if (transport != null) {
        appendLine("Снимок транспорта: ${if (transport.finalized) "итоговый" else "предварительный"}.")
        if (transport.applicationMode) {
            appendLine("Маршрутизация: 0.0.0.0/0 для выбранного приложения, DNS физической сети по IPv4. IPv6 заблокирован для выбранного приложения.")
            appendLine("Соединения открыто/закрыто: TCP ${transport.openedTcp}/${transport.closedTcp}, UDP ${transport.openedUdp}/${transport.closedUdp}.")
        } else {
            appendLine("Ограничение маршрутизации: один частный IPv4-адрес, IPv6 заблокирован для пакета VPN.")
        }
        appendLine("Передача данных в обе стороны: ${if (transport.transferred) "зафиксирована" else "ожидается"}")
        appendLine("Пакеты TUN: отправлено ${transport.tunSentPackets}, получено ${transport.tunReceivedPackets}")
        appendLine("Байты прямого тракта: отправлено ${transport.sentBytes}, получено ${transport.receivedBytes}")
        appendLine("Соединения: ${transport.connections}. Операции с ошибками: ${transport.errors}.")
        val journalSession = transportJournal?.session
        if (journalSession?.sessionId == transport.sessionId && journalSession != null && journalSession.total != transport.errors) {
            appendLine("Счётчик транспорта и сводка отказов сняты в разные моменты одного сеанса.")
        }
    }

    transportJournal?.session?.let {
        appendLine()
        appendLine("Сводка транспорта")
        appendLine(it.text())
        appendLine("Журнал транспорта на диске: ${transportJournal.bytes} байт, ${transportJournal.storedRecords} записей.")
    }

    appendLine()
    appendLine("Журнал событий VPN")
    if (vpnHistory.storageError) appendLine("Хранилище журнала недоступно. Список может быть неполным.")
    if (vpnHistory.droppedInformational > 0 || vpnHistory.droppedCritical > 0) {
        appendLine("Пропуски истории: обычные ${vpnHistory.droppedInformational}, критические ${vpnHistory.droppedCritical}.")
    }
    if (vpnHistory.unreadableRecords > 0) {
        appendLine("Повреждённые записи на диске: ${vpnHistory.unreadableRecords}. История неполная.")
    }
    if (vpnHistory.entries.isEmpty()) appendLine("Записей нет.")
    for (event in vpnHistory.entries) {
        appendLine()
        appendLine("${event.time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)} · ${event.type.level.name} · ${event.type.description}")
        appendLine(event.details())
    }

    appendLine()
    appendLine("Журнал ошибок")
    if (unreadableErrors > 0) appendLine("Нечитаемые записи журнала ошибок: $unreadableErrors. Журнал неполон.")
    if (errors.isEmpty()) appendLine("Записей нет.")
    for (event in errors) {
        appendLine()
        append(event.report())
    }
}
