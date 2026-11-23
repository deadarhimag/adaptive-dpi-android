package org.adaptivedpi.android.vpn.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.adaptivedpi.android.vpn.VpnEvent
import org.adaptivedpi.android.vpn.VpnLifecycle
import org.adaptivedpi.android.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

class VpnEventHistoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun preservesClockOffsetSessionAndInsertionOrderAcrossProcesses() = runBlocking {
        val store = VpnEventStore(File(folder.root, "events"))
        val session = UUID.randomUUID()
        val time = OffsetDateTime.parse("2026-11-22T10:30:00.123+03:00")
        val start = VpnJournalEvent.create(VpnEventType.StartRequested, session, time = time)
        val running = VpnJournalEvent.create(VpnEventType.Started, session, time = time)
        val stopped = VpnJournalEvent.create(VpnEventType.Stopped, session, time = time)
        withWriter(store) { writer ->
            assertTrue(writer.record(start))
            assertTrue(writer.record(running))
            assertTrue(writer.record(running))
            assertTrue(writer.record(stopped))
            assertEquals(listOf(stopped, running, start), writer.awaitEntries(3))
        }
        withWriter(store) { writer ->
            assertEquals(listOf(stopped, running, start), writer.awaitEntries(3))
        }
    }

    @Test
    fun usesTheActiveSessionWhenAnOlderStartAppearsAfterTheNextRequest() = runBlocking {
        val store = VpnEventStore(File(folder.root, "events"), maxEntries = 5)
        withWriter(store) { writer ->
            val lifecycle = VpnLifecycle { assertTrue(writer.record(it)) }
            val previousSession = UUID(0, 1)
            val nextSession = UUID(0, 2)
            val previous = "process:$previousSession"
            val next = "process:$nextSession"
            fun request(id: String) {
                assertTrue(lifecycle.beginConsent(id))
                lifecycle.update(id, VpnEvent.StartRequested)
                assertTrue(lifecycle.attachService(id) {})
            }

            withTimeout(5_000) { writer.state.first { it.loaded } }
            // Записи публикации ждут в той же очереди корутин до первого ожидания состояния.
            request(previous)
            lifecycle.update(previous, VpnEvent.ServiceStarted)
            lifecycle.stop()
            lifecycle.serviceEnded(previous, VpnEvent.ServiceDestroyed)
            assertNull(lifecycle.currentSessionId)
            val before = writer.state.value.entries.map { it.id }.toSet()

            request(next)
            assertEquals(nextSession, lifecycle.currentSessionId)
            val delayed = withTimeout(5_000) {
                writer.state.first { state ->
                    state.entries.any { it.type == VpnEventType.StartRequested && it.sessionId == nextSession }
                }
            }
            assertEquals(previousSession, delayed.entries.first {
                it.type == VpnEventType.Started && it.id !in before
            }.sessionId)
            assertFalse(delayed.entries.any {
                it.type == VpnEventType.Started && it.sessionId == lifecycle.currentSessionId
            })

            lifecycle.update(next, VpnEvent.ServiceStarted)
            val started = withTimeout(5_000) {
                writer.state.first { state ->
                    state.entries.any { it.type == VpnEventType.Started && it.sessionId == nextSession }
                }
            }
            assertEquals(nextSession, started.entries.single {
                it.type == VpnEventType.Started && it.sessionId == lifecycle.currentSessionId
            }.sessionId)

            val rotation = List(6) { VpnJournalEvent.create(VpnEventType.ActivityRestored) }
            rotation.forEach { assertTrue(writer.record(it)) }
            val bounded = withTimeout(5_000) { writer.state.first { it.entries.first() == rotation.last() } }
            assertEquals(5, bounded.entries.size)
            assertFalse(bounded.entries.any { it.type == VpnEventType.Started })
            assertEquals(nextSession, lifecycle.currentSessionId)
        }
    }

    @Test
    fun clearIsPersistedAndDoesNotDeleteSubsequentEventsOrErrorLog() = runBlocking {
        val store = VpnEventStore(File(folder.root, "events"))
        val errorLog = DiagnosticJournal(File(folder.root, "errors"))
        val error = DiagnosticEvent.create(DiagnosticCode.ConsentFailed, "0.1.0", 36, VpnSnapshot(VpnState.StartFailed))
        errorLog.write(listOf(error))
        val start = VpnJournalEvent.create(VpnEventType.Started)
        val stop = VpnJournalEvent.create(VpnEventType.Stopped)
        withWriter(store) { writer ->
            writer.record(start)
            assertTrue(writer.clear())
            assertTrue(store.read().isEmpty())
            writer.record(stop)
            assertEquals(listOf(stop), writer.awaitEntries(1))
        }
        assertEquals(listOf(stop), store.read())
        assertEquals(listOf(error), errorLog.read())
    }

    @Test
    fun rotatesByEntryCountAndByEncodedFileSize() {
        val countFile = File(folder.root, "count")
        val bytesFile = File(folder.root, "bytes")
        val session = UUID.randomUUID()
        val events = (1..5).map {
            VpnJournalEvent.create(VpnEventType.Started, session, time = OffsetDateTime.parse("2026-11-22T10:30:0$it+03:00"))
        }.reversed()
        val countStore = VpnEventStore(countFile, maxEntries = 3)
        countStore.write(events)
        assertEquals(events.take(3), countStore.read())

        val bytesStore = VpnEventStore(bytesFile, maxBytes = 250)
        bytesStore.write(events)
        assertEquals(events.take(2), bytesStore.read())
        assertTrue(bytesFile.length() <= 250)
    }

    @Test
    fun failedDiskWritesKeepLiveEventsAndDoNotStopFutureWrites() = runBlocking {
        val directory = File(folder.root, "temporarily-unavailable")
        directory.writeText("занято")
        val store = VpnEventStore(File(directory, "events"))
        val start = VpnJournalEvent.create(VpnEventType.Started)
        val stop = VpnJournalEvent.create(VpnEventType.Stopped)
        withWriter(store) { writer ->
            writer.record(start)
            assertEquals(listOf(start), writer.awaitEntries(1))
            assertTrue(writer.state.value.storageError)
            assertFalse(writer.clear())
            assertEquals(listOf(start), writer.state.value.entries)

            assertTrue(directory.delete())
            writer.record(stop)
            assertEquals(listOf(stop, start), writer.awaitEntries(2))
            assertFalse(writer.state.value.storageError)
        }
        assertEquals(listOf(stop, start), store.read())
    }

    @Test
    fun criticalEventSurvivesOrdinaryRotationDuringDiskFailureAndIsSavedOnRecovery() = runBlocking {
        val directory = File(folder.root, "blocked-history")
        directory.writeText("занято")
        val store = VpnEventStore(File(directory, "events"), maxEntries = 8)
        val critical = VpnJournalEvent.create(VpnEventType.StopError, code = DiagnosticCode.VpnCleanupFailed)
        val next = VpnJournalEvent.create(VpnEventType.StartRequested)
        withWriter(store) { writer ->
            assertTrue(writer.record(critical))
            val ordinary = List(12) { VpnJournalEvent.create(VpnEventType.ActivityRestored) }
            ordinary.forEach { assertTrue(writer.record(it)) }
            val snapshot = withTimeout(5_000) { writer.state.first { it.entries.firstOrNull() == ordinary.last() } }
            assertTrue(snapshot.storageError)
            assertTrue(snapshot.entries.contains(critical))
            assertEquals(0L, snapshot.droppedCritical)

            assertTrue(directory.delete())
            assertTrue(writer.record(next))
            withTimeout(5_000) { writer.state.first { !it.storageError && it.entries.firstOrNull() == next } }
            assertTrue(store.read().contains(critical))
        }
    }

    @Test
    fun failedReadIsReportedAndDamagedLinesDoNotHideValidRows() = runBlocking {
        val file = folder.newFolder("not-a-file")
        val event = VpnJournalEvent.create(VpnEventType.Started)
        val store = VpnEventStore(file)
        withWriter(store) { writer ->
            assertTrue(withTimeout(5_000) { writer.state.first { it.loaded } }.storageError)
        }

        assertTrue(file.delete())
        store.write(listOf(event))
        file.appendText("повреждённая строка\n")
        assertEquals(listOf(event), store.read())
    }

    @Test
    fun retriesInitialReadBeforeReplacingOlderHistory() = runBlocking {
        val file = folder.newFolder("unavailable")
        val previous = VpnJournalEvent.create(VpnEventType.Started)
        val restored = VpnJournalEvent.create(VpnEventType.ActivityRestored)
        val stop = VpnJournalEvent.create(VpnEventType.Stopped)
        val store = VpnEventStore(file)
        withWriter(store) { writer ->
            writer.record(restored)
            assertEquals(listOf(restored), writer.awaitEntries(1))
            assertTrue(writer.state.value.storageError)
            assertTrue(file.delete())
            store.write(listOf(previous))
            writer.record(stop)
            assertEquals(listOf(stop, restored, previous), writer.awaitEntries(3))
            assertFalse(writer.state.value.storageError)
        }
    }

    @Test
    fun aStaleActiveMarkerDoesNotReportAnAlreadyStoppedSessionAsInterrupted() = runBlocking {
        val session = UUID.randomUUID()
        val stopped = VpnJournalEvent.create(VpnEventType.Stopped, session)
        val other = VpnJournalEvent.create(VpnEventType.InterruptedSession, UUID.randomUUID())
        val store = VpnEventStore(File(folder.root, "events"))
        store.write(listOf(stopped))
        withWriter(store) { writer ->
            writer.record(VpnJournalEvent.create(VpnEventType.InterruptedSession, session))
            writer.record(other)
            assertEquals(listOf(other, stopped), writer.awaitEntries(2))
        }
    }

    @Test
    fun confirmedCrashSupersedesRecoveredInterruptionWithTheSameId() = runBlocking {
        val store = VpnEventStore(File(folder.root, "events"))
        val session = UUID.randomUUID()
        val interrupted = VpnJournalEvent.create(VpnEventType.InterruptedSession, session, id = session)
        val crash = VpnJournalEvent.create(VpnEventType.ProcessCrash, id = session)
        store.write(listOf(interrupted))
        withWriter(store) { writer ->
            assertTrue(writer.record(crash))
            withTimeout(5_000) { writer.state.first { it.entries == listOf(crash) } }
        }
        assertEquals(listOf(crash), store.read())
    }

    private suspend fun VpnEventWriter.awaitEntries(count: Int) =
        withTimeout(5_000) { state.first { it.loaded && it.entries.size >= count }.entries }

    private suspend fun CoroutineScope.withWriter(store: VpnEventStore, test: suspend (VpnEventWriter) -> Unit) {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            test(VpnEventWriter(store, scope))
        } finally {
            scope.cancel()
        }
    }
}
