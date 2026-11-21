package org.adaptivedpi.android.vpn

import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.VpnEventType
import org.adaptivedpi.android.vpn.diagnostics.VpnJournalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class VpnLifecycleJournalTest {
    private val recorded = mutableListOf<VpnJournalEvent>()
    private val lifecycle = VpnLifecycle { recorded.add(it) }

    @Test
    fun logsConsentThenActualStartAndOnlyTheFirstStopSource() {
        val session = UUID.randomUUID()
        val id = "process:$session"
        assertTrue(lifecycle.beginConsent(id))
        assertTrue(recorded.isEmpty())
        lifecycle.permissionResult(id, VpnEventType.PermissionRequested)
        lifecycle.permissionResult(id, VpnEventType.PermissionRequested)
        lifecycle.permissionResult(id, VpnEventType.PermissionGranted)
        lifecycle.update(id, VpnEvent.StartRequested)
        assertTrue(lifecycle.attachService(id) { })
        assertFalse(recorded.any { it.type == VpnEventType.Started })

        lifecycle.update(id, VpnEvent.ServiceStarted)
        lifecycle.update(id, VpnEvent.ServiceStarted)
        lifecycle.stop(id, fromNotification = true)
        lifecycle.stop()
        lifecycle.serviceEnded(id, VpnEvent.ServiceDestroyed)
        lifecycle.serviceEnded(id, VpnEvent.ServiceDestroyed)
        assertEquals(
            listOf(
                VpnEventType.PermissionRequested, VpnEventType.PermissionGranted, VpnEventType.StartRequested,
                VpnEventType.Started, VpnEventType.StopFromNotification, VpnEventType.Stopped,
            ),
            recorded.map { it.type },
        )
        assertTrue(recorded.all { it.sessionId == session })
    }

    @Test
    fun logsSystemRevocationOnceAndKeepsTheSuccessorSeparate() {
        val first = "process:${UUID.randomUUID()}"
        val second = "process:${UUID.randomUUID()}"
        start(first)
        lifecycle.serviceEnded(first, VpnEvent.SystemConsentRevoked)
        lifecycle.serviceEnded(first, VpnEvent.SystemConsentRevoked)
        assertEquals(VpnState.ConsentRevoked, lifecycle.state.value)

        start(second)
        lifecycle.stop(first, fromNotification = true)
        assertEquals(VpnState.TestSessionRunning, lifecycle.state.value)
        lifecycle.stop()
        lifecycle.stop(second, fromNotification = true)
        lifecycle.serviceEnded(second, VpnEvent.ServiceDestroyed)
        assertEquals(
            listOf(
                VpnEventType.StartRequested, VpnEventType.Started, VpnEventType.SystemRevoked,
                VpnEventType.StartRequested, VpnEventType.Started, VpnEventType.StopFromApp, VpnEventType.Stopped,
            ),
            recorded.map { it.type },
        )
        assertTrue(recorded.take(3).all { it.sessionId == first.toSessionUuid() })
        assertTrue(recorded.drop(3).all { it.sessionId == second.toSessionUuid() })
    }

    @Test
    fun distinguishesFailuresAndDoesNotInventSystemRevocationOrTunCreation() {
        val denied = "process:${UUID.randomUUID()}"
        val unavailable = "process:${UUID.randomUUID()}"
        val failed = "process:${UUID.randomUUID()}"
        lifecycle.beginConsent(denied)
        lifecycle.update(denied, VpnEvent.ConsentDenied)
        lifecycle.beginConsent(unavailable)
        lifecycle.update(unavailable, VpnEvent.StartRequested)
        lifecycle.update(unavailable, VpnEvent.ConsentRevoked)
        lifecycle.beginConsent(failed)
        lifecycle.update(failed, VpnEvent.StartRequested)
        lifecycle.update(failed, VpnEvent.ServiceFailed, DiagnosticCode.TunnelFailed)
        assertEquals(
            listOf(
                VpnEventType.PermissionDenied, VpnEventType.StartRequested, VpnEventType.PermissionUnavailable,
                VpnEventType.StartRequested, VpnEventType.StartError,
            ),
            recorded.map { it.type },
        )
        assertEquals(DiagnosticCode.TunnelFailed, recorded.last().code)
        assertEquals(VpnState.StartFailed, recorded.last().state)
    }

    @Test
    fun restorationRecordsUiAndLeavesVpnStateAlone() {
        val id = "process:${UUID.randomUUID()}"
        start(id)
        lifecycle.activityRestored(sameProcess = true)
        assertEquals(VpnEventType.ActivityRestored, recorded.last().type)
        assertEquals(id.toSessionUuid(), recorded.last().sessionId)
        assertEquals(1, recorded.count { it.type == VpnEventType.Started })
        assertEquals(VpnState.TestSessionRunning, lifecycle.state.value)

        val restored = mutableListOf<VpnJournalEvent>()
        val newProcess = VpnLifecycle { restored.add(it) }
        newProcess.activityRestored(sameProcess = false)
        assertEquals(VpnEventType.ProcessStateRestored, restored.single().type)
        assertNull(restored.single().sessionId)
        assertEquals(VpnState.Idle, newProcess.state.value)
    }

    private fun start(id: String) {
        assertTrue(lifecycle.beginConsent(id))
        lifecycle.update(id, VpnEvent.StartRequested)
        assertTrue(lifecycle.attachService(id) { })
        lifecycle.update(id, VpnEvent.ServiceStarted)
    }
}
