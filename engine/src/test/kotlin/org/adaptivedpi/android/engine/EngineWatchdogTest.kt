package org.adaptivedpi.android.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineWatchdogTest {
    private val watchdog = EngineWatchdog(startedAtMillis = 100, timeoutMillis = 1_000)

    @Test
    fun aRespondingEngineIsNotFailedWhenInternetIsUnavailable() {
        assertEquals(EngineHealth.NetworkUnavailable,
            watchdog.evaluate(EngineSignal.Heartbeat(990), EngineNetwork.Unavailable, 1_000))
        assertEquals(EngineHealth.NetworkUnknown,
            watchdog.evaluate(EngineSignal.Heartbeat(990), EngineNetwork.Unknown, 1_000))
        assertEquals(EngineHealth.Responsive,
            watchdog.evaluate(EngineSignal.Heartbeat(990), EngineNetwork.Validated, 1_000))
    }

    @Test
    fun startupAllowsGracePeriodThenDetectsAnAbsentHeartbeat() {
        assertEquals(EngineHealth.Starting, watchdog.evaluate(EngineSignal.Starting, EngineNetwork.Unknown, 1_099))
        assertEquals(EngineHealth.Hung, watchdog.evaluate(EngineSignal.Starting, EngineNetwork.Unknown, 1_100))
    }

    @Test
    fun aStoppedWorkerIsHungEvenWhenTheDeviceHasNoNetwork() {
        assertEquals(EngineHealth.Hung,
            watchdog.evaluate(EngineSignal.Heartbeat(500), EngineNetwork.Unavailable, 1_500))
    }

    @Test
    fun aFailureSignalStopsWithoutWaitingForTheDeadline() {
        assertEquals(EngineHealth.Failed, watchdog.evaluate(EngineSignal.Failed, EngineNetwork.Validated, 101))
    }
}
