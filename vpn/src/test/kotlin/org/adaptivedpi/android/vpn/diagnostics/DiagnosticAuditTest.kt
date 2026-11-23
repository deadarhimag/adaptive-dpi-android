package org.adaptivedpi.android.vpn.diagnostics

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportFailure
import org.adaptivedpi.android.engine.TransportOperation
import org.adaptivedpi.android.engine.TransportSite
import org.adaptivedpi.android.engine.TransportSubsystem
import org.adaptivedpi.android.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import kotlin.coroutines.CoroutineContext

class DiagnosticAuditTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun criticalHistorySurvivesOrdinaryFloodAndTracksLossOfCriticalOverflow() {
        val dispatcher = PausedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val store = VpnEventStore(File(folder.root, "events"))
            val writer = VpnEventWriter(store, scope)
            val session = UUID.randomUUID()
            repeat(256) { assertTrue(writer.record(VpnJournalEvent.create(VpnEventType.ActivityRestored, session))) }
            assertFalse(writer.record(VpnJournalEvent.create(VpnEventType.ActivityRestored, session)))
            val critical = VpnJournalEvent.create(VpnEventType.StopError, session, DiagnosticCode.VpnCleanupFailed, VpnState.Failed)
            repeat(31) { assertTrue(writer.record(VpnJournalEvent.create(VpnEventType.StopError, session))) }
            assertTrue(writer.record(critical))
            assertFalse(writer.record(VpnJournalEvent.create(VpnEventType.StopError, session)))
            dispatcher.drain()
            assertTrue(writer.state.value.loaded)
            assertFalse(writer.state.value.storageError)
            assertEquals(1L, writer.state.value.droppedInformational)
            assertEquals(1L, writer.state.value.droppedCritical)
            assertTrue(store.read().any { it.id == critical.id })
        } finally {
            scope.cancel()
            dispatcher.drain()
        }
    }

    @Test
    fun lateFailureRefreshesPersistedSummaryAfterFirstFlush() = runBlocking {
        val dispatcher = PausedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val directory = File(folder.root, "transport")
        try {
            val store = TransportLogStore(directory)
            val journal = TransportJournal(scope) { store }
            val session = TransportSessionDiagnostics(UUID.randomUUID(), journal).also(journal::useSession)
            val failure = TransportFailure(TransportSite(TransportSubsystem.HEV, TransportOperation.STOP), TransportCategory.CLEANUP)
            session.record(VpnState.Stopping, failure, 1)
            session.finish()
            journal.flush()

            session.record(VpnState.Stopping, failure, 2)
            journal.flush()
            assertEquals(2L, journal.state.value.session?.total)
            val reopened = TransportLogStore(directory)
            assertEquals(2, reopened.storedRecords)
            assertEquals(2L, reopened.readSummary()?.total)
        } finally {
            scope.cancel()
            dispatcher.drain()
        }
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }
}
