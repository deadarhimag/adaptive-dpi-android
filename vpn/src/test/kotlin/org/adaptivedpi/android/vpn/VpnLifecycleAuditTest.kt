package org.adaptivedpi.android.vpn

import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.VpnEventType
import org.adaptivedpi.android.vpn.diagnostics.VpnJournalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class VpnLifecycleAuditTest {
    @Test
    fun delayRetainsOwnerUntilActualCompletionAndRejectsStaleCallbacks() {
        val lifecycle = VpnLifecycle()
        var stops = 0
        assertTrue(lifecycle.beginConsent("previous"))
        lifecycle.update("previous", VpnEvent.StartRequested)
        assertTrue(lifecycle.attachService("previous") { stops++ })
        lifecycle.stop()
        lifecycle.cleanupDelayed("previous", "HEV")
        lifecycle.stop()
        assertEquals(1, stops)
        assertEquals(VpnState.Stopping, lifecycle.state.value)
        assertEquals("HEV", lifecycle.cleanupDelay.value)
        assertFalse(lifecycle.beginConsent("conflicting"))
        lifecycle.serviceEnded("unrelated", VpnEvent.ServiceDestroyed)
        assertEquals(VpnState.Stopping, lifecycle.state.value)

        lifecycle.serviceEnded("previous", VpnEvent.ServiceDestroyed)
        assertFalse(lifecycle.resourcesBlocked.value)
        assertEquals(VpnState.Idle, lifecycle.state.value)
        assertEquals(null, lifecycle.cleanupDelay.value)
        assertTrue(lifecycle.beginConsent("new"))
        lifecycle.cleanupDelayed("previous", "старый callback")
        lifecycle.serviceEnded("previous", VpnEvent.CleanupFailed)
        assertEquals(VpnState.AwaitingConsent, lifecycle.state.value)
        assertEquals(null, lifecycle.cleanupDelay.value)
    }

    @Test
    fun serviceDestructionCannotClearUnknownResourceOwnership() {
        val lifecycle = VpnLifecycle()
        assertTrue(lifecycle.beginConsent("session"))
        lifecycle.update("session", VpnEvent.StartRequested)
        assertTrue(lifecycle.attachService("session") {})
        lifecycle.stop()
        lifecycle.serviceEnded("session", VpnEvent.CleanupFailed, released = false)
        assertEquals(VpnState.Failed, lifecycle.state.value)
        assertTrue(lifecycle.resourcesBlocked.value)
        assertFalse(lifecycle.beginConsent("another"))
    }

    @Test
    fun oneHundredCyclesIsolateLateCommandsAcrossStopRevokeAndFailures() {
        val events = mutableListOf<VpnJournalEvent>()
        val lifecycle = VpnLifecycle(events::add)
        var previous: String? = null
        repeat(100) { cycle ->
            val id = UUID(0, cycle.toLong() + 1).toString()
            var stops = 0
            assertTrue(lifecycle.beginConsent(id))
            assertFalse(lifecycle.beginConsent("double-start"))
            previous?.let {
                lifecycle.stop(it, fromNotification = true)
                lifecycle.update(it, VpnEvent.EngineConfirmed)
                lifecycle.serviceEnded(it, VpnEvent.CleanupFailed, DiagnosticCode.VpnCleanupFailed)
            }
            assertTrue(lifecycle.isAwaitingConsent(id))
            lifecycle.update(id, VpnEvent.StartRequested)
            assertEquals(VpnState.Starting, lifecycle.state.value)
            if (cycle % 5 == 1) lifecycle.stop()
            assertTrue(lifecycle.attachService(id) { stops++ })
            assertFalse(lifecycle.attachService(id) { error("Двойной владелец сеанса") })

            val terminal = when (cycle % 5) {
                0, 2, 3 -> {
                    lifecycle.update(id, VpnEvent.TransportTunCreated)
                    assertEquals(VpnState.TransportStarting, lifecycle.state.value)
                    lifecycle.update(id, VpnEvent.EngineConfirmed)
                    assertEquals(VpnState.EngineRunning, lifecycle.state.value)
                    lifecycle.activityRestored(sameProcess = true)
                    assertEquals(VpnState.EngineRunning, lifecycle.state.value)
                    lifecycle.stop()
                    lifecycle.stop(id, fromNotification = true)
                    assertEquals(1, stops)
                    when (cycle % 5) {
                        0 -> VpnEvent.ServiceDestroyed to VpnState.Idle
                        2 -> VpnEvent.SystemConsentRevoked to VpnState.ConsentRevoked
                        else -> VpnEvent.CleanupFailed to VpnState.Failed
                    }
                }
                1 -> {
                    lifecycle.update(id, VpnEvent.TransportTunCreated)
                    assertEquals(VpnState.Stopping, lifecycle.state.value)
                    VpnEvent.ServiceDestroyed to VpnState.Idle
                }
                else -> VpnEvent.ServiceFailed to VpnState.StartFailed
            }
            assertFalse(lifecycle.beginConsent("before-end"))
            lifecycle.serviceEnded(id, terminal.first)
            lifecycle.serviceEnded(id, terminal.first)
            assertEquals(terminal.second, lifecycle.state.value)
            previous = id
        }
        assertEquals(100, events.count { it.type == VpnEventType.StartRequested })
        assertEquals(80, events.count { it.type == VpnEventType.StopFromApp })
        assertEquals(20, events.count { it.type == VpnEventType.StopError })
        assertEquals(20, events.count { it.type == VpnEventType.SystemRevoked })
        assertEquals(40, events.count { it.type == VpnEventType.Stopped })
    }
}
