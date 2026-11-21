package org.adaptivedpi.android.vpn

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.adaptivedpi.android.engine.EngineHealth
import org.adaptivedpi.android.engine.EngineHealthSource
import org.adaptivedpi.android.engine.EngineNetwork
import org.adaptivedpi.android.engine.EngineWatchdog
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode

internal fun monitorEngine(
    source: EngineHealthSource,
    network: () -> EngineNetwork,
    onConfirmed: () -> Unit,
    onCriticalFailure: (DiagnosticCode, Throwable?) -> Unit,
): Job {
    val watchdog = EngineWatchdog(SystemClock.uptimeMillis(), timeoutMillis = 15_000)
    return CoroutineScope(Dispatchers.Default).launch {
        var confirmed = false
        while (isActive) {
            delay(1_000)
            val signal = try {
                source.read()
            } catch (error: RuntimeException) {
                onCriticalFailure(DiagnosticCode.EngineFailed, error)
                return@launch
            }
            val availableNetwork = runCatching(network).getOrDefault(EngineNetwork.Unknown)
            when (watchdog.evaluate(signal, availableNetwork, SystemClock.uptimeMillis())) {
                EngineHealth.Failed -> {
                    onCriticalFailure(DiagnosticCode.EngineFailed, null)
                    return@launch
                }
                EngineHealth.Hung -> {
                    onCriticalFailure(DiagnosticCode.EngineHung, null)
                    return@launch
                }
                EngineHealth.Responsive -> if (!confirmed) {
                    confirmed = true
                    onConfirmed()
                }
                else -> Unit
            }
        }
    }
}
