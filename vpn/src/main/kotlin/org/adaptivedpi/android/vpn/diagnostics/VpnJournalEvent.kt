package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.vpn.VpnState
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class VpnEventLevel { INFO, WARNING, ERROR }

enum class VpnEventType(val level: VpnEventLevel, val description: String) {
    PermissionRequested(VpnEventLevel.INFO, "Запрошено системное разрешение VPN."),
    PermissionGranted(VpnEventLevel.INFO, "Android выдал разрешение VPN."),
    PermissionDenied(VpnEventLevel.WARNING, "Разрешение VPN не выдано или запрос отменён."),
    PermissionAbandoned(VpnEventLevel.INFO, "Запрос разрешения закрыт приложением."),
    PermissionAlreadyGranted(VpnEventLevel.INFO, "Разрешение VPN уже выдано."),
    PermissionUnavailable(VpnEventLevel.WARNING, "Разрешение VPN недоступно при запуске."),
    PermissionError(VpnEventLevel.ERROR, "Не удалось запросить или проверить разрешение VPN."),
    StartRequested(VpnEventLevel.INFO, "Запускается проверочный VPN."),
    Started(VpnEventLevel.INFO, "Проверочный VPN запущен. Интерфейс TUN создан."),
    StopFromApp(VpnEventLevel.INFO, "Запрошена остановка VPN из приложения."),
    StopFromNotification(VpnEventLevel.INFO, "Запрошена остановка VPN из уведомления."),
    Stopped(VpnEventLevel.INFO, "Проверочный сеанс VPN завершён."),
    SystemRevoked(VpnEventLevel.WARNING, "Android отозвал разрешение VPN."),
    StartError(VpnEventLevel.ERROR, "Не удалось запустить проверочный VPN."),
    StopError(VpnEventLevel.ERROR, "Возникла ошибка при остановке VPN."),
    UnexpectedStop(VpnEventLevel.ERROR, "Сеанс VPN завершился с ошибкой."),
    ProcessCrash(VpnEventLevel.ERROR, "Обнаружена запись об аварийном завершении приложения."),
    InterruptedSession(VpnEventLevel.WARNING, "Обнаружен незавершённый сеанс предыдущего процесса."),
    ActivityRestored(VpnEventLevel.INFO, "Состояние экрана восстановлено при пересоздании Activity."),
    ProcessStateRestored(VpnEventLevel.INFO, "Сохранённое состояние экрана восстановлено в новом процессе."),
}

@ConsistentCopyVisibility
data class VpnJournalEvent internal constructor(
    val id: UUID,
    val time: OffsetDateTime,
    val type: VpnEventType,
    val sessionId: UUID?,
    val code: DiagnosticCode?,
    val state: VpnState?,
) {
    fun details(): String = buildString {
        appendLine("Тип: ${type.name}")
        appendLine("Событие: $id")
        sessionId?.let { appendLine("Сеанс: $it") }
        code?.let { appendLine("Код: ${it.name}. ${it.detail}") }
        state?.let { appendLine("Состояние VPN: ${it.name}") }
    }.trimEnd()

    internal companion object {
        fun create(
            type: VpnEventType,
            sessionId: UUID? = null,
            code: DiagnosticCode? = null,
            state: VpnState? = null,
            id: UUID = UUID.randomUUID(),
            time: OffsetDateTime = OffsetDateTime.now(),
        ) = VpnJournalEvent(id, time.truncatedTo(ChronoUnit.MILLIS), type, sessionId, code, state)
    }
}
