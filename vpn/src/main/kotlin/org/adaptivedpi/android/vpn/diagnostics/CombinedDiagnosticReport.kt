package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.vpn.VpnState
import org.adaptivedpi.android.vpn.TransportSnapshot
import java.time.format.DateTimeFormatter

fun combinedDiagnosticReport(
    appVersion: String?,
    androidVersion: String?,
    androidApi: Int,
    deviceModel: String?,
    vpnState: VpnState,
    vpnHistory: VpnEventHistoryState,
    errors: List<DiagnosticEvent>,
    transport: TransportSnapshot? = null,
): String = buildString {
    appendLine("Диагностика adaptive-dpi-android")
    appendLine("Версия приложения: ${DiagnosticEvent.safeVersion(appVersion)}")
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
        appendLine("Ограничение маршрутизации: один частный IPv4-адрес, IPv6 заблокирован для пакета VPN.")
        appendLine("Передача данных в обе стороны: ${if (transport.transferred) "зафиксирована" else "ожидается"}")
        appendLine("Пакеты TUN: отправлено ${transport.tunSentPackets}, получено ${transport.tunReceivedPackets}")
        appendLine("Байты прямого тракта: отправлено ${transport.sentBytes}, получено ${transport.receivedBytes}")
        appendLine("Соединения: ${transport.connections}. Операции с ошибками: ${transport.errors}.")
    }

    appendLine()
    appendLine("Журнал событий VPN")
    if (vpnHistory.storageError) appendLine("Хранилище журнала недоступно. Список может быть неполным.")
    if (vpnHistory.entries.isEmpty()) appendLine("Записей нет.")
    for (event in vpnHistory.entries) {
        appendLine()
        appendLine("${event.time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)} · ${event.type.level.name} · ${event.type.description}")
        appendLine(event.details())
    }

    appendLine()
    appendLine("Журнал ошибок")
    if (errors.isEmpty()) appendLine("Записей нет.")
    for (event in errors) {
        appendLine()
        append(event.report())
    }
}
