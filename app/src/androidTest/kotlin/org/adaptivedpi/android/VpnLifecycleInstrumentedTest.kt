package org.adaptivedpi.android

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.adaptivedpi.android.vpn.AdaptiveVpnService
import org.adaptivedpi.android.vpn.VpnController
import org.adaptivedpi.android.vpn.VpnState
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnEventHistory
import org.adaptivedpi.android.vpn.diagnostics.VpnEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class VpnLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val notifications = checkNotNull(
        instrumentation.targetContext.getSystemService(NotificationManager::class.java),
    )

    @Test
    fun recreatingTheActivityDoesNotStartVpn() {
        val before = runBlocking { withTimeout(10_000) { VpnEventHistory.state.first { it.loaded } } }.entries
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals(VpnState.Idle, VpnController.state.value) }
            scenario.recreate()
            scenario.onActivity { assertEquals(VpnState.Idle, VpnController.state.value) }
            val entries = runBlocking {
                withTimeout(10_000) {
                    VpnEventHistory.state.first { history ->
                        history.entries.any { it.type == VpnEventType.ActivityRestored && it !in before }
                    }.entries
                }
            }
            assertEquals(listOf(VpnEventType.ActivityRestored), entries.filterNot { it in before }.map { it.type })
        }
    }

    @Test
    fun oneHundredAndroidServiceSessionsRejectDuplicateStartsAndReachIdle() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var prepared = false
            scenario.onActivity { prepared = VpnService.prepare(it) == null }
            assumeTrue("Требуется предварительно выданное согласие Android", prepared)
            try {
                repeat(100) {
                    scenario.onActivity { activity ->
                        assertEquals(VpnState.Idle, VpnController.state.value)
                        assertNull(VpnController.requestStart(activity))
                        assertNull(VpnController.requestStart(activity))
                        assertEquals(VpnState.Starting, VpnController.state.value)
                    }
                    awaitState(VpnState.TestSessionRunning)
                    scenario.onActivity {
                        VpnController.stop()
                        VpnController.stop()
                    }
                    awaitState(VpnState.Idle)
                }
            } finally {
                scenario.onActivity { VpnController.stop() }
            }
        }
    }

    @Test
    fun immediateStopWhileStartingReleasesServiceAndAllowsNewSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var prepared = false
            scenario.onActivity { prepared = VpnService.prepare(it) == null }
            assumeTrue("Требуется предварительно выданное согласие Android", prepared)
            try {
                scenario.onActivity { activity ->
                    assertNull(VpnController.requestStart(activity))
                    assertEquals(VpnState.Starting, VpnController.state.value)
                    VpnController.stop()
                    assertEquals(VpnState.Stopping, VpnController.state.value)
                    assertNull(VpnController.requestStart(activity))
                    assertEquals(VpnState.Stopping, VpnController.state.value)
                }
                awaitState(VpnState.Idle)
                awaitNotificationRemoval()
                startSession(scenario)
            } finally {
                scenario.onActivity { VpnController.stop() }
                awaitState(VpnState.Idle)
            }
        }
    }

    @Test
    fun androidServiceDestructionCleansUpBeforeRestart() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                val first = startSession(scenario)
                scenario.onActivity { assertTrue(it.stopService(Intent(it, AdaptiveVpnService::class.java))) }
                awaitState(VpnState.Failed)
                awaitNotificationRemoval()
                assertFalse(VpnController.resourcesBlocked.value)
                acknowledgeNewError(DiagnosticCode.VpnUnexpectedStop, first)
                assertNotEquals(first, startSession(scenario))
            } finally {
                scenario.onActivity { VpnController.stop() }
                awaitState(VpnState.Idle)
            }
        }
    }

    @Test
    fun missingTargetPackageFailsStartupAndAllowsAnotherSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val previous = VpnDiagnostics.events.value.map { it.id }.toSet()
            try {
                scenario.onActivity {
                    assertNull(VpnService.prepare(it))
                    assertNull(VpnController.requestStart(it, applicationPackage = "org.adaptivedpi.ci.missing"))
                    assertEquals(VpnState.Starting, VpnController.state.value)
                }
                awaitState(VpnState.StartFailed)
                awaitNotificationRemoval()
                assertFalse(VpnController.resourcesBlocked.value)
                val error = runBlocking {
                    withTimeout(10_000) {
                        VpnDiagnostics.events.first { events ->
                            events.any { it.code == DiagnosticCode.TransportApplicationUnavailable && it.id !in previous }
                        }.first { it.code == DiagnosticCode.TransportApplicationUnavailable && it.id !in previous }
                    }
                }
                acknowledgeError(error.id)
                startSession(scenario)
            } finally {
                scenario.onActivity { VpnController.stop() }
                awaitState(VpnState.Idle)
            }
        }
    }

    private fun acknowledgeNewError(code: DiagnosticCode, id: UUID) = runBlocking {
        withTimeout(10_000) {
            VpnDiagnostics.events.first { events -> events.any { it.code == code && it.id == id } }
        }
        acknowledgeError(id)
    }

    private fun acknowledgeError(id: UUID) = runBlocking {
        VpnDiagnostics.acknowledge(id)
        withTimeout(10_000) {
            VpnDiagnostics.events.first { events -> events.none { it.id == id && !it.acknowledged } }
        }
    }

    @Test
    fun activeSessionSurvivesRecreationAndOldNotificationCannotStopItsSuccessor() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var prepared = false
            scenario.onActivity { prepared = VpnService.prepare(it) == null }
            assumeTrue("На тестовом устройстве сначала подтвердите согласие VPN и остановите сеанс", prepared)
            assumeTrue(
                "Разрешите уведомления на тестовом устройстве",
                Build.VERSION.SDK_INT < 33 ||
                    instrumentation.targetContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
            )

            try {
                val first = startSession(scenario)
                val oldStop = awaitNotification().notification.actions.single().actionIntent
                scenario.recreate()
                scenario.onActivity { assertEquals(VpnState.TestSessionRunning, VpnController.state.value) }

                scenario.onActivity {
                    VpnController.stop()
                    VpnController.stop()
                }
                awaitState(VpnState.Idle)
                awaitNotificationRemoval()
                assertStopRecorded(first, VpnEventType.StopFromApp)

                val second = startSession(scenario)
                val currentStop = awaitNotification().notification.actions.single().actionIntent
                assertNotEquals(oldStop, currentStop)
                oldStop.send()
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertEquals(VpnState.TestSessionRunning, VpnController.state.value) }

                currentStop.send()
                currentStop.send()
                awaitState(VpnState.Idle)
                awaitNotificationRemoval()
                assertStopRecorded(second, VpnEventType.StopFromNotification)
            } finally {
                scenario.onActivity { VpnController.stop() }
            }
        }
    }

    private fun assertStopRecorded(session: UUID, source: VpnEventType) = runBlocking {
        val entries = withTimeout(10_000) {
            VpnEventHistory.state.first { history ->
                history.entries.any { it.sessionId == session && it.type == VpnEventType.Stopped }
            }.entries
        }
        val stops = entries.filter {
            it.sessionId == session && it.type in listOf(VpnEventType.StopFromApp, VpnEventType.StopFromNotification)
        }
        assertEquals(listOf(source), stops.map { it.type })
    }

    private fun startSession(scenario: ActivityScenario<MainActivity>): UUID {
        val initialized = runBlocking { withTimeout(10_000) { VpnEventHistory.state.first { it.loaded } } }
        val before = initialized.entries.map { it.id }.toSet()
        scenario.onActivity { assertNull(VpnController.requestStart(it)) }
        awaitState(VpnState.TestSessionRunning)
        return runBlocking {
            withTimeout(10_000) {
                val history = VpnEventHistory.state.first { state ->
                    state.entries.any { it.type == VpnEventType.Started && it.id !in before }
                }
                checkNotNull(history.entries.first { it.type == VpnEventType.Started && it.id !in before }.sessionId)
            }
        }
    }

    private fun awaitState(expected: VpnState) = runBlocking {
        withTimeout(10_000) { VpnController.state.first { it == expected } }
    }

    private fun awaitNotification() = runBlocking {
        withTimeout(10_000) {
            var notification = vpnNotification()
            while (notification == null) {
                delay(50)
                notification = vpnNotification()
            }
            notification
        }
    }

    private fun awaitNotificationRemoval() = runBlocking {
        withTimeout(10_000) {
            while (vpnNotification() != null) delay(50)
        }
    }

    private fun vpnNotification() =
        notifications.activeNotifications.firstOrNull { it.notification.channelId == "vpn_session" }
}
