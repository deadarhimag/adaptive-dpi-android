package org.adaptivedpi.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnStateTest {
    @Test
    fun startsAfterGrantAndStops() {
        val awaiting = VpnState.Idle.reduce(VpnEvent.ConsentRequired)
        assertEquals(VpnState.AwaitingConsent, awaiting)
        val starting = awaiting.reduce(VpnEvent.StartRequested)
        assertEquals(VpnState.Starting, starting)
        val active = starting.reduce(VpnEvent.ServiceStarted)
        assertEquals(VpnState.TestSessionRunning, active)
        val stopping = active.reduce(VpnEvent.StopRequested)
        assertEquals(VpnState.Stopping, stopping)
        assertEquals(VpnState.Idle, stopping.reduce(VpnEvent.ServiceDestroyed))
    }

    @Test
    fun canRetryAfterConsentDenial() {
        val denied = VpnState.AwaitingConsent.reduce(VpnEvent.ConsentDenied)
        assertEquals(VpnState.ConsentDenied, denied)
        assertTrue(denied.canStart)
        assertEquals(VpnState.AwaitingConsent, denied.reduce(VpnEvent.ConsentRequired))
    }

    @Test
    fun alreadyGrantedConsentStartsDirectly() {
        assertEquals(VpnState.Starting, VpnState.Idle.reduce(VpnEvent.StartRequested))
    }

    @Test
    fun rejectsDuplicateStarts() {
        for (state in listOf(VpnState.Starting, VpnState.TestSessionRunning, VpnState.Stopping)) {
            assertFalse(state.canStart)
            assertEquals(state, state.reduce(VpnEvent.StartRequested))
        }
    }

    @Test
    fun revokeDuringStartOrRunSurvivesServiceDestruction() {
        for (state in listOf(VpnState.Starting, VpnState.TestSessionRunning, VpnState.Stopping)) {
            val revoked = state.reduce(VpnEvent.ConsentRevoked)
            assertEquals(VpnState.ConsentRevoked, revoked)
            assertEquals(revoked, revoked.reduce(VpnEvent.ServiceDestroyed))
            assertEquals(revoked, revoked.reduce(VpnEvent.ServiceStarted))
            assertTrue(revoked.canStart)
        }
    }

    @Test
    fun preservesStartErrorAndAllowsRetry() {
        val failed = VpnState.Starting.reduce(VpnEvent.ServiceFailed)
        assertEquals(VpnState.StartFailed, failed)
        assertEquals(failed, failed.reduce(VpnEvent.ServiceDestroyed))
        assertEquals(failed, VpnState.Starting.reduce(VpnEvent.ServiceDestroyed))
        assertEquals(VpnState.Starting, failed.reduce(VpnEvent.StartRequested))
    }

    @Test
    fun tracksUnexpectedStopWithoutRevocation() {
        assertEquals(VpnState.Idle, VpnState.TestSessionRunning.reduce(VpnEvent.ServiceDestroyed))
    }
}
