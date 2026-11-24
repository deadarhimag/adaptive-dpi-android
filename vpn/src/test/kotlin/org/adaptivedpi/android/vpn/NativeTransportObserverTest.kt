package org.adaptivedpi.android.vpn

import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode
import org.adaptivedpi.android.engine.TransportFailure
import org.adaptivedpi.android.engine.TransportOperation
import org.adaptivedpi.android.engine.TransportProtocol
import org.adaptivedpi.android.engine.TransportSite
import org.adaptivedpi.android.engine.TransportSubsystem
import org.adaptivedpi.android.vpn.diagnostics.TransportSessionDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class NativeTransportObserverTest {
    @Test
    fun nativeAggregatesAndDiscardedRecordsKeepExactTotals() {
        val session = TransportSessionDiagnostics(UUID.randomUUID(), null)
        NativeTransportObserver(session).accept(longArrayOf(
            42, 0, 0, 3, 0, 2, 0,
            6, -2, 0, 3, VpnState.Stopping.ordinal.toLong(), 10_000, 10, 41,
            7, -1, 11, 4, VpnState.EngineRunning.ordinal.toLong(), 50, 10, 41,
        ))

        val summary = session.summary()
        assertEquals(10_055L, session.total)
        assertEquals(10_055L, summary.total)
        assertEquals(10_000L, summary.byCategory[TransportCategory.CANCELLED])
        assertEquals(52L, summary.byCategory[TransportCategory.BACKPRESSURE])
        assertEquals(3L, summary.byCategory[TransportCategory.TUN_IO])
        assertEquals(5L, summary.dropped)
        assertEquals(Instant.ofEpochMilli(42), summary.lastFailure)
        assertEquals(3L, session.problems)
    }

    @Test
    fun expectedOutcomesDoNotCountAsProblemsButALaterSetupFailureDoes() {
        val session = TransportSessionDiagnostics(UUID.randomUUID(), null)
        val site = TransportSite(TransportSubsystem.TCP, TransportOperation.READ, TransportProtocol.TCP)
        session.record(VpnState.EngineRunning, TransportFailure(site, TransportCategory.TIMEOUT, TransportCode.TIMEOUT))
        session.record(VpnState.EngineRunning, TransportFailure(site, TransportCategory.CANCELLED, TransportCode.FLOW_CANCELLED))
        assertEquals(2L, session.total)
        assertEquals(0L, session.problems)

        session.record(VpnState.EngineRunning, TransportFailure(site, TransportCategory.SOCKET_SETUP))
        session.record(VpnState.EngineRunning, TransportFailure(site, TransportCategory.UNKNOWN))
        assertEquals(4L, session.total)
        assertEquals(2L, session.problems)
    }
}
