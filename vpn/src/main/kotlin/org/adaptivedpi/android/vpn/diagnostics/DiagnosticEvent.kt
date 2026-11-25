package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.vpn.VpnState
import java.time.Instant
import java.util.UUID

enum class DiagnosticComponent(val label: String) {
    Application("Приложение"), Vpn("Служба VPN"), Engine("Сетевой движок"), Export("Экспорт")
}

enum class DiagnosticStage(val label: String) {
    Consent("Системное согласие"), Start("Запуск"), Foreground("Foreground-режим"),
    Tunnel("Создание TUN"), Running("Работа"), Stop("Остановка"), Recovery("Восстановление"),
    Save("Запись в выбранный файл"), Share("Подготовка файла для отправки")
}

enum class DiagnosticCode(
    val component: DiagnosticComponent,
    val stage: DiagnosticStage,
    val description: String,
    val detail: String,
    val stopsVpn: Boolean = false,
) {
    ConsentFailed(DiagnosticComponent.Vpn, DiagnosticStage.Consent, "Не удалось запросить согласие VPN.",
        "Запрос системного согласия завершился ошибкой."),
    VpnStartFailed(DiagnosticComponent.Vpn, DiagnosticStage.Start, "Не удалось запустить VPN-службу.",
        "Android не подтвердил запуск службы.", true),
    ForegroundFailed(DiagnosticComponent.Vpn, DiagnosticStage.Foreground, "Не удалось перевести VPN в foreground-режим.",
        "Запуск проверочного сеанса отменён.", true),
    TunnelFailed(DiagnosticComponent.Vpn, DiagnosticStage.Tunnel, "Не удалось создать интерфейс VPN.",
        "Создание TUN завершилось исключением.", true),
    TransportApplicationUnavailable(DiagnosticComponent.Vpn, DiagnosticStage.Tunnel, "Выбранное приложение недоступно для VPN.",
        "Проверьте имя пакета и наличие приложения в текущем профиле Android.", true),
    TransportIpv4Unavailable(DiagnosticComponent.Vpn, DiagnosticStage.Tunnel, "Для транспорта нужна сеть с IPv4 и DNS по IPv4.",
        "На выбранном подключении не удалось получить адрес IPv4 и настройки DNS по IPv4.", true),
    VpnCleanupFailed(DiagnosticComponent.Vpn, DiagnosticStage.Stop, "Возникла ошибка при освобождении ресурсов VPN.",
        "Проверьте итог остановки и состояние отдельных этапов очистки."),
    VpnCleanupDelayed(DiagnosticComponent.Vpn, DiagnosticStage.Stop, "Остановка VPN заняла больше ожидаемого.",
        "В отчёте указан этап, на котором остановка заняла больше времени."),
    VpnUnexpectedStop(DiagnosticComponent.Vpn, DiagnosticStage.Stop, "Служба VPN завершилась неожиданно.",
        "Служба уничтожена без команды штатной остановки.", true),
    VpnInterrupted(DiagnosticComponent.Vpn, DiagnosticStage.Recovery, "Предыдущий сеанс VPN прервался.",
        "Незавершённый сеанс обнаружен при запуске. Время события — время обнаружения.", true),
    EngineFailed(DiagnosticComponent.Engine, DiagnosticStage.Running, "Сетевой движок сообщил о критической ошибке.",
        "Сеанс закрывается после явного сигнала отказа.", true),
    EngineHung(DiagnosticComponent.Engine, DiagnosticStage.Running, "Сетевой движок перестал отвечать.",
        "Истёк срок сигнала рабочего цикла, независимого от доступности сети.", true),
    AppCrash(DiagnosticComponent.Application, DiagnosticStage.Running, "Приложение завершилось с ошибкой.",
        "Зафиксировано необработанное исключение процесса."),
    ProcessExitNative(DiagnosticComponent.Application, DiagnosticStage.Running, "Процесс приложения завершился из-за сбоя в нативном коде.",
        "Причина взята из истории завершений процесса Android. Время события - время завершения процесса."),
    ProcessExitAnr(DiagnosticComponent.Application, DiagnosticStage.Running, "Процесс приложения завершён из-за отсутствия ответа.",
        "Причина взята из истории завершений процесса Android. Время события - время завершения процесса."),
    ProcessExitLowMemory(DiagnosticComponent.Application, DiagnosticStage.Running, "Система завершила процесс приложения из-за нехватки памяти.",
        "Причина взята из истории завершений процесса Android. Время события - время завершения процесса."),
    ProcessExitResources(DiagnosticComponent.Application, DiagnosticStage.Running, "Система завершила процесс приложения из-за чрезмерного расхода ресурсов.",
        "Причина взята из истории завершений процесса Android. Время события - время завершения процесса."),
    ProcessExitSignaled(DiagnosticComponent.Application, DiagnosticStage.Running, "Процесс приложения завершён сигналом.",
        "Причина взята из истории завершений процесса Android. Время события - время завершения процесса."),
    NotificationSettingsFailed(DiagnosticComponent.Application, DiagnosticStage.Consent,
        "Не удалось открыть системные настройки уведомлений.",
        "Android не открыл экран разрешения или настроек уведомлений."),
    SaveFailed(DiagnosticComponent.Export, DiagnosticStage.Save, "Не удалось сохранить отчёт.",
        "Поставщик документов не смог принять файл."),
    ShareFailed(DiagnosticComponent.Export, DiagnosticStage.Share, "Не удалось подготовить отчёт для отправки.",
        "Файл или меню отправки оказались недоступны."),
}

enum class KnownFlag { Yes, No, Unknown }

data class VpnSnapshot(
    val state: VpnState,
    val alwaysOn: KnownFlag = KnownFlag.Unknown,
    val lockdown: KnownFlag = KnownFlag.Unknown,
) {
    val mayHaveActiveVpn: Boolean
        get() = state.canStop

    val connectionNotice: String
        get() = if (alwaysOn == KnownFlag.Yes && lockdown == KnownFlag.Yes) {
            "Включён постоянный VPN с блокировкой. После остановки интернет может остаться недоступным."
        } else {
            "Интернет может работать напрямую или быть заблокирован системными настройками VPN."
        }
}

internal enum class ExceptionKind {
    SecurityException, IOException, IllegalArgumentException, IllegalStateException,
    NullPointerException, OutOfMemoryError, Other;

    companion object {
        fun from(error: Throwable): ExceptionKind = when (error) {
            is java.lang.SecurityException -> SecurityException
            is java.io.IOException -> IOException
            is java.lang.IllegalArgumentException -> IllegalArgumentException
            is java.lang.IllegalStateException -> IllegalStateException
            is java.lang.NullPointerException -> NullPointerException
            is java.lang.OutOfMemoryError -> OutOfMemoryError
            else -> Other
        }
    }
}

// Имена кадров берутся из этого списка. Сообщение исключения, имя файла и сырые кадры не экспортируются.
internal enum class StackSite(val symbol: String) {
    VpnStart("org.adaptivedpi.android.vpn.AdaptiveVpnService.startTestSession"),
    VpnStop("org.adaptivedpi.android.vpn.AdaptiveVpnService.finishSession"),
    VpnRelease("org.adaptivedpi.android.vpn.AdaptiveVpnService.releaseResources"),
    VpnRequest("org.adaptivedpi.android.vpn.VpnController.requestStart"),
    VpnConsent("org.adaptivedpi.android.vpn.VpnController.completeConsent"),
    ActivityCreate("org.adaptivedpi.android.MainActivity.onCreate"),
    ActivityStartVpn("org.adaptivedpi.android.MainActivity.startVpn"),
    Hidden("кадр скрыт"),
}

internal data class SafeFrame(val site: StackSite, val line: Int)

internal data class DiagnosticAge(val boot: String, val elapsedMillis: Long)

@ConsistentCopyVisibility
data class DiagnosticEvent internal constructor(
    val id: UUID,
    val timeMillis: Long,
    val code: DiagnosticCode,
    val appVersion: String,
    val androidApi: Int,
    val androidVersion: String,
    val vpn: VpnSnapshot,
    internal val exceptionKind: ExceptionKind?,
    internal val frames: List<SafeFrame>,
    val acknowledged: Boolean = false,
    internal val notified: Boolean = false,
    internal val cleanup: VpnCleanupDetails? = null,
    internal val age: DiagnosticAge? = null,
) {
    val time: Instant get() = Instant.ofEpochMilli(timeMillis)
    val vpnStopped: Boolean get() = code.stopsVpn || (code == DiagnosticCode.AppCrash && vpn.mayHaveActiveVpn)
    val requiresNotification: Boolean get() = vpnStopped || code == DiagnosticCode.AppCrash

    fun report(): String = buildString {
        appendLine("Диагностический отчёт adaptive-dpi-android")
        appendLine("Дата и время (UTC): $time")
        appendLine("Идентификатор: $id")
        appendLine("Код: ${code.name}")
        appendLine("Описание: ${code.description}")
        appendLine("Компонент: ${code.component.label}")
        appendLine("Этап: ${code.stage.label}")
        appendLine("Подробности: ${code.detail}")
        appendLine("Версия приложения: $appVersion")
        appendLine("Версия Android: $androidVersion (API $androidApi)")
        appendLine("Состояние VPN: ${vpn.state.name}")
        appendLine("Постоянный VPN: ${vpn.alwaysOn.label()}")
        appendLine("Блокировка без VPN: ${vpn.lockdown.label()}")
        if (vpnStopped) appendLine(connectionNotice())
        cleanup?.let { appendLine(it.text()) }
        exceptionKind?.let {
            appendLine("Исключение: ${it.name}")
            if (frames.isNotEmpty()) {
                appendLine("Стек:")
                frames.forEach { frame -> appendLine("  ${frame.site.symbol.substringAfterLast(".android.")}:${frame.line}") }
            }
        }
    }

    fun connectionNotice() = vpn.connectionNotice

    companion object {
        internal const val MAX_FRAMES = 12
        private val versionPattern = Regex("[0-9]{1,6}(\\.[0-9]{1,6}){0,3}")

        internal fun safeVersion(version: String?) =
            version?.takeIf(versionPattern::matches) ?: "неизвестна"

        internal fun create(
            code: DiagnosticCode,
            version: String?,
            androidApi: Int,
            vpn: VpnSnapshot,
            error: Throwable? = null,
            id: UUID = UUID.randomUUID(),
            timeMillis: Long = System.currentTimeMillis(),
            androidVersion: String? = null,
            cleanup: VpnCleanupDetails? = null,
        ): DiagnosticEvent {
            val frames = error?.let { cause ->
                runCatching {
                    cause.stackTrace.take(MAX_FRAMES).map { frame ->
                        val site = StackSite.entries.firstOrNull {
                            it.symbol == "${frame.className}.${frame.methodName}"
                        } ?: StackSite.Hidden
                        SafeFrame(site, frame.lineNumber)
                    }
                }.getOrDefault(emptyList())
            }.orEmpty()
            return DiagnosticEvent(
                id, timeMillis, code, safeVersion(version), androidApi, safeVersion(androidVersion), vpn,
                error?.let(ExceptionKind::from), frames, cleanup = cleanup,
            )
        }
    }
}

private fun KnownFlag.label(): String = when (this) {
    KnownFlag.Yes -> "да"
    KnownFlag.No -> "нет"
    KnownFlag.Unknown -> "неизвестно"
}
