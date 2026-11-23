package org.adaptivedpi.android

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.UUID

class VpnLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val notifications = checkNotNull(
        instrumentation.targetContext.getSystemService(NotificationManager::class.java),
    )

    @get:Rule
    val failureJournal: TestWatcher = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            val history = VpnEventHistory.state.value
            Log.w("VpnLifecycleTest", "${description.methodName} state=${VpnController.state.value} " +
                "storageError=${history.storageError} dropped=${history.droppedInformational}/${history.droppedCritical}")
            history.entries.take(32).forEach { event ->
                Log.w("VpnLifecycleTest", "${event.time} ${event.type} session=${event.sessionId} " +
                    "state=${event.state} code=${event.code}")
            }
        }
    }

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
                assertTrue(checkNotNull(runningVpnService()).started)
                scenario.onActivity { assertTrue(it.stopService(Intent(it, AdaptiveVpnService::class.java))) }
                // Android остаётся клиентом службы, пока открыт TUN.
                assertFalse(checkNotNull(runningVpnService()).started)
                scenario.onActivity { VpnController.stop() }
                awaitState(VpnState.Idle)
                awaitNotificationRemoval()
                assertFalse(VpnController.resourcesBlocked.value)
                assertNull("Перед повторным запуском Android должен удалить старую службу", runningVpnService())
                assertStopRecorded(first, VpnEventType.StopFromApp)
                assertNotEquals(first, startSession(scenario))
            } finally {
                scenario.onActivity { VpnController.stop() }
                if (VpnController.state.value.canStop) awaitState(VpnState.Idle)
            }
        }
    }

    @Test
    fun missingTargetPackageFailsStartupAndAllowsAnotherSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val previous = VpnDiagnostics.events.value.map { it.id }.toSet()
            try {
                val failedSession = requestStart(scenario, applicationPackage = "org.adaptivedpi.ci.missing")
                awaitState(VpnState.StartFailed)
                awaitNotificationRemoval()
                assertFalse(VpnController.resourcesBlocked.value)
                runBlocking {
                    val entries = withTimeout(10_000) {
                        VpnEventHistory.state.first { state ->
                            state.entries.any {
                                it.sessionId == failedSession && it.type == VpnEventType.StartError &&
                                    it.code == DiagnosticCode.TransportApplicationUnavailable
                            }
                        }.entries
                    }
                    assertFalse(entries.any { it.sessionId == failedSession && it.type == VpnEventType.TransportTunCreated })
                }
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
                if (VpnController.state.value.canStop) awaitState(VpnState.Idle)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun runningVpnService(): ActivityManager.RunningServiceInfo? {
        val manager = checkNotNull(instrumentation.targetContext.getSystemService(ActivityManager::class.java))
        return manager.getRunningServices(100).firstOrNull {
            it.service.className == AdaptiveVpnService::class.java.name
        }
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
                scenario.onActivity {
                    assertEquals(VpnState.TestSessionRunning, VpnController.state.value)
                    assertEquals(first, VpnController.currentSessionId)
                }

                scenario.onActivity {
                    VpnController.stop()
                    VpnController.stop()
                }
                awaitState(VpnState.Idle)
                awaitNotificationRemoval()
                assertStopRecorded(first, VpnEventType.StopFromApp)

                val second = startSession(scenario)
                assertNotEquals(first, second)
                val currentStop = awaitNotification().notification.actions.single().actionIntent
                assertNotEquals(oldStop, currentStop)
                sendNotificationStop(oldStop, first)
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(VpnState.TestSessionRunning, VpnController.state.value)
                    assertEquals(second, VpnController.currentSessionId)
                }

                sendNotificationStop(currentStop, second)
                currentStop.send()
                awaitState(VpnState.Idle)
                awaitNotificationRemoval()
                scenario.onActivity { assertNull(VpnController.currentSessionId) }
                assertStopRecorded(second, VpnEventType.StopFromNotification)
            } finally {
                scenario.onActivity { VpnController.stop() }
                if (VpnController.state.value.canStop) awaitState(VpnState.Idle)
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
        assertEquals("Источник команды Stop для сеанса $session", listOf(source), stops.map { it.type })
    }

    private fun sendNotificationStop(stop: PendingIntent, expectedSession: UUID) = runBlocking {
        val delivered = CompletableDeferred<Intent?>()
        stop.send(0, { _, intent, _, _, _ -> delivered.complete(intent) }, Handler(Looper.getMainLooper()))
        val intent = checkNotNull(withTimeout(10_000) { delivered.await() })
        val expectedId = "${VpnController.processIdentity}:$expectedSession"
        assertEquals(ComponentName(instrumentation.targetContext, AdaptiveVpnService::class.java), intent.component)
        assertEquals("org.adaptivedpi.android.vpn.STOP", intent.action)
        assertEquals(Uri.fromParts("adaptive-dpi", expectedId, null), intent.data)
        assertEquals(expectedId, intent.getStringExtra("org.adaptivedpi.android.vpn.SESSION_ID"))
        Log.i("VpnLifecycleTest", "Отправлен Stop из уведомления для сеанса $expectedSession")
    }

    private fun startSession(scenario: ActivityScenario<MainActivity>): UUID {
        val session = requestStart(scenario)
        awaitState(VpnState.TestSessionRunning)
        scenario.onActivity { assertEquals(session, VpnController.currentSessionId) }
        runBlocking {
            withTimeout(10_000) {
                VpnEventHistory.state.first { state ->
                    state.entries.any { it.type == VpnEventType.Started && it.sessionId == session }
                }
            }
        }
        return session
    }

    private fun requestStart(scenario: ActivityScenario<MainActivity>, applicationPackage: String? = null): UUID {
        var session: UUID? = null
        scenario.onActivity { activity ->
            assertNull(VpnController.currentSessionId)
            assertNull(VpnService.prepare(activity))
            assertNull(VpnController.requestStart(activity, applicationPackage = applicationPackage))
            assertEquals(VpnState.Starting, VpnController.state.value)
            session = checkNotNull(VpnController.currentSessionId)
        }
        return checkNotNull(session).also { Log.i("VpnLifecycleTest", "Принят Start для сеанса $it") }
    }

    private fun awaitState(expected: VpnState) = runBlocking {
        withTimeoutOrNull(10_000) { VpnController.state.first { it == expected } }
            ?: error("Ожидалось состояние $expected. Текущее состояние: ${VpnController.state.value}.")
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
