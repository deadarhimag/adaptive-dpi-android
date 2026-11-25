package org.adaptivedpi.android.vpn.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.util.UUID

/** Запись Android о завершении процесса: только поля, из которых строится ключ события. */
internal data class ProcessExitRecord(val pid: Int, val timestampMillis: Long, val reason: Int, val processName: String)

/** Чтение причин прошлых завершений процесса (`ApplicationExitInfo`, API 30+). */
internal object ExitReasonReader {
    private const val MAX_RECORDS = 16

    fun read(context: Context): List<ProcessExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS).map {
            ProcessExitRecord(it.pid, it.timestamp, it.reason, it.processName)
        }
    }

    /** Только причины, которые обработчик исключений не ловит; остальные в журнал не попадают. */
    fun codeOf(reason: Int): DiagnosticCode? = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> DiagnosticCode.ProcessExitNative
        ApplicationExitInfo.REASON_ANR -> DiagnosticCode.ProcessExitAnr
        ApplicationExitInfo.REASON_LOW_MEMORY -> DiagnosticCode.ProcessExitLowMemory
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> DiagnosticCode.ProcessExitResources
        ApplicationExitInfo.REASON_SIGNALED -> DiagnosticCode.ProcessExitSignaled
        else -> null
    }

    /** Одна и та же запись Android всегда даёт один и тот же идентификатор. */
    fun idOf(record: ProcessExitRecord): UUID = UUID.nameUUIDFromBytes(
        "exit\t${record.pid}\t${record.timestampMillis}\t${record.reason}\t${record.processName}".toByteArray(Charsets.UTF_8),
    )
}
