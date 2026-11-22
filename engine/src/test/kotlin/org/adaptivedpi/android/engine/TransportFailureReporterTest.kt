package org.adaptivedpi.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TransportFailureReporterTest {
    @Test
    fun classifiesTypedCausesAndLinuxErrnoWithoutReadingMessages() {
        val site = TransportSite(TransportSubsystem.TCP, TransportOperation.CONNECT, TransportProtocol.TCP, TransportDirection.OUTBOUND)
        val recorded = mutableListOf<TransportFailure>()
        val privateText = "connection reset at https://private.test/?token=secret 192.0.2.1:1234"
        val reset = IOException(privateText)
        val reporter = TransportFailureReporter({ if (it === reset) 104 else null }, recorded::add)
        reporter.recordError(site, reset)
        reporter.recordError(site, ConnectException(privateText))
        reporter.recordError(site, SocketTimeoutException(privateText), cancellation = TransportCancellation.SESSION)
        reporter.recordError(site, UnknownHostException(privateText))
        reporter.recordError(site, SocketException(privateText), cancellation = TransportCancellation.SESSION)
        assertEquals(listOf(TransportCategory.RESET, TransportCategory.UNKNOWN, TransportCategory.TIMEOUT,
            TransportCategory.DNS, TransportCategory.CANCELLED), recorded.map(TransportFailure::category))
        assertEquals(null, recorded[1].errno)
        assertEquals(TransportCode.SOCKET_EXCEPTION, recorded[1].code)
        assertEquals(104, recorded[0].errno)
        assertFalse(recorded.toString().contains("private.test"))
        assertFalse(recorded.toString().contains("secret"))
    }

    @Test
    fun badDescriptorAndNativeCancellationRequireEvidenceOfClose() {
        val site = TransportSite(TransportSubsystem.DNS, TransportOperation.RECEIVE, TransportProtocol.UDP, TransportDirection.INBOUND)
        for (errno in listOf(9, 125)) {
            val records = mutableListOf<TransportFailure>()
            val reporter = TransportFailureReporter({ errno }, records::add)
            val error = SocketException("Message is intentionally ignored")
            reporter.recordError(site, error)
            reporter.recordError(site, error, TransportCancellation.FLOW)
            reporter.recordError(site, error, TransportCancellation.SESSION)
            assertEquals(TransportCategory.INTERNAL, records[0].category)
            assertEquals(TransportCategory.CANCELLED, records[1].category)
            assertEquals(TransportCode.FLOW_CANCELLED, records[1].code)
            assertEquals(TransportCode.SESSION_CANCELLED, records[2].code)
        }
    }

    @Test
    fun notConnectedDuringHalfCloseIsExpectedOnlyAfterPeerEofOrExplicitClose() {
        val records = mutableListOf<TransportFailure>()
        val reporter = TransportFailureReporter({ 107 }, records::add)
        val site = TransportSite(TransportSubsystem.HEV, TransportOperation.SHUTDOWN_OUTPUT, TransportProtocol.TCP)
        val error = SocketException()
        reporter.recordError(site, error, fallback = TransportCategory.CLEANUP)
        reporter.recordError(site, error, fallback = TransportCategory.CLEANUP, peerEof = true)
        reporter.recordError(site, error, TransportCancellation.SESSION, TransportCategory.CLEANUP)
        reporter.recordError(site.copy(operation = TransportOperation.CLOSE), error, TransportCancellation.SESSION, TransportCategory.CLEANUP)
        assertEquals(listOf(TransportCategory.CLEANUP, TransportCategory.CLOSED, TransportCategory.CANCELLED,
            TransportCategory.CLEANUP), records.map(TransportFailure::category))
        assertEquals(TransportCode.PEER_EOF, records[1].code)
        assertEquals("errno=ENOTCONN (107), PEER_EOF", records[1].codeLabel)
    }
}
