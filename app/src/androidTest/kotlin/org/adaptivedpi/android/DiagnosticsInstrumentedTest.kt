package org.adaptivedpi.android

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DiagnosticsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications = checkNotNull(context.getSystemService(NotificationManager::class.java))

    @Test
    fun pendingCombinedReportSnapshotIsAPrivateFileUntilDeleted() {
        val report = "Снимок отчёта\nстрока 2 ✓"
        val name = DiagnosticExports.storePending(context, report)
        val file = File(File(context.noBackupFilesDir, "pending-export"), name)
        try {
            assertTrue(file.isFile)
            check(file.setLastModified(1))
            DiagnosticExports.pruneExpiredShares(context)
            DiagnosticExports.prunePending(context)
            assertEquals(report, DiagnosticExports.readPending(context, name))
            assertEquals(null, DiagnosticExports.readPending(context, "../$name"))
            DiagnosticExports.deletePending(context, name)
            assertFalse(file.exists())
            assertEquals(null, DiagnosticExports.readPending(context, name))
        } finally {
            file.delete()
        }
    }

    @Test
    fun recreatingDuringSnapshotPreparationLaunchesSafOnceAndSavesTheSameText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pending = File(context.noBackupFilesDir, "pending-export")
        val previous = pending.list().orEmpty().toSet()
        val directory = File(context.cacheDir, "diagnostic-reports").apply { mkdirs() }
        val destination = File.createTempFile("snapshot-recreation-", ".txt", directory)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", destination)
        val monitor = instrumentation.addMonitor(
            IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                addDataType("text/plain")
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)),
            true,
        )
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateExpired = AtomicBoolean()
        val writerGate = thread {
            synchronized(DiagnosticExports) {
                locked.countDown()
                if (!release.await(15, TimeUnit.SECONDS)) gateExpired.set(true)
            }
        }
        val report = "Снимок до пересоздания\nСтрока 2 ✓"
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("saveSnapshot", String::class.java, String::class.java)
                        .apply { isAccessible = true }.invoke(activity, report, DiagnosticExports.COMBINED_FILE_NAME)
                }
                scenario.recreate()
                release.countDown()
                await { destination.readText() == report && (pending.list().orEmpty().toSet() - previous).isEmpty() }
                assertFalse("Запись была разблокирована до пересоздания", gateExpired.get())
                instrumentation.waitForIdleSync()
                assertEquals(1, monitor.hits)
            }
        } finally {
            release.countDown()
            writerGate.join(5_000)
            instrumentation.removeMonitor(monitor)
            destination.delete()
        }
    }

    @Test
    fun recreatingDuringSafWriteKeepsOneWriteAndUnblocksAnotherExport() {
        val fixture = SafFixture(context)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pending = File(context.noBackupFilesDir, "pending-export")
        val previous = pending.list().orEmpty().toSet()
        val report = "Проверка записи ✓\n".repeat(12_000)
        val monitor = monitorSave(fixture.uri)
        try {
            SafFixture.assertHealthy(checkNotNull(fixture.reset("success", report.toByteArray().size).getBundle("previous")))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.exportSnapshot(report) }
                await { fixture.healthyState().getBoolean("writing") }
                scenario.recreate()
                assertEquals(1, monitor.hits)
                fixture.release()
                await {
                    val result = fixture.healthyState()
                    result.getBoolean("finished") && result.getInt("bytes") == report.toByteArray().size &&
                        result.getString("digest") == digest(report.toByteArray()) &&
                        (pending.list().orEmpty().toSet() - previous).isEmpty()
                }
                assertEquals(1, fixture.healthyState().getInt("opened"))
                val second = "Второй отчёт"
                fixture.reset("success", second.toByteArray().size)
                fixture.release()
                scenario.onActivity { it.exportSnapshot(second) }
                await { monitor.hits == 2 }
                await {
                    fixture.healthyState().getString("digest") ==
                        digest(second.toByteArray()) && (pending.list().orEmpty().toSet() - previous).isEmpty()
                }
            }
        } finally {
            fixture.release()
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun recreatingAfterSafResultBeforeReadPreservesThePendingWrite() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val report = "До чтения ✓"
        val directory = File(context.cacheDir, "diagnostic-reports").apply { mkdirs() }
        val destination = File.createTempFile("snapshot-before-read-", ".txt", directory)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", destination)
        val monitor = monitorSave(uri)
        val name = DiagnosticExports.storePending(context, report)
        val retained = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateExpired = AtomicBoolean()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val holder = thread {
                    synchronized(DiagnosticExports) {
                        retained.countDown()
                        if (!release.await(15, TimeUnit.SECONDS)) gateExpired.set(true)
                    }
                }
                try {
                    assertTrue(retained.await(4, TimeUnit.SECONDS))
                    scenario.onActivity { activity ->
                        val fileName = DiagnosticExports.COMBINED_FILE_NAME
                        activity.javaClass.getDeclaredMethod("requestSave", String::class.java, String::class.java)
                            .apply { isAccessible = true }.invoke(activity, "snapshot:$fileName:$name", fileName)
                    }
                    await {
                        var received = false
                        scenario.onActivity {
                            val owner = androidx.lifecycle.ViewModelProvider(it)[DiagnosticSnapshotPreparation::class.java]
                            received = owner.writing != null
                        }
                        received
                    }
                    scenario.recreate()
                    assertFalse(gateExpired.get())
                    assertEquals(0L, destination.length())
                } finally {
                    release.countDown()
                    holder.join(5_000)
                }
                await { destination.readText() == report && DiagnosticExports.readPending(context, name) == null }
                assertEquals(1, monitor.hits)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            DiagnosticExports.deletePending(context, name)
            destination.delete()
        }
    }

    @Test
    fun consumedSafSnapshotCannotBeClaimedAgainUntilExplicitRetry() {
        val report = "Состояние на диске после получения URI ✓"
        val name = DiagnosticExports.storePending(context, report)
        try {
            assertEquals(DiagnosticExports.WriteClaim.Claimed, DiagnosticExports.claimWrite(context, name))
            DiagnosticExports.releasePending(name)
            DiagnosticExports.retainPending(name)
            assertEquals(DiagnosticExports.WriteClaim.Interrupted, DiagnosticExports.claimWrite(context, name))
            assertEquals(report, DiagnosticExports.readPending(context, name))
            DiagnosticExports.armRetry(context, name)
            assertEquals(DiagnosticExports.WriteClaim.Claimed, DiagnosticExports.claimWrite(context, name))
            DiagnosticExports.deletePending(context, name)
            assertEquals(DiagnosticExports.WriteClaim.Missing, DiagnosticExports.claimWrite(context, name))
        } finally {
            DiagnosticExports.deletePending(context, name)
        }
    }

    @Test
    fun cancelSafClearsSnapshotAndAllowsAnotherSave() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pending = File(context.noBackupFilesDir, "pending-export")
        val previous = pending.list().orEmpty().toSet()
        val monitor = monitorSave(null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                repeat(2) { index ->
                    scenario.onActivity { it.exportSnapshot("Отмена ${index + 1} ✓") }
                    await { monitor.hits == index + 1 && (pending.list().orEmpty().toSet() - previous).isEmpty() }
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun failingSafOpenOrWriteRetainsTheSnapshotForExplicitRetry() {
        val fixture = SafFixture(context)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pending = File(context.noBackupFilesDir, "pending-export")
        val previous = pending.list().orEmpty().toSet()
        val physical = File(context.cacheDir, "diagnostic-reports").apply { mkdirs() }
        val saved = File.createTempFile("retry-", ".txt", physical)
        val goodUri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", saved)
        try {
            for (mode in listOf("open-error", "write-error")) {
                val broken = monitorSave(fixture.uri)
                SafFixture.assertHealthy(checkNotNull(fixture.reset(mode).getBundle("previous")))
                try {
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        val report = "Повтор записи: $mode ✓"
                        val errors = VpnDiagnostics.events.value.map { it.id }.toSet()
                        scenario.onActivity { it.exportSnapshot(report) }
                        await { VpnDiagnostics.events.value.any { it.id !in errors && it.code == DiagnosticCode.SaveFailed } }
                        val failure = fixture.healthyState()
                        assertEquals(1, failure.getInt("opened"))
                        assertEquals(mode, failure.getString("outcome"))
                        assertTrue((pending.listFiles().orEmpty().filter { it.name !in previous }).single().readText() == report)
                        scenario.recreate()
                        assertEquals(1, broken.hits)
                        instrumentation.removeMonitor(broken)
                        val recovery = monitorSave(goodUri)
                        try {
                            scenario.onActivity { activity ->
                                val owner = androidx.lifecycle.ViewModelProvider(activity)[DiagnosticSnapshotPreparation::class.java]
                                activity.javaClass.getDeclaredMethod("retrySnapshot", String::class.java)
                                    .apply { isAccessible = true }.invoke(activity, checkNotNull(owner.retry.value))
                            }
                            await { saved.readText() == report && (pending.list().orEmpty().toSet() - previous).isEmpty() }
                            assertEquals(1, recovery.hits)
                        } finally {
                            instrumentation.removeMonitor(recovery)
                        }
                    }
                } finally {
                    instrumentation.removeMonitor(broken)
                }
            }
        } finally {
            saved.delete()
        }
    }

    @Test
    fun recentOrFutureOrphansCannotBlockExportAndActiveSnapshotsSurviveReclamation() {
        val pending = File(context.noBackupFilesDir, "pending-export").apply { mkdirs() }
        val active = DiagnosticExports.storePending(context, "Действующий снимок")
        val activeFile = File(pending, active)
        val orphans = List(7) {
            val name = "${java.util.UUID.randomUUID()}.txt"
            File(pending, name).apply {
                writeText("Снимок предыдущего процесса")
                check(setLastModified(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(5)))
            }
        }
        try {
            check(activeFile.setLastModified(1))
            DiagnosticExports.prunePending(context)
            val next = DiagnosticExports.storePending(context, "Новый снимок")
            try {
                assertEquals("Новый снимок", DiagnosticExports.readPending(context, next))
                assertEquals("Действующий снимок", DiagnosticExports.readPending(context, active))
                assertTrue(orphans.any { !it.exists() })
                assertTrue(pending.listFiles().orEmpty().size <= 8)
            } finally {
                DiagnosticExports.deletePending(context, next)
            }
        } finally {
            DiagnosticExports.deletePending(context, active)
            orphans.forEach(File::delete)
        }
        val abandoned = List(7) { File(pending, "${UUID.randomUUID()}.txt").apply { writeText("Готовый файл") } }
        val temporary = File(pending, "${UUID.randomUUID()}.txt.tmp")
        try {
            temporary.writeText("Незавершённая запись")
            check(temporary.setLastModified(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(5)))
            val name = DiagnosticExports.storePending(context, "Экспорт после заполнения квоты")
            try {
                assertFalse(temporary.exists())
                assertEquals("Экспорт после заполнения квоты", DiagnosticExports.readPending(context, name))
            } finally {
                DiagnosticExports.deletePending(context, name)
            }
        } finally {
            temporary.delete()
            abandoned.forEach(File::delete)
        }
    }

    @Test
    fun finishingAfterSafFailureStopsRetainingTheAbandonedSnapshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = SafFixture(context)
        val directory = File(context.noBackupFilesDir, "pending-export")
        val previous = directory.list().orEmpty().toSet()
        val monitor = monitorSave(fixture.uri)
        try {
            SafFixture.assertHealthy(checkNotNull(fixture.reset("open-error").getBundle("previous")))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.exportSnapshot("Закрытая задача ✓") }
                await {
                    var retry = false
                    scenario.onActivity {
                        retry = androidx.lifecycle.ViewModelProvider(it)[DiagnosticSnapshotPreparation::class.java].retry.value != null
                    }
                    retry
                }
                assertEquals("open-error", fixture.healthyState().getString("outcome"))
                scenario.onActivity { it.finish() }
            }
            val file = directory.listFiles().orEmpty().single { it.name !in previous }
            val orphans = List(7) { File(directory, "${UUID.randomUUID()}.txt").apply { writeText("Более новый снимок") } }
            try {
                check(file.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5)))
                val name = DiagnosticExports.storePending(context, "Следующий экспорт")
                try {
                    assertFalse(file.exists())
                    assertEquals("Следующий экспорт", DiagnosticExports.readPending(context, name))
                } finally {
                    DiagnosticExports.deletePending(context, name)
                }
            } finally {
                DiagnosticExports.deletePending(context, file.name.removeSuffix(".writing"))
                orphans.forEach(File::delete)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun MainActivity.exportSnapshot(report: String) {
        javaClass.getDeclaredMethod("saveSnapshot", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(this, report, DiagnosticExports.COMBINED_FILE_NAME)
    }

    private fun monitorSave(uri: Uri?) = InstrumentationRegistry.getInstrumentation().addMonitor(
        IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
            addDataType("text/plain")
            addCategory(Intent.CATEGORY_OPENABLE)
        },
        Instrumentation.ActivityResult(
            if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, Intent().setData(uri),
        ),
        true,
    )

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
    fun parallelLargeTxtAttachmentsKeepIndependentUrisNamesAndBytes() = runBlocking<Unit>(Dispatchers.IO) {
        val resolver = context.contentResolver
        val files = listOf(DiagnosticExports.TRANSPORT_FILE_NAME, DiagnosticExports.COMBINED_FILE_NAME, "adaptive-dpi-audit.txt")
        val written = java.util.concurrent.ConcurrentLinkedQueue<Uri>()
        try {
            val results = files.mapIndexed { index, name ->
                async {
                    val report = "$index\n" + "Синтетические данные. ".repeat(50_000)
                    val chooser = DiagnosticExports.shareChooser(DiagnosticExports.shareIntent(context, name, report), "Поделиться")
                    val uri = checkNotNull(chooser.clipData).getItemAt(0).uri
                    written.add(uri)
                    assertEquals("content", uri.scheme)
                    assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    assertFalse(chooser.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                    checkNotNull(resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)).use {
                        assertTrue(it.moveToFirst())
                        assertEquals(name, it.getString(0))
                    }
                    assertArrayEquals(report.toByteArray(Charsets.UTF_8), checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() })
                    uri
                }
            }.awaitAll()
            assertEquals(files.size, results.toSet().size)
        } finally {
            written.forEach { resolver.delete(it, null, null) }
        }
    }

    @Test
    fun criticalErrorPostsOneNotificationAndOpensItsDiagnosticEvent() {
        assumeTrue("На тестовом Android включите уведомления приложения", notifications.areNotificationsEnabled())
        await { notifications.getNotificationChannel(VpnDiagnostics.CHANNEL_ID) != null }
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

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            ActivityScenario.launch(TransportTestActivity::class.java).use {
                var reportActivity: MainActivity? = null
                try {
                    posted.notification.contentIntent.send()
                    await {
                        var selected = false
                        instrumentation.runOnMainSync {
                            val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<MainActivity>().firstOrNull()
                            if (resumed != null) reportActivity = resumed
                            selected = VpnDiagnostics.eventId(resumed?.intent) == event.id
                        }
                        selected
                    }
                } finally {
                    reportActivity?.let { activity ->
                        instrumentation.runOnMainSync { activity.finish() }
                        await {
                            var destroyed = false
                            instrumentation.runOnMainSync { destroyed = activity.isDestroyed }
                            destroyed
                        }
                    }
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

    @Test(timeout = 75_000)
    fun armsVpnForExternalProcessDeath() {
        val target = checkNotNull(InstrumentationRegistry.getArguments().getString("transportTarget"))
        ActivityScenario.launch(TransportTestActivity::class.java).use { scenario ->
            try {
                scenario.onActivity {
                    assertEquals(null, VpnService.prepare(it))
                    assertEquals(null, VpnController.requestStart(it, target))
                }
                await { VpnController.state.value == VpnState.EngineRunning }
                val session = checkNotNull(VpnController.currentSessionId)
                assertFixtureEcho(target)
                assertTrue(androidVpnExists())
                await { File(context.noBackupFilesDir, "diagnostics/active-vpn").length() > 0L }
                Log.i("VpnRecoveryTest", "READY ${android.os.Process.myPid()} $session")
                CountDownLatch(1).await(40, TimeUnit.SECONDS)
                error("Управляющий сценарий не уничтожил процесс")
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { VpnController.stop() }
            }
        }
    }

    @Test
    fun anAbortedVpnProcessIsReportedAfterTheNextLaunch() {
        val argument = InstrumentationRegistry.getArguments().getString("recoveredVpnError")
        assumeTrue("Тест запускается после отдельного аварийного завершения приложения", argument != null)
        val code = DiagnosticCode.valueOf(checkNotNull(argument))
        val session = InstrumentationRegistry.getArguments().getString("recoveredVpnSession")?.let(UUID::fromString)
        val target = InstrumentationRegistry.getArguments().getString("transportTarget")
        val expected: (DiagnosticEvent) -> Boolean = { it.code == code && !it.acknowledged && (session == null || it.id == session) }
        await { VpnDiagnostics.events.value.any(expected) }
        val event = VpnDiagnostics.events.value.first(expected)
        try {
            assertTrue(event.vpnStopped)
            assertEquals(VpnState.Idle, VpnController.state.value)
            assertTrue(event.report().contains(event.id.toString()))
            val type = if (code == DiagnosticCode.AppCrash) VpnEventType.ProcessCrash else VpnEventType.InterruptedSession
            await { VpnEventHistory.state.value.entries.any { it.id == event.id && it.type == type } }
            if (session != null && target != null) {
                val oldPid = checkNotNull(InstrumentationRegistry.getArguments().getString("oldVpnPid")).toInt()
                assertTrue(android.os.Process.myPid() != oldPid)
                ActivityScenario.launch(MainActivity::class.java).use {
                    await { notifications.activeNotifications.any { notice -> notice.tag == session.toString() } }
                    assertFalse(androidVpnExists())
                    assertFixtureEcho(target)
                    assertEquals(1, VpnDiagnostics.events.value.count { event -> event.id == session })
                    assertEquals(1, VpnEventHistory.state.value.entries.count { event -> event.id == session && event.type == type })
                }
            }
        } finally {
            VpnDiagnostics.acknowledge(event.id)
        }
    }

    private fun androidVpnExists(): Boolean {
        val manager = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        return manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    private fun assertFixtureEcho(target: String) {
        val expected = "vpn-system-recovery ✓".toByteArray()
        Socket(Proxy.NO_PROXY).use { socket ->
            socket.soTimeout = 5_000
            socket.connect(InetSocketAddress(target, 39052), 5_000)
            socket.getOutputStream().write(expected)
            val received = ByteArray(expected.size)
            DataInputStream(socket.getInputStream()).readFully(received)
            assertArrayEquals(expected, received)
        }
    }

    private fun record(code: DiagnosticCode): DiagnosticEvent {
        val previous = VpnDiagnostics.events.value.map { it.id }.toSet()
        VpnDiagnostics.record(code)
        await { VpnDiagnostics.events.value.any { it.id !in previous && it.code == code } }
        return VpnDiagnostics.events.value.first { it.id !in previous && it.code == code }
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
