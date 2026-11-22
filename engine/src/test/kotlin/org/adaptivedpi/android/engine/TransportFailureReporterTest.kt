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
        reporter.recordError(site, SocketTimeoutException(privateText), cancelled = true)
        reporter.recordError(site, UnknownHostException(privateText))
        reporter.recordError(site, SocketException(privateText), cancelled = true)
        assertEquals(listOf(TransportCategory.RESET, TransportCategory.UNKNOWN, TransportCategory.TIMEOUT,
            TransportCategory.DNS, TransportCategory.CANCELLED), recorded.map(TransportFailure::category))
        assertEquals("unknown", recorded[1].codeLabel)
        assertEquals(104, recorded[0].errno)
        assertFalse(recorded.toString().contains("private.test"))
        assertFalse(recorded.toString().contains("secret"))
    }
}
