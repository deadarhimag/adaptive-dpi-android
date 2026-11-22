package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode
import org.adaptivedpi.android.engine.TransportFailure
import org.adaptivedpi.android.engine.TransportOperation
import org.adaptivedpi.android.engine.TransportSite
import org.adaptivedpi.android.engine.TransportSubsystem
import org.adaptivedpi.android.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

class TransportLogStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = UUID.randomUUID()

    @Test
    fun rotationRetainsNewestRecordsWithinFiveMebibytesAndRamBudget() {
        val directory = temporary.newFolder()
        val store = TransportLogStore(directory)
        repeat(52_000) { index ->
            store.append(entry(index + 1L))
            if (index % 100 == 0) assertTrue(store.bytes <= TransportLogStore.MAX_DISK_BYTES)
        }
        store.saveSummary(TransportSessionSummary(id, mapOf(TransportCategory.TIMEOUT to 52_000L), 0, 0, Instant.ofEpochMilli(52_000), true))
        assertTrue(store.bytes <= 5L * 1024 * 1024)
        assertEquals(5, directory.listFiles().orEmpty().count { it.name.startsWith("transport-log-") })
        assertTrue(directory.listFiles().orEmpty().all { it.length() <= 1024L * 1024 })
        assertEquals(500, store.entries.size + 2 * TransportJournal.PENDING_LIMIT)

        val recovered = TransportLogStore(directory)
        assertEquals(store.storedRecords, recovered.storedRecords)
        assertEquals(store.entries, recovered.entries)
        assertEquals(52_000L, recovered.readSummary()?.total)
        var previous = 0L
        var count = 0
        recovered.forEachEntry {
            val millis = it.firstTime.toEpochMilli()
            assertTrue(millis > previous)
            if (previous == 0L) assertTrue(millis > 1)
            previous = millis
            count++
        }
        assertEquals(52_000L, previous)
        assertEquals(recovered.storedRecords, count)
    }

    @Test
    fun malformedOversizedAndIncompleteLinesAreIgnoredAndNewEventsSurvive() {
        val directory = temporary.newFolder()
        val first = TransportLogStore(directory)
        first.append(entry(1))
        val segment = File(directory, "transport-log-0")
        segment.appendText("invalid_checksum\tbroken\n" + "X".repeat(4097) + "\n")
        first.append(entry(2))
        segment.appendText("torn record")
        File(directory, "transport-summary").writeText("torn summary")

        val reopened = TransportLogStore(directory)
        assertTrue(reopened.storageError)
        assertEquals(null, reopened.readSummary())
        reopened.append(entry(3))
        val final = TransportLogStore(directory)
        assertEquals(listOf(3L, 2L, 1L), final.entries.map { it.firstTime.toEpochMilli() })
        assertEquals(3, final.storedRecords)
    }

    private fun entry(time: Long) = TransportLogEntry(
        id, Instant.ofEpochMilli(time), Instant.ofEpochMilli(time), VpnState.EngineRunning,
        TransportFailure(TransportSite(TransportSubsystem.TCP, TransportOperation.READ), TransportCategory.TIMEOUT, TransportCode.TIMEOUT), 1,
    )
}
