package org.adaptivedpi.android.vpn.diagnostics

import android.app.ApplicationExitInfo
import org.adaptivedpi.android.vpn.VpnState
import org.adaptivedpi.android.engine.RelayShutdownStatus
import org.adaptivedpi.android.engine.SocketCloseSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

class DiagnosticCenterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val idle = VpnSnapshot(VpnState.Idle)
    private val running = VpnSnapshot(VpnState.TestSessionRunning, KnownFlag.Yes, KnownFlag.Yes)

    @Test
    fun writesAndReadsAnEventWithItsMetadataAndAcknowledgement() {
        val journal = DiagnosticJournal(temporary.root)
        val original = center(journal)
        val event = original.record(DiagnosticCode.TunnelFailed, running, IOException())
        original.acknowledge(event.id)

        val read = center(DiagnosticJournal(temporary.root)).events.value.single()
        assertEquals(event.id, read.id)
        assertEquals(event.time, read.time)
        assertEquals(DiagnosticCode.TunnelFailed, read.code)
        assertEquals("0.1.0", read.appVersion)
        assertEquals("16", read.androidVersion)
        assertEquals(running, read.vpn)
        assertTrue(read.acknowledged)
    }

    @Test
    fun recoveryWriteFailurePreservesCrashSlotUntilItCanBeRecovered() {
        val directory = temporary.root
        val journal = DiagnosticJournal(directory)
        center(journal).captureCrash(running, IllegalStateException())
        val crash = checkNotNull(journal.readCrash())
        assertTrue(File(directory, "journal.tmp").mkdir())

        val firstRecovery = center(journal)
        assertEquals(crash.id, firstRecovery.events.value.single().id)
        assertEquals(crash.id, journal.readCrash()?.id)
        assertTrue(journal.read().isEmpty())

        val nextRecovery = center(journal)
        assertEquals(crash.id, nextRecovery.events.value.single().id)
        assertEquals(crash.id, journal.read().single().id)
        assertEquals(null, journal.readCrash())
    }

    @Test
    fun unreadableHistoryIsRetriedAndMergedBeforeNewEventsReplaceIt() {
        val directory = temporary.root
        val journal = DiagnosticJournal(directory)
        val original = center(journal)
        val old = original.record(DiagnosticCode.SaveFailed, idle)
        original.captureCrash(running, IOException())
        val crash = checkNotNull(journal.readCrash())
        val history = File(directory, "journal").toPath()
        val backup = File(directory, "journal-test-original").toPath()
        Files.move(history, backup)
        Files.createDirectory(history)

        val recovering = center(journal)
        assertEquals(crash.id, recovering.events.value.single().id)
        assertEquals(crash.id, journal.readCrash()?.id)
        Files.delete(history)
        Files.move(backup, history)
        val fresh = recovering.record(DiagnosticCode.ShareFailed, idle)

        assertEquals(listOf(fresh.id, crash.id, old.id), journal.read().map { it.id })
        val reloaded = center(journal)
        assertEquals(setOf(fresh.id, crash.id, old.id), reloaded.events.value.map { it.id }.toSet())
        assertEquals(null, journal.readCrash())
    }

    @Test
    fun recoveringActiveMarkerDoesNotDiscardAnUnreadCrashSlot() {
        val directory = temporary.root
        val journal = DiagnosticJournal(directory)
        val prior = center(journal)
        val session = UUID.randomUUID()
        prior.markActive(running, session)
        prior.captureCrash(running, IOException(), session)
        val crash = checkNotNull(journal.readCrash())
        val crashSlot = File(directory, "last-crash").toPath()
        val backup = File(directory, "last-crash-test-original").toPath()
        Files.move(crashSlot, backup)
        Files.createDirectory(crashSlot)

        assertEquals(DiagnosticCode.VpnInterrupted, center(journal).events.value.single().code)
        assertTrue(Files.isDirectory(crashSlot))
        Files.delete(crashSlot)
        Files.move(backup, crashSlot)
        val recovered = center(journal).events.value.single()
        assertEquals(crash.id, recovered.id)
        assertEquals(DiagnosticCode.AppCrash, recovered.code)
        assertEquals(null, journal.readCrash())
    }

    @Test
    fun theDeadlineAndFinalRelayStateAreIndependentAndSurviveJournalReload() {
        val journal = DiagnosticJournal(temporary.root)
        val details = VpnCleanupDetails(UUID.randomUUID(), 4_031, true, true, 2,
            RelayShutdownStatus(0, 0, 0, 0, 0), 0, 0, emptyList())
        assertFalse(details.incomplete)
        assertTrue(details.copy(relay = RelayShutdownStatus(4, 0, 0, 1, 0)).incomplete)
        assertTrue(details.copy(issues = listOf(CleanupIssue(CleanupStep.TunClose, ExceptionKind.IOException))).incomplete)
        center(journal).record(DiagnosticCode.VpnCleanupDelayed, idle, cleanup = details)

        val read = center(DiagnosticJournal(temporary.root)).events.value.single()
        assertEquals(details, read.cleanup)
        assertTrue(read.report().contains("RELAY_STOP, пул запросов"))
        assertTrue(read.report().contains("Релей при завершении службы: завершён"))
        assertTrue(read.report().contains(details.session.toString()))
    }

    @Test
    fun socketCloseWarningDoesNotBlockWhenTheRetryConfirmedRelease() {
        val base = VpnCleanupDetails(UUID.randomUUID(), 10, true, true, null,
            RelayShutdownStatus(0, 0, 0, 0, 0), 0, 0, emptyList())
        val retried = base.copy(sockets = SocketCloseSummary(closed = 3, failed = 1, unconfirmed = 0))
        assertFalse(retried.incomplete)
        assertEquals(retried, VpnCleanupDetails.decode(retried.encode()))
        assertTrue(retried.text().contains("ошибки закрытия 1"))
        assertTrue(base.copy(sockets = SocketCloseSummary(failed = 1, unconfirmed = 1)).incomplete)

        // Запись старого формата без пятого поля: ошибка закрытия по-прежнему считается неподтверждённой.
        val legacy = retried.encode().replace(";3,0,1,0,0;", ";3,0,1,0;")
        assertTrue(VpnCleanupDetails.decode(legacy).incomplete)
    }

    @Test
    fun recordWrittenBeforeCodeCleanupWithRetainedValuesIsStillReadable() {
        // Строка в формате журнала до очистки 1.2: значения EngineHung и EngineMonitor сохранены ради неё.
        val id = UUID.randomUUID()
        val cleanup = "-;4100;true;true;-;0;0;-;EngineMonitor:IOException,EngineTunnel:Other"
        File(temporary.root, "journal").writeText(listOf(
            "1", id, System.currentTimeMillis(), "EngineHung", "0.1.0", 34, "14", "Stopping", "Yes", "No",
            "IOException", false, true, "", cleanup,
        ).joinToString("\t") + "\n")

        val read = DiagnosticJournal(temporary.root).read().single()
        assertEquals(id, read.id)
        assertEquals(DiagnosticCode.EngineHung, read.code)
        assertEquals(listOf(CleanupStep.EngineMonitor, CleanupStep.EngineTunnel), read.cleanup?.issues?.map { it.step })
        assertTrue(read.report().contains("EngineMonitor"))
    }

    @Test
    fun rotatesByCountAndPreservesTheMostRecentItems() {
        val journal = DiagnosticJournal(temporary.root, maxEntries = 3)
        val center = center(journal)
        val ids = List(6) { center.record(DiagnosticCode.ConsentFailed, idle).id }
        assertEquals(ids.takeLast(3).reversed(), journal.read().map(DiagnosticEvent::id))
    }

    @Test
    fun rotatesByByteSizeAndKeepsTheLatestEntry() {
        val journal = DiagnosticJournal(temporary.root, maxBytes = 360)
        val center = center(journal)
        val ids = List(8) { center.record(DiagnosticCode.VpnCleanupFailed, running).id }
        assertTrue(File(temporary.root, "journal").length() in 1L..360L)
        assertEquals(ids.last(), journal.read().first().id)
        assertTrue(journal.read().size < ids.size)
    }

    @Test
    fun persistenceAndNotificationFailuresDoNotCrashTheRecorder() {
        val journal = DiagnosticJournal(temporary.newFile())
        val center = center(journal) { throw SecurityException("Запрет отправки") }
        val event = center.record(DiagnosticCode.VpnUnexpectedStop, running)
        assertEquals(event.id, center.events.value.single().id)
        assertFalse(center.events.value.single().notified)

        center.acknowledge(event.id)
        assertTrue(center.events.value.single().acknowledged)
        assertNotNull(center.record(DiagnosticCode.SaveFailed, idle))
    }

    @Test
    fun crashIsRecoveredOnceAndMatchedWithTheActiveSessionMarker() {
        val journal = DiagnosticJournal(temporary.root)
        var notifications = 0
        val first = center(journal) { notifications++; true }
        first.markActive(running)
        first.captureCrash(running, IllegalStateException("первый crash"))
        first.captureCrash(running, IllegalArgumentException("повторный callback"))
        assertEquals(0, notifications)

        val restarted = center(journal)
        val crash = restarted.events.value.single()
        assertEquals(DiagnosticCode.AppCrash, crash.code)
        assertEquals(ExceptionKind.IllegalStateException, crash.exceptionKind)
        assertTrue(crash.vpnStopped)
        restarted.acknowledge(crash.id)
        assertEquals(crash.id, center(journal).events.value.single().id)
        assertTrue(center(journal).events.value.single().acknowledged)
    }

    @Test
    fun abandonedProcessLeavesMarkerButAnOrdinaryStopDoesNot() {
        val journal = DiagnosticJournal(temporary.root)
        val first = center(journal)
        first.markActive(running)
        first.clearActive()
        assertTrue(center(journal).events.value.isEmpty())

        first.markActive(running)
        val afterKill = center(journal).events.value.single()
        assertEquals(DiagnosticCode.VpnInterrupted, afterKill.code)
        assertTrue(afterKill.connectionNotice().contains("может остаться недоступным"))
        assertEquals(afterKill.id, center(journal).events.value.single().id)
    }

    @Test
    fun anEventRemainsUnreadWhenNotificationsAreDeniedAndIsPostedOnlyOnceAfterPermission() {
        val journal = DiagnosticJournal(temporary.root)
        var posted = 0
        val denied = center(journal) { false }
        val event = denied.record(DiagnosticCode.EngineHung, running)
        assertFalse(denied.events.value.single().acknowledged)
        assertFalse(denied.events.value.single().notified)

        val allowed = center(journal) { posted++; true }
        allowed.notifyOnce(event.id)
        allowed.notifyOnce(event.id)
        center(journal) { posted++; true }.notifyOnce(event.id)
        assertEquals(1, posted)
    }

    @Test
    fun reportAndJournalExcludeExceptionMessagesFileNamesAndUntrustedFrames() {
        val message = "https://private.example/path password=hunter cookie=abc token=secret"
        val cause = IOException(message).apply {
            stackTrace = arrayOf(
                StackTraceElement("org.adaptivedpi.android.vpn.AdaptiveVpnService", "startTestSession", "private-path", 24),
                StackTraceElement("com.private.application", "tokenSecret", "Cookie123", 42),
            )
        }
        val journal = DiagnosticJournal(temporary.root)
        val event = DiagnosticCenter(journal, "token-secret", 36, "release-secret") { false }
            .record(DiagnosticCode.TunnelFailed, running, cause)
        val report = journal.read().single().report()
        assertTrue(report.contains(event.id.toString()))
        assertTrue(report.contains("AdaptiveVpnService.startTestSession:24"))
        assertTrue(report.contains("кадр скрыт:42"))
        assertTrue(report.contains("Версия Android"))
        assertTrue(report.contains("Дата и время"))
        val bytesOnDisk = File(temporary.root, "journal").readText()
        for (secret in listOf("private.example", "hunter", "token=secret", "private-path",
            "com.private.application", "tokenSecret", "Cookie123", "token-secret", "release-secret")) {
            assertFalse(report.contains(secret))
            assertFalse(bytesOnDisk.contains(secret))
        }
    }

    @Test
    fun expiredRecordsAndCrashSlotAreDeletedFromTheDisk() {
        var now = 10_000L
        val journal = DiagnosticJournal(temporary.root, maxAgeMillis = 500, clock = { now })
        val first = center(journal)
        val expired = first.record(DiagnosticCode.ConsentFailed, idle)
        first.captureCrash(idle, IllegalStateException())
        now += 400
        val fresh = first.record(DiagnosticCode.SaveFailed, idle)
        now += 100

        val reloaded = center(DiagnosticJournal(temporary.root, maxAgeMillis = 500, clock = { now }))
        assertEquals(listOf(fresh.id), reloaded.events.value.map(DiagnosticEvent::id))
        assertFalse(File(temporary.root, "last-crash").exists())
        assertFalse(File(temporary.root, "journal").readText().contains(expired.id.toString()))

        now += 500
        reloaded.prune()
        assertTrue(reloaded.events.value.isEmpty())
        assertEquals("", File(temporary.root, "journal").readText())
    }

    @Test
    fun clockMovedBackwardsKeepsRecentRecordsAndCrashSlot() {
        var now = 10_000L
        val journal = DiagnosticJournal(temporary.root, maxAgeMillis = 500, clock = { now })
        val first = center(journal)
        val recent = first.record(DiagnosticCode.SaveFailed, idle)
        first.captureCrash(idle, IllegalStateException())
        now -= 1_000

        assertEquals(listOf(recent.id), journal.read().map(DiagnosticEvent::id))
        assertNotNull(journal.readCrash())
        now += 1_000
        assertEquals(listOf(recent.id), journal.read().map(DiagnosticEvent::id))

        now += 500
        assertTrue(journal.read().isEmpty())
    }

    @Test
    fun aPersistedCriticalReasonReplacesTheInterruptedSessionMarker() {
        val journal = DiagnosticJournal(temporary.root)
        val first = center(journal)
        first.markActive(running)
        val marker = checkNotNull(journal.readActive())
        val id = first.persistCritical(DiagnosticCode.EngineHung, running, null, marker.id)
        assertEquals(marker.id, id)

        val recovered = center(journal).events.value.single()
        assertEquals(DiagnosticCode.EngineHung, recovered.code)
        assertEquals(id, recovered.id)
    }

    @Test
    fun aLateCriticalWriteCannotClearTheMarkerOfANewerSession() {
        val journal = DiagnosticJournal(temporary.root)
        val first = center(journal)
        first.markActive(running)
        val old = checkNotNull(journal.readActive())
        first.markActive(running)
        val next = checkNotNull(journal.readActive())

        first.persistCritical(DiagnosticCode.EngineHung, running, null, old.id)
        assertEquals(next.id, journal.readActive()?.id)
    }

    @Test
    fun repeatedFaultsKeepJournalEntriesButShareAnAlertCooldown() {
        var now = 100_000L
        var notifications = 0
        val journal = DiagnosticJournal(temporary.root, clock = { now })
        val first = center(journal) { notifications++; true }
        first.record(DiagnosticCode.EngineHung, running)
        now += 1_000
        val second = center(journal) { notifications++; true }
        second.record(DiagnosticCode.EngineHung, running)
        assertEquals(2, second.events.value.size)
        assertEquals(1, notifications)
        now += 61_000
        second.record(DiagnosticCode.EngineHung, running)
        assertEquals(2, notifications)
    }

    @Test
    fun processExitsWithTheSameTimeAreAllRecordedAndRereadingAddsNothing() {
        val now = 1_000_000_000L
        val journal = DiagnosticJournal(temporary.root, clock = { now })
        val records = listOf(
            ProcessExitRecord(11, now - 5_000, ApplicationExitInfo.REASON_CRASH_NATIVE, "app"),
            ProcessExitRecord(12, now - 5_000, ApplicationExitInfo.REASON_CRASH_NATIVE, "app"),
            ProcessExitRecord(11, now - 5_000, ApplicationExitInfo.REASON_ANR, "app"),
        )
        var notifications = 0
        val first = center(journal) { notifications++; true }
        first.recordProcessExits(records)
        first.recordProcessExits(records)

        assertEquals(3, first.events.value.size)
        assertEquals(setOf(now - 5_000), first.events.value.map { it.timeMillis }.toSet())
        assertEquals(3, journal.read().size)
        val restarted = center(DiagnosticJournal(temporary.root, clock = { now }))
        restarted.recordProcessExits(records)
        assertEquals(3, restarted.events.value.size)
        assertEquals(0, notifications)
    }

    @Test
    fun onlyReasonsTheCrashHandlerMissesAreRecordedAndOldRecordsAreSkipped() {
        val now = 10L * 24 * 60 * 60 * 1_000
        val week = 7L * 24 * 60 * 60 * 1_000
        val journal = DiagnosticJournal(temporary.root, clock = { now })
        val center = center(journal)
        val wanted = mapOf(
            ApplicationExitInfo.REASON_CRASH_NATIVE to DiagnosticCode.ProcessExitNative,
            ApplicationExitInfo.REASON_ANR to DiagnosticCode.ProcessExitAnr,
            ApplicationExitInfo.REASON_LOW_MEMORY to DiagnosticCode.ProcessExitLowMemory,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to DiagnosticCode.ProcessExitResources,
            ApplicationExitInfo.REASON_SIGNALED to DiagnosticCode.ProcessExitSignaled,
        )
        val ignored = listOf(
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_UNKNOWN,
        )
        val records = (wanted.keys + ignored).mapIndexed { index, reason ->
            ProcessExitRecord(100 + index, now - 1_000L - index, reason, "app")
        } + ProcessExitRecord(1, now - week - 1, ApplicationExitInfo.REASON_ANR, "app")
        center.recordProcessExits(records)

        assertEquals(wanted.values.toSet(), center.events.value.map { it.code }.toSet())
        assertEquals(wanted.size, center.events.value.size)
        assertTrue(center.events.value.none { it.requiresNotification || it.vpnStopped })
    }

    @Test
    fun processExitIsRecordedOnceEvenWhenTheFirstWriteFailed() {
        val now = 1_000_000_000L
        val journal = DiagnosticJournal(temporary.root, clock = { now })
        val record = ProcessExitRecord(7, now - 100, ApplicationExitInfo.REASON_LOW_MEMORY, "app")
        val blocker = File(temporary.root, "journal.tmp")
        assertTrue(blocker.mkdir())
        center(journal).recordProcessExits(listOf(record))
        assertTrue(journal.read().isEmpty())

        // Неудачная запись сама убирает пустой каталог-помеху (finally в writeDiagnosticFile): дальше путь свободен.
        blocker.delete()
        assertFalse(blocker.exists())
        val retry = center(DiagnosticJournal(temporary.root, clock = { now }))
        retry.recordProcessExits(listOf(record))
        center(DiagnosticJournal(temporary.root, clock = { now })).recordProcessExits(listOf(record))
        assertEquals(listOf(ExitReasonReader.idOf(record)), journal.read().map { it.id })
    }

    private fun center(journal: DiagnosticJournal, notify: (DiagnosticEvent) -> Boolean = { false }) =
        DiagnosticCenter(journal, "0.1.0", 36, "16", notify = notify)
}
