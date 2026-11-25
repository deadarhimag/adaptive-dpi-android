package org.adaptivedpi.android

import android.os.Build
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Эксперимент 1.6: таблица «операция - ok / errno» на этом устройстве. Исход каждой операции не проверяется: его и выясняем. */
class KernelCapabilitiesInstrumentedTest {
    @Test
    fun everyOperationReportsOkOrErrnoOnALoopbackPair() {
        val rows = KernelCapabilityProbe.run()
        val header = "Устройство ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}, ядро ${System.getProperty("os.version")}"
        Log.i(TAG, header)
        rows.forEach { Log.i(TAG, it.text()) }

        assertEquals(KernelCapabilityProbe.OPERATIONS, rows.map { it.operation })
        assertTrue(rows.toString(), rows.all { it.result.isNotBlank() })
        // Эксперимент не ломает обычную передачу на новых сокетах.
        assertEquals("ok", KernelCapabilityProbe.loopbackStillWorks())
    }

    private companion object {
        const val TAG = "KernelCaps"
    }
}
