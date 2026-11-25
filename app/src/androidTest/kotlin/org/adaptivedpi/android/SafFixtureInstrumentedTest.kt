package org.adaptivedpi.android

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

internal class SafFixture(context: Context) {
    private val resolver = context.contentResolver
    val uri: Uri = Uri.parse("content://org.adaptivedpi.android.test.saf/result")

    fun reset(mode: String, expectedBytes: Int = -1, timeoutMillis: Int = 20_000): Bundle =
        checkNotNull(resolver.call(uri, "reset", mode, Bundle().apply {
            putInt("expectedBytes", expectedBytes)
            putInt("timeoutMillis", timeoutMillis)
        }))

    fun release() {
        checkNotNull(resolver.call(uri, "release", null, null))
    }

    fun state(): Bundle = checkNotNull(resolver.call(uri, "state", null, null))

    fun healthyState(): Bundle = state().also { assertHealthy(it) }

    fun open(): ParcelFileDescriptor = checkNotNull(resolver.openFileDescriptor(uri, "wt"))

    fun awaitState(condition: (Bundle) -> Boolean): Bundle {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            val observed = state()
            if (condition(observed)) return observed
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Не получено ожидаемое состояние SAF: ${state()}")
    }

    companion object {
        fun assertHealthy(state: Bundle) {
            assertEquals("Ошибка фикстуры SAF: $state", null, state.getString("error"))
        }
    }
}

class SafFixtureInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = SafFixture(context)

    @Test
    fun startsInTestPackageAndReceivesExactBytesAfterRelease() {
        val payload = "Контроль SAF ✓".toByteArray()
        SafFixture.assertHealthy(checkNotNull(fixture.reset("success", payload.size).getBundle("previous")))
        val initial = fixture.healthyState()
        assertEquals("text/plain", context.contentResolver.getType(fixture.uri))
        assertNotEquals(Process.myPid(), initial.getInt("pid"))
        assertNotEquals(Process.myUid(), initial.getInt("uid"))
        assertEquals(context.packageManager.getApplicationInfo("${context.packageName}.test", 0).uid, initial.getInt("uid"))
        assertEquals(0, initial.getInt("opened"))
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(fixture.open()).use { output ->
                output.write(payload)
                val blocked = fixture.awaitState { it.getBoolean("writing") }
                assertFalse(blocked.getBoolean("finished"))
                assertEquals(null, blocked.getString("digest"))
                fixture.release()
            }
            val result = fixture.awaitState { it.getBoolean("finished") }
            SafFixture.assertHealthy(result)
            assertEquals("success", result.getString("outcome"))
            assertEquals(1, result.getInt("opened"))
            assertEquals(payload.size, result.getInt("bytes"))
            assertEquals(digest(payload), result.getString("digest"))
        } finally {
            fixture.release()
        }
    }

    @Test
    fun requestedOpenAndWriteFailuresAreObservableWithoutFixtureError() {
        fixture.reset("open-error")
        assertThrows(FileNotFoundException::class.java) { fixture.open().close() }
        assertControlledFailure("open-error")
        fixture.reset("write-error")
        assertThrows(IOException::class.java) {
            ParcelFileDescriptor.AutoCloseOutputStream(fixture.open()).use { it.write(42) }
        }
        assertControlledFailure("write-error")
    }

    @Test
    fun prematureAndFaultedPipesFailWithoutPublishingADigest() {
        fixture.reset("success", expectedBytes = 2)
        fixture.release()
        fixture.open().close()
        assertFixtureFailure("закрыт до получения отчёта")
        val fresh = fixture.reset("success", expectedBytes = 2)
        assertEquals("error", fresh.getBundle("previous")?.getString("outcome"))
        assertEquals(0, fresh.getInt("opened"))
        fixture.release()
        ParcelFileDescriptor.AutoCloseOutputStream(fixture.open()).use { it.write(42) }
        assertFixtureFailure("закрыт до получения отчёта: 1 байт")
        fixture.reset("success", expectedBytes = 2)
        fixture.release()
        fixture.open().closeWithError("Досрочная ошибка источника")
        assertFixtureFailure("Досрочная ошибка источника")
        fixture.reset("success")
        fixture.open().use {
            assertThrows(FileNotFoundException::class.java) { fixture.open().close() }
        }
        assertFixtureFailure("Повторное открытие без reset")
        fixture.reset("success")
    }

    @Test
    fun resetRetiresBothReadingAndBlockedWorkersAndTimeoutIsAnError() {
        for (writeFirstByte in listOf(false, true)) {
            fixture.reset("success")
            ParcelFileDescriptor.AutoCloseOutputStream(fixture.open()).use { output ->
                if (writeFirstByte) {
                    output.write(42)
                    fixture.awaitState { it.getBoolean("writing") }
                }
                val next = fixture.reset("success")
                val previous = checkNotNull(next.getBundle("previous"))
                assertEquals("cancelled-by-reset", previous.getString("outcome"))
                assertTrue(previous.getBoolean("finished"))
                assertEquals(0, next.getInt("opened"))
                SafFixture.assertHealthy(previous)
            }
        }
        fixture.reset("success", timeoutMillis = 500)
        ParcelFileDescriptor.AutoCloseOutputStream(fixture.open()).use {
            assertFixtureFailure("Тайм-аут чтения")
        }
        fixture.reset("success", timeoutMillis = 1_500)
        ParcelFileDescriptor.AutoCloseOutputStream(fixture.open()).use {
            it.write(42)
            assertFixtureFailure("Тайм-аут разблокировки")
        }
        fixture.reset("success")
    }

    private fun assertControlledFailure(expected: String) {
        val result = fixture.healthyState()
        assertEquals(expected, result.getString("outcome"))
        assertEquals(1, result.getInt("opened"))
        assertTrue(result.getBoolean("finished"))
        assertEquals(null, result.getString("digest"))
    }

    private fun assertFixtureFailure(reason: String) {
        val result = fixture.awaitState { it.getBoolean("finished") }
        assertEquals("error", result.getString("outcome"))
        assertEquals(null, result.getString("digest"))
        assertTrue(result.toString(), result.getString("error").orEmpty().contains(reason))
    }
}

internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
