package org.adaptivedpi.android

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticEvent
import org.adaptivedpi.android.vpn.diagnostics.TransportDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnEventHistory
import org.adaptivedpi.android.vpn.diagnostics.VpnEventType
import org.adaptivedpi.android.vpn.diagnostics.combinedDiagnosticReport
import org.adaptivedpi.android.vpn.VpnController
import org.adaptivedpi.android.vpn.VpnState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringWriter

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
    fun sharesTheExactCombinedReportSnapshotAsUtf8() {
        val report = combinedDiagnosticReport(
            "0.1.0", Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MODEL, VpnController.state.value,
            VpnEventHistory.state.value, VpnDiagnostics.events.value,
        )
        val send = DiagnosticExports.shareIntent(context, DiagnosticExports.COMBINED_FILE_NAME, report)
        val uri = checkNotNull(send.clipData).getItemAt(0).uri
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("content", uri.scheme)
        assertEquals(
            report,
            checkNotNull(context.contentResolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
        val save = ActivityResultContracts.CreateDocument("text/plain")
            .createIntent(context, DiagnosticExports.COMBINED_FILE_NAME)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, save.action)
        assertEquals(DiagnosticExports.COMBINED_FILE_NAME, save.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun savesAndSharesIdenticalTransportTextAndKeepsRecentAttachmentsReadable() = runBlocking(Dispatchers.IO) {
        val report = StringWriter().also { TransportDiagnostics.export(it) }.toString()
        val send = DiagnosticExports.shareTransportJournal(context)
        val uri = checkNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        val resolver = context.contentResolver
        val directory = File(context.cacheDir, "diagnostic-reports")
        val destination = File.createTempFile("saf-test-", ".txt", directory)
        val expired = File.createTempFile("expired-test-", ".txt", directory)
        val recent = mutableListOf<File>()
        var next: Uri? = null
        try {
            assertTrue(expired.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L))
            repeat(17) { recent += File.createTempFile("recent-test-", ".txt", directory) }
            val nextSend = DiagnosticExports.shareTransportJournal(context)
            next = checkNotNull(nextSend.clipData).getItemAt(0).uri
            assertFalse(expired.exists())
            assertTrue(recent.all { it.exists() })

            val document = ActivityResultContracts.CreateDocument("text/plain")
                .createIntent(context, DiagnosticExports.TRANSPORT_FILE_NAME)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, document.action)
            assertEquals("text/plain", document.type)
            assertEquals("adaptive-dpi-transport.txt", document.getStringExtra(Intent.EXTRA_TITLE))
            val documentUri = FileProvider.getUriForFile(context, checkNotNull(uri.authority), destination)
            DiagnosticExports.saveTransportJournal(context, documentUri)

            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals("content", uri.scheme)
            assertEquals(uri, checkNotNull(send.clipData).getItemAt(0).uri)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            val chooser = DiagnosticExports.shareChooser(send, "Поделиться")
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(uri, checkNotNull(chooser.clipData).getItemAt(0).uri)
            checkNotNull(resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)).use {
                assertTrue(it.moveToFirst())
                assertEquals("adaptive-dpi-transport.txt", it.getString(0))
            }
            val bytes = checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
            assertArrayEquals(report.toByteArray(Charsets.UTF_8), bytes)
            assertArrayEquals(destination.readBytes(), bytes)
        } finally {
            resolver.delete(uri, null, null)
            next?.let { resolver.delete(it, null, null) }
            destination.delete()
            expired.delete()
            recent.forEach { it.delete() }
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
            val type = if (code == DiagnosticCode.AppCrash) VpnEventType.ProcessCrash else VpnEventType.InterruptedSession
            await { VpnEventHistory.state.value.entries.any { it.id == event.id && it.type == type } }
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
