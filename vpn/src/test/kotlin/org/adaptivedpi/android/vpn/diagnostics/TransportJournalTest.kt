package org.adaptivedpi.android.vpn.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportFailure
import org.adaptivedpi.android.engine.TransportFailureReporter
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
import java.io.StringWriter
import java.io.Writer
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TransportJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val site = TransportSite(TransportSubsystem.UDP, TransportOperation.RECEIVE)
    private val timeout = TransportFailure(site, TransportCategory.TIMEOUT)

    @Test
    fun tenThousandIdenticalFailuresRemainOneRecordAndFullCounters() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val directory = temporary.newFolder()
        try {
            val journal = TransportJournal(scope, batchMillis = 60_000) { TransportLogStore(directory) }
            val session = TransportSessionDiagnostics(UUID.randomUUID(), journal).also(journal::useSession)
            repeat(10_000) { session.record(VpnState.EngineRunning, timeout, it.toLong()) }
            session.record(VpnState.Stopping, TransportFailure(site, TransportCategory.CANCELLED), 10_001)
            session.record(VpnState.Stopping, TransportFailure(site, TransportCategory.CLEANUP), 10_002)
            session.record(VpnState.Stopping, TransportFailure(site, TransportCategory.UNKNOWN), 10_003)
            session.finish()
            journal.flush()
            val output = journal.state.value
            val summary = checkNotNull(output.session)
            assertEquals(10_003L, summary.total)
            assertEquals(10_001L, summary.expected)
            assertEquals(1L, summary.unexpected)
            assertEquals(1L, summary.unknown)
            assertEquals(9_999L, summary.merged)
            assertEquals(0L, summary.dropped)
            assertEquals(4, output.storedRecords)
            val row = output.entries.last()
            assertEquals(10_000, row.repeats.toInt())
            assertEquals(0, row.firstTime.toEpochMilli().toInt())
            assertEquals(9_999, row.lastTime.toEpochMilli().toInt())
            assertEquals(summary, TransportLogStore(directory).readSummary())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fullPendingWindowDropsDistinctRecordsButStillMergesAndCountsEveryOperation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val directory = temporary.newFolder()
        try {
            val journal = TransportJournal(scope, batchMillis = 60_000) { TransportLogStore(directory) }
            val session = TransportSessionDiagnostics(UUID.randomUUID(), journal).also(journal::useSession)
            repeat(100) { index -> session.record(VpnState.EngineRunning, timeout.copy(result = index), index.toLong()) }
            repeat(3) { session.record(VpnState.EngineRunning, timeout.copy(result = 0), 200) }
            journal.flush()
            val summary = checkNotNull(journal.state.value.session)
            assertEquals(103L, summary.total)
            assertEquals(36L, summary.dropped)
            assertEquals(mapOf(TransportDropSource.LIMIT to 36L), summary.dropsBySource)
            assertEquals(3L, summary.merged)
            assertEquals(64, journal.state.value.storedRecords)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun slowExportDoesNotBlockProducersOrSessionStopAndContainsConsistentRecordsWithoutSecrets() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val directory = temporary.newFolder()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = StringWriter()
        val writer = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                output.write(buffer, offset, length)
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val producer = Executors.newFixedThreadPool(4)
        try {
            val journal = TransportJournal(scope, batchMillis = 60_000) { TransportLogStore(directory) }
            val session = TransportSessionDiagnostics(UUID.randomUUID(), journal).also(journal::useSession)
            val privateData = "https://secret.test:4567/secret?token=secret Cookie=secret payload=secret"
            TransportFailureReporter(sink = { session.record(VpnState.EngineRunning, it, 1) })
                .recordError(site, SocketException(privateData))
            val export = async(Dispatchers.IO) { journal.export(writer) }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                val jobs = List(4) {
                    producer.submit { repeat(2_500) { session.record(VpnState.Stopping, timeout, 2) } }
                }
                jobs.forEach { it.get(3, TimeUnit.SECONDS) }
                session.finish()
            } finally {
                release.countDown()
            }
            export.await()
            assertTrue(output.toString().contains("Всего отказов операций 1."))
            assertFalse(output.toString().contains("secret"))
            assertFalse(directory.listFiles().orEmpty().any { it.readText().contains("secret") })
            journal.flush()
            assertEquals(10_001L, TransportLogStore(directory).readSummary()?.total)
            assertEquals(2, journal.state.value.storedRecords)
        } finally {
            producer.shutdownNow()
            scope.cancel()
        }
    }
}
