package org.adaptivedpi.android.vpn

import android.system.ErrnoException
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
    sink = { failure -> record(state, failure) },
)

internal class NativeTransportObserver(private val diagnostics: TransportSessionDiagnostics) {
    fun accept(records: LongArray) {
        // Заголовок содержит время и агрегаты пропущенных событий. Следом идут записи по восемь полей.
        val droppedAt = records[0]
        categories.forEachIndexed { index, category ->
            val count = records[index + 1]
            if (count > 0) diagnostics.recordDropped(category, droppedAt, count)
        }
        for (offset in categories.size + 1 until records.size step 8) {
            val phase = records[offset].toInt()
            val result = records[offset + 1].toInt()
            val errno = records[offset + 2].toInt().takeUnless { it == 0 }
            val category = categories[records[offset + 3].toInt()]
            val state = VpnState.entries[records[offset + 4].toInt()]
            val repeats = records[offset + 5]
            val first = records[offset + 6]
            val last = records[offset + 7]
            diagnostics.recordBatch(state, failure(phase, result, errno, category), first, last, repeats)
        }
    }

    private fun failure(phase: Int, result: Int, errno: Int?, category: TransportCategory): TransportFailure {
        val (code, operation) = when (phase) {
            1 -> TransportCode.HEV_CONFIG to TransportOperation.INIT
            2 -> TransportCode.HEV_LOGGER to TransportOperation.INIT
            3 -> TransportCode.HEV_TASKS to TransportOperation.INIT
            4 -> TransportCode.HEV_TUN_INIT to TransportOperation.INIT
            5 -> TransportCode.HEV_LOOP to TransportOperation.RUN
            6 -> TransportCode.TUN_READ to TransportOperation.READ
            7 -> TransportCode.TUN_WRITE to TransportOperation.WRITE
            8 -> TransportCode.TUN_BUFFER to TransportOperation.READ
            else -> TransportCode.UNKNOWN to TransportOperation.RUN
        }
        val subsystem = if (phase >= 6) TransportSubsystem.TUN else TransportSubsystem.HEV
        val direction = when (operation) {
            TransportOperation.READ -> TransportDirection.OUTBOUND
            TransportOperation.WRITE -> TransportDirection.INBOUND
            else -> TransportDirection.CONTROL
        }
        return TransportFailure(TransportSite(subsystem, operation, direction = direction), category, code, errno, result)
    }

    private companion object {
        // Порядок совпадает с FAULT_CATEGORIES в bridge.c.
        val categories = listOf(TransportCategory.HEV_INIT, TransportCategory.INTERNAL, TransportCategory.TUN_IO,
            TransportCategory.CANCELLED, TransportCategory.BACKPRESSURE, TransportCategory.LIMIT)
    }
}
