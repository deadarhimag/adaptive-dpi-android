package org.adaptivedpi.android.engine

enum class EngineNetwork { Validated, Unavailable, Unknown }

sealed interface EngineSignal {
    data object Starting : EngineSignal
    data object Failed : EngineSignal

    /** Сигнал рабочего цикла в шкале SystemClock.uptimeMillis(), независимо от трафика и ответов сети. */
    data class Heartbeat(val elapsedMillis: Long) : EngineSignal
}

/** Мгновенный снимок из отдельного потока. Нельзя ждать ввода-вывода или блокировки движка. */
fun interface EngineHealthSource {
    fun read(): EngineSignal
}

enum class EngineHealth { Starting, Responsive, NetworkUnavailable, NetworkUnknown, Failed, Hung }

class EngineWatchdog(
    private val startedAtMillis: Long,
    private val timeoutMillis: Long,
) {
    init {
        require(timeoutMillis > 0)
    }

    fun evaluate(signal: EngineSignal, network: EngineNetwork, nowMillis: Long): EngineHealth {
        if (signal == EngineSignal.Failed) return EngineHealth.Failed
        val heartbeat = (signal as? EngineSignal.Heartbeat)?.elapsedMillis
        val lastProgress = heartbeat?.takeIf { it in startedAtMillis..nowMillis } ?: startedAtMillis
        if (nowMillis - lastProgress >= timeoutMillis) return EngineHealth.Hung
        if (heartbeat == null || heartbeat != lastProgress) return EngineHealth.Starting
        return when (network) {
            EngineNetwork.Validated -> EngineHealth.Responsive
            EngineNetwork.Unavailable -> EngineHealth.NetworkUnavailable
            EngineNetwork.Unknown -> EngineHealth.NetworkUnknown
        }
    }
}

/** Закрывает TUN независимо от рабочего цикла, затем инициирует остановку движка. Вызовы идемпотентны. */
interface EngineEmergencyStop {
    fun closeTunnel()
    fun requestStop()
}
