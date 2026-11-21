package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.vpn.VpnState
import java.time.format.DateTimeFormatter

fun combinedDiagnosticReport(
    appVersion: String?,
    androidVersion: String?,
    androidApi: Int,
    deviceModel: String?,
    vpnState: VpnState,
    vpnHistory: VpnEventHistoryState,
    errors: List<DiagnosticEvent>,
): String = buildString {
    appendLine("Диагностика adaptive-dpi-android")
    appendLine("Версия приложения: ${DiagnosticEvent.safeVersion(appVersion)}")
    appendLine("Версия Android: ${DiagnosticEvent.safeVersion(androidVersion)} (API $androidApi)")
    val model = deviceModel.orEmpty().filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        .take(100).trim().ifEmpty { "неизвестна" }
    appendLine("Модель устройства: $model")
    appendLine("Состояние VPN: ${vpnState.name}")
    val engine = if (vpnState == VpnState.EngineRunning) "работа подтверждена" else "пока не подключён"
    appendLine("Сетевой движок: $engine")

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
