package org.adaptivedpi.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VpnShutdownTest {
    @Test
    fun releasingTunDuringTheJournalGracePeriodDoesNotDelayManualStop() {
        val steps = mutableListOf<String>()
        val shutdown = VpnShutdown({ steps += "tun" }, { steps += "service" })
        shutdown.releaseNow()
        assertEquals(listOf("tun"), steps)

        shutdown.stop()
        shutdown.stop()
        assertEquals(listOf("tun", "service"), steps)
    }

    @Test
    fun controlledFailureReleasesResourcesBeforeReportingAndStopsOnlyOnce() {
        val steps = mutableListOf<String>()
        val shutdown = VpnShutdown(
            release = { steps += listOf("tun", "engine", "foreground") },
            stopService = { steps += "service" },
        )

        shutdown.stop { steps += "report" }
        shutdown.stop { error("Повторная остановка не нужна") }

        assertTrue(shutdown.started)
        assertEquals(listOf("tun", "engine", "foreground", "service", "report"), steps)
    }

    @Test
    fun oneFailedReleaseDoesNotPreventOtherCleanupStepsOrReporting() {
        val steps = mutableListOf<String>()
        val shutdown = VpnShutdown(
            release = {
                releaseVpnResources(
                    { steps += "cleanup error" },
                    { throw IOException("Тестовый отказ") },
                    { steps += "foreground" },
                    { steps += "marker" },
                )
            },
            stopService = { steps += "service" },
        )

        shutdown.stop { steps += "report" }
        assertEquals(listOf("foreground", "marker", "cleanup error", "service", "report"), steps)
    }

    @Test
    fun criticalStopMakesManualRestartAvailableAfterTheOldServiceEnds() {
        val lifecycle = VpnLifecycle()
        assertTrue(lifecycle.beginConsent("running"))
        lifecycle.update("running", VpnEvent.StartRequested)
        assertTrue(lifecycle.attachService("running") {})
        lifecycle.update("running", VpnEvent.ServiceStarted)

        lifecycle.serviceEnded("running", VpnEvent.CriticalFailure)
        assertEquals(VpnState.Failed, lifecycle.state.value)
        assertTrue(lifecycle.beginConsent("manual"))
        lifecycle.update("manual", VpnEvent.StartRequested)
        assertEquals(VpnState.Starting, lifecycle.state.value)
    }
}
