package org.adaptivedpi.android

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.adaptivedpi.android.vpn.VpnController
import org.adaptivedpi.android.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class VpnLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val notifications = checkNotNull(
        instrumentation.targetContext.getSystemService(NotificationManager::class.java),
    )

    @Test
    fun recreatingTheActivityDoesNotStartVpn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals(VpnState.Idle, VpnController.state.value) }
            scenario.recreate()
            scenario.onActivity { assertEquals(VpnState.Idle, VpnController.state.value) }
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
                startSession(scenario)
                val oldStop = awaitNotification().notification.actions.single().actionIntent
                scenario.recreate()
                scenario.onActivity { assertEquals(VpnState.TestSessionRunning, VpnController.state.value) }

                scenario.onActivity {
                    VpnController.stop()
                    VpnController.stop()
                }
                awaitState(VpnState.Idle)
                awaitNotificationRemoval()

                startSession(scenario)
                val currentStop = awaitNotification().notification.actions.single().actionIntent
                assertNotEquals(oldStop, currentStop)
                oldStop.send()
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertEquals(VpnState.TestSessionRunning, VpnController.state.value) }

                currentStop.send()
                currentStop.send()
                awaitState(VpnState.Idle)
                awaitNotificationRemoval()
            } finally {
                scenario.onActivity { VpnController.stop() }
            }
        }
    }

    private fun startSession(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { assertNull(VpnController.requestStart(it)) }
        awaitState(VpnState.TestSessionRunning)
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
