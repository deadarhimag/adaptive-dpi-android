package org.adaptivedpi.android

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticEvent
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics
import org.adaptivedpi.android.vpn.VpnController
import org.adaptivedpi.android.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DiagnosticsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications = checkNotNull(context.getSystemService(NotificationManager::class.java))

    @Test
    fun sharesOnlyAReadableDiagnosticCacheFileAndUsesTheDocumentPickerForSaving() {
        val event = record(DiagnosticCode.ConsentFailed)
        val outside = File.createTempFile("private-", ".txt", context.cacheDir)
        try {
            val send = DiagnosticExports.shareIntent(context, event)
            val uri = checkNotNull(send.clipData).getItemAt(0).uri
            val authority = "${context.packageName}.diagnostic-reports"

            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals("content", uri.scheme)
            assertEquals(authority, uri.authority)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertEquals(
                event.report(),
                checkNotNull(context.contentResolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8).use { it.readText() },
            )

            val provider = checkNotNull(context.packageManager.resolveContentProvider(authority, 0))
            assertFalse(provider.exported)
            assertTrue(provider.grantUriPermissions)
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, authority, outside) }

            val document = ActivityResultContracts.CreateDocument("text/plain")
                .createIntent(context, DiagnosticExports.fileName(event))
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, document.action)
            assertEquals("text/plain", document.type)
            assertEquals(DiagnosticExports.fileName(event), document.getStringExtra(Intent.EXTRA_TITLE))
        } finally {
            outside.delete()
            VpnDiagnostics.acknowledge(event.id)
        }
    }

    @Test
    fun criticalErrorPostsOneNotificationAndOpensItsDiagnosticEvent() {
        assumeTrue("На тестовом Android включите уведомления приложения", notifications.areNotificationsEnabled())
        assumeTrue(
            "На тестовом Android включите канал критических ошибок",
            notifications.getNotificationChannel(VpnDiagnostics.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_HIGH,
        )
        // Запись и публикация происходят без открытой Activity.
        val event = record(DiagnosticCode.VpnUnexpectedStop)
        try {
            val tag = event.id.toString()
            await { notifications.activeNotifications.any { it.tag == tag } }
            val posted = notifications.activeNotifications.single { it.tag == tag }
            assertEquals(VpnDiagnostics.CHANNEL_ID, posted.notification.channelId)
            assertEquals("VPN отключён из-за ошибки", posted.notification.extras.getCharSequence("android.title").toString())
            assertEquals("Подробнее", posted.notification.actions.single().title.toString())

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                posted.notification.contentIntent.send()
                await {
                    var selected = false
                    scenario.onActivity { selected = VpnDiagnostics.eventId(it.intent) == event.id }
                    selected
                }
            }
        } finally {
            VpnDiagnostics.acknowledge(event.id)
        }
    }

    @Test
    fun deniedPermissionLeavesAnUnreadErrorWithoutSystemNotification() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        assumeTrue(
            "Запретите уведомления на тестовом Android",
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED,
        )
        val event = record(DiagnosticCode.VpnUnexpectedStop)
        try {
            assertFalse(VpnDiagnostics.events.value.first { it.id == event.id }.acknowledged)
            assertFalse(notifications.activeNotifications.any { it.tag == event.id.toString() })
        } finally {
            VpnDiagnostics.acknowledge(event.id)
        }
    }

    @Test
    fun anAbortedVpnProcessIsReportedAfterTheNextLaunch() {
        val argument = InstrumentationRegistry.getArguments().getString("recoveredVpnError")
        assumeTrue("Тест запускается после отдельного аварийного завершения приложения", argument != null)
        val code = DiagnosticCode.valueOf(checkNotNull(argument))
        val event = VpnDiagnostics.events.value.first { it.code == code && !it.acknowledged }
        try {
            assertTrue(event.vpnStopped)
            assertEquals(VpnState.Idle, VpnController.state.value)
            assertTrue(event.report().contains(event.id.toString()))
        } finally {
            VpnDiagnostics.acknowledge(event.id)
        }
    }

    private fun record(code: DiagnosticCode): DiagnosticEvent {
        VpnDiagnostics.record(code)
        return VpnDiagnostics.events.value.first { it.code == code }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(condition())
    }
}
