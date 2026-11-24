package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.vpn.VpnState
import org.adaptivedpi.android.vpn.TransportSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.OffsetDateTime
import java.util.UUID

class CombinedDiagnosticReportTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun includesMetadataAndBothJournalsWithRedactedErrorDetails() {
        val session = UUID.randomUUID()
        val time = OffsetDateTime.parse("2026-11-22T10:30:00+03:00")
        val event = VpnJournalEvent.create(VpnEventType.Started, session, time = time)
        val error = DiagnosticEvent.create(
            DiagnosticCode.TunnelFailed, "0.1.0", 36, VpnSnapshot(VpnState.StartFailed),
            IllegalStateException("Cookie=session-secret https://private.example/access?token=secret"),
        )
        val report = combinedDiagnosticReport(
            "0.1.0", "16", 36, "Pixel 7\u0000", VpnState.TestSessionRunning,
            VpnEventHistoryState(listOf(event), loaded = true), listOf(error),
        )

        val expected = listOf(
            "Версия приложения: 0.1.0", "Версия Android: 16 (API 36)", "Модель устройства: Pixel 7",
            "Состояние VPN: TestSessionRunning", "Сетевой движок: пока не подключён",
            "Журнал событий VPN", "2026-11-22T10:30:00+03:00 · INFO", "Сеанс: $session",
            "Журнал ошибок", error.id.toString(), "Исключение: IllegalStateException",
        )
        expected.forEach { assertTrue(it, report.contains(it)) }
        listOf("Cookie=", "session-secret", "private.example", "token=", "\u0000").forEach {
            assertFalse(it, report.contains(it))
        }
    }

    @Test
    fun explainsMissingEntriesAndHistoryReadFailure() {
        val report = combinedDiagnosticReport(
            null, "16", 36, null, VpnState.Idle, VpnEventHistoryState(loaded = true, storageError = true), emptyList(),
        )
        assertTrue(report.contains("Модель устройства: неизвестна"))
        assertTrue(report.contains("Хранилище журнала недоступно"))
        assertTrue(report.contains("Журнал ошибок\nЗаписей нет."))
    }

    @Test
    fun buildIdentityDistinguishesApksWithTheSameVersionName() {
        fun report(build: BuildIdentity) = combinedDiagnosticReport(
            "0.1.1", "16", 36, "Pixel 7", VpnState.Idle,
            VpnEventHistoryState(loaded = true, unreadableRecords = 2), emptyList(), build = build,
        )
        val first = report(BuildIdentity(25, "41", "a1b2c3d4e5f6", false))
        val second = report(BuildIdentity(26, "42", "0f1e2d3c4b5a", true))
        assertTrue(first.contains("Сборка: код версии 25, запуск CI 41, коммит a1b2c3d4e5f6"))
        assertTrue(second.contains("Сборка: код версии 26, запуск CI 42, коммит 0f1e2d3c4b5a, есть локальные изменения"))
        assertTrue(first.contains("Повреждённые записи на диске: 2. История неполная."))
        val local = report(BuildIdentity(null, "local", "evil\nline", false))
        assertTrue(local.contains("Сборка: код версии неизвестен, локальная сборка, коммит неизвестен"))
        assertFalse(local.contains("evil"))
    }

    @Test
    fun includesAggregatesAndDistinguishesEngineReadinessFromBidirectionalTraffic() {
        val transport = TransportSnapshot(true, 3, 2, 30, 0, 1, 2)
        val report = combinedDiagnosticReport(
            "0.1.1", "16", 36, "Pixel 7", VpnState.EngineRunning,
            VpnEventHistoryState(), emptyList(), transport,
        )
        assertTrue(report.contains("локальный транспорт запущен"))
        assertTrue(report.contains("Передача данных в обе стороны: ожидается"))
        assertTrue(report.contains("Пакеты TUN: отправлено 3, получено 2"))
        assertTrue(transport.copy(receivedBytes = 24).transferred)
        assertFalse(transport.transferred)
    }

    @Test
    fun errorRecoveryProvidesTheOldSessionOnce() {
        val journal = DiagnosticJournal(folder.root)
        val session = UUID.randomUUID()
        val snapshot = VpnSnapshot(VpnState.TestSessionRunning)
        DiagnosticCenter(journal, "0.1.0", 36, "16") { false }.apply {
            markActive(snapshot, session)
            captureCrash(snapshot, IllegalStateException("secret"), session)
        }

        val (event, recoveredSession) = DiagnosticCenter(journal, "0.1.0", 36, "16") { false }.recovered.single()
        assertTrue(event.code == DiagnosticCode.AppCrash)
        assertTrue(recoveredSession == session)
        assertTrue(DiagnosticCenter(journal, "0.1.0", 36, "16") { false }.recovered.isEmpty())
    }

    @Test
    fun aCrashDoesNotInheritTheMarkerOfAnUnrelatedSession() {
        val journal = DiagnosticJournal(folder.root)
        val snapshot = VpnSnapshot(VpnState.TestSessionRunning)
        DiagnosticCenter(journal, "0.1.0", 36, "16") { false }.apply {
            markActive(snapshot, UUID.randomUUID())
            captureCrash(snapshot, IllegalStateException("secret"), UUID.randomUUID())
        }
        val recovered = DiagnosticCenter(journal, "0.1.0", 36, "16") { false }.recovered.single()
        assertTrue(recovered.first.code == DiagnosticCode.AppCrash)
        assertTrue(recovered.second == null)
    }
}
