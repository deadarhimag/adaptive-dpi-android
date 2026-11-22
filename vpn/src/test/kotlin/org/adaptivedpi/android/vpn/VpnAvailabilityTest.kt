package org.adaptivedpi.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnAvailabilityTest {
    @Test
    fun missingNetworkInformationDoesNotProveAnotherVpnOrARestriction() {
        assertEquals(VpnAvailability.Unknown, classifyVpnAvailability(VpnState.Idle, null, false, false))
        assertEquals(VpnAvailability.NoVisibleVpn, classifyVpnAvailability(VpnState.Idle, false, false, false))
        assertEquals(VpnAvailability.OtherVpn, classifyVpnAvailability(VpnState.Idle, true, false, false))
    }

    @Test
    fun ownSessionOrItsTeardownNeverLooksLikeAnotherVpn() {
        for (state in listOf(VpnState.TestSessionRunning, VpnState.TransportStarting, VpnState.EngineRunning)) {
            assertEquals(VpnAvailability.OwnVpn, classifyVpnAvailability(state, null, false, false))
        }
        for (state in listOf(VpnState.Starting, VpnState.Stopping, VpnState.AwaitingConsent)) {
            assertEquals(VpnAvailability.Unknown, classifyVpnAvailability(state, true, false, false))
        }
        assertEquals(VpnAvailability.Unknown, classifyVpnAvailability(VpnState.Idle, true, true, false))
    }

    @Test
    fun aKnownRestrictionTakesPriorityOverAnObservedVpn() {
        assertEquals(VpnAvailability.Restricted, classifyVpnAvailability(VpnState.Idle, true, false, true))
        assertEquals(VpnAvailability.Restricted, classifyVpnAvailability(VpnState.ConsentDenied, null, false, true))
    }
}
