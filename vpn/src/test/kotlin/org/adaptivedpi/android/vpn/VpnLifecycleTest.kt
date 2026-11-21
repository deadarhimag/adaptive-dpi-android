package org.adaptivedpi.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnLifecycleTest {
    @Test
    fun acceptsOnlyMatchingConsentAndServiceCommands() {
        val lifecycle = VpnLifecycle()
        assertTrue(lifecycle.beginConsent("requested"))
        lifecycle.update("old", VpnEvent.ConsentDenied)
        assertTrue(lifecycle.isAwaitingConsent("requested"))
        assertFalse(lifecycle.beginConsent("duplicate"))
        assertFalse(lifecycle.attachService("requested") { error("Нет разрешения на запуск") })

        lifecycle.update("requested", VpnEvent.StartRequested)
        assertFalse(lifecycle.attachService("old") { error("Команда из другого сеанса") })
        assertTrue(lifecycle.attachService("requested") {})
        assertFalse(lifecycle.attachService("requested") { error("Повторный владелец") })
        lifecycle.update("requested", VpnEvent.ServiceStarted)
        assertEquals(VpnState.TestSessionRunning, lifecycle.state.value)
    }

    @Test
    fun freshProcessRejectsQueuedServiceStartButCanRecoverDeliveredConsent() {
        val lifecycle = VpnLifecycle()
        assertEquals(VpnState.Idle, lifecycle.state.value)
        assertFalse(lifecycle.attachService("old") { error("Сеанс из старого процесса") })
        lifecycle.update("old", VpnEvent.ServiceStarted)
        assertEquals(VpnState.Idle, lifecycle.state.value)

        assertTrue(lifecycle.beginConsent("saved", restoring = true))
        assertTrue(lifecycle.isAwaitingConsent("saved"))
        lifecycle.update("saved", VpnEvent.ConsentDenied)
        assertEquals(VpnState.ConsentDenied, lifecycle.state.value)
        assertFalse(lifecycle.beginConsent("saved", restoring = true))
    }

    @Test
    fun staleStopAndCleanupLeaveNewSessionIntact() {
        val lifecycle = VpnLifecycle()
        lifecycle.start("first") {}
        lifecycle.stop("first")
        lifecycle.serviceEnded("first", VpnEvent.ServiceDestroyed)

        var stops = 0
        lifecycle.start("next") { stops++ }
        lifecycle.stop("first")
        lifecycle.serviceEnded("first", VpnEvent.ConsentRevoked)
        assertEquals(0, stops)
        assertEquals(VpnState.TestSessionRunning, lifecycle.state.value)
    }

    @Test
    fun stopBeforeServiceAttachmentDoesNotRestoreRunningState() {
        val lifecycle = VpnLifecycle()
        assertTrue(lifecycle.beginConsent("pending"))
        lifecycle.update("pending", VpnEvent.StartRequested)
        lifecycle.stop()
        lifecycle.stop()

        assertEquals(VpnState.Stopping, lifecycle.state.value)
        assertTrue(lifecycle.attachService("pending") {})
        lifecycle.update("pending", VpnEvent.ServiceStarted)
        assertEquals(VpnState.Stopping, lifecycle.state.value)
        lifecycle.serviceEnded("pending", VpnEvent.ServiceDestroyed)
        assertEquals(VpnState.Idle, lifecycle.state.value)
    }

    @Test
    fun repeatedStopRequestsInvokeCleanupOnceAndKeepTheTokenUntilServiceEnds() {
        val lifecycle = VpnLifecycle()
        var stops = 0
        lifecycle.start("active") { stops++ }

        lifecycle.stop()
        lifecycle.stop("active")
        assertEquals(1, stops)
        assertEquals(VpnState.Stopping, lifecycle.state.value)
        assertFalse(lifecycle.beginConsent("next"))

        lifecycle.serviceEnded("active", VpnEvent.ServiceDestroyed)
        lifecycle.serviceEnded("active", VpnEvent.ServiceDestroyed)
        assertEquals(VpnState.Idle, lifecycle.state.value)
        assertTrue(lifecycle.beginConsent("next"))
    }

    @Test
    fun permissionRevocationEndsOnlyTheCurrentSession() {
        val lifecycle = VpnLifecycle()
        lifecycle.start("active") {}
        lifecycle.serviceEnded("active", VpnEvent.ConsentRevoked)
        lifecycle.serviceEnded("active", VpnEvent.ServiceDestroyed)
        assertEquals(VpnState.ConsentRevoked, lifecycle.state.value)
        assertTrue(lifecycle.beginConsent("retry"))
    }

    @Test
    fun serviceFailureWhileStoppingLeavesIdleAndAllowsRetry() {
        val lifecycle = VpnLifecycle()
        assertTrue(lifecycle.beginConsent("pending"))
        lifecycle.update("pending", VpnEvent.StartRequested)
        lifecycle.stop()
        assertTrue(lifecycle.attachService("pending") {})
        lifecycle.serviceEnded("pending", VpnEvent.ServiceFailed)
        assertEquals(VpnState.Idle, lifecycle.state.value)
        assertTrue(lifecycle.beginConsent("retry"))
    }

    @Test
    fun abandoningConsentIgnoresItsLateResult() {
        val lifecycle = VpnLifecycle()
        assertTrue(lifecycle.beginConsent("first"))
        lifecycle.update("first", VpnEvent.ConsentAbandoned)
        assertEquals(VpnState.Idle, lifecycle.state.value)

        assertTrue(lifecycle.beginConsent("next"))
        lifecycle.update("first", VpnEvent.StartRequested)
        assertTrue(lifecycle.isAwaitingConsent("next"))
    }

    private fun VpnLifecycle.start(id: String, stop: () -> Unit) {
        assertTrue(beginConsent(id))
        update(id, VpnEvent.StartRequested)
        assertTrue(attachService(id, stop))
        update(id, VpnEvent.ServiceStarted)
    }

    @Test
    fun runningTunDoesNotConfirmEngineAndAConfirmedEngineCanBeStopped() {
        val lifecycle = VpnLifecycle()
        var stops = 0
        lifecycle.start("current") { stops++ }
        lifecycle.update("old", VpnEvent.EngineConfirmed)
        assertEquals(VpnState.TestSessionRunning, lifecycle.state.value)

        lifecycle.update("current", VpnEvent.EngineConfirmed)
        assertEquals(VpnState.EngineRunning, lifecycle.state.value)
        lifecycle.stop()
        lifecycle.update("current", VpnEvent.EngineConfirmed)
        assertEquals(VpnState.Stopping, lifecycle.state.value)
        assertEquals(1, stops)
    }

    @Test
    fun manualStopCanCompleteAnAlreadyStartedCriticalShutdownOnlyOnce() {
        val lifecycle = VpnLifecycle()
        var stops = 0
        lifecycle.start("fault") { stops++ }
        lifecycle.update("fault", VpnEvent.StopRequested)
        lifecycle.stop()
        lifecycle.stop("fault")
        assertEquals(1, stops)

        lifecycle.serviceEnded("fault", VpnEvent.CriticalFailure)
        assertEquals(VpnState.Failed, lifecycle.state.value)
        assertFalse(lifecycle.attachService("fault") { error("Автоперезапуск с прежним ID") })
    }
}
