package org.adaptivedpi.android.vpn

import android.system.ErrnoException
import android.system.OsConstants
import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode
import org.adaptivedpi.android.engine.TransportDirection
import org.adaptivedpi.android.engine.TransportFailure
import org.adaptivedpi.android.engine.TransportFailureReporter
import org.adaptivedpi.android.engine.TransportOperation
import org.adaptivedpi.android.engine.TransportSite
import org.adaptivedpi.android.engine.TransportSubsystem
import org.adaptivedpi.android.vpn.diagnostics.TransportSessionDiagnostics

internal fun TransportSessionDiagnostics.reporter() = TransportFailureReporter(
    errnoOf = { error -> generateSequence(error, Throwable::cause).take(8).filterIsInstance<ErrnoException>().firstOrNull()?.errno },
    sink = { failure -> record(VpnController.state.value, failure) },
)

internal class NativeTransportObserver(private val reporter: TransportFailureReporter) {
    fun report(phase: Int, result: Int, nativeErrno: Int) {
        // Числа фаз определены в transport-diagnostics.h. Значение 0 вместо errno означает отсутствие кода.
        val errno = nativeErrno.takeIf { it != 0 }
        val (code, operation) = when (phase) {
            1 -> TransportCode.HEV_CONFIG to TransportOperation.INIT
            2 -> TransportCode.HEV_LOGGER to TransportOperation.INIT
            3 -> TransportCode.HEV_TASKS to TransportOperation.INIT
            4 -> TransportCode.HEV_TUN_INIT to TransportOperation.INIT
            5 -> TransportCode.HEV_LOOP to TransportOperation.RUN
            6 -> TransportCode.TUN_READ to TransportOperation.READ
            7 -> TransportCode.TUN_WRITE to TransportOperation.WRITE
            8 -> TransportCode.TUN_BUFFER to TransportOperation.READ
            else -> return
        }
        val subsystem = if (phase >= 6) TransportSubsystem.TUN else TransportSubsystem.HEV
        val direction = when (operation) {
            TransportOperation.READ -> TransportDirection.OUTBOUND
            TransportOperation.WRITE -> TransportDirection.INBOUND
            else -> TransportDirection.CONTROL
        }
        val category = when {
            code == TransportCode.TUN_READ && result == -2 -> TransportCategory.CANCELLED
            code == TransportCode.TUN_WRITE && (errno == OsConstants.EAGAIN || errno == OsConstants.ENOBUFS || errno == OsConstants.ENOMEM) -> TransportCategory.BACKPRESSURE
            code == TransportCode.TUN_BUFFER -> TransportCategory.LIMIT
            subsystem == TransportSubsystem.TUN -> TransportCategory.TUN_IO
            operation == TransportOperation.INIT -> TransportCategory.HEV_INIT
            else -> TransportCategory.INTERNAL
        }
        reporter.record(TransportFailure(TransportSite(subsystem, operation, direction = direction), category, code, errno, result))
    }
}
