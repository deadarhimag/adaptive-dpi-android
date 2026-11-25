package org.adaptivedpi.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.io.File

/** Новый ViewModel и восстановленный AndroidX Registry, без подмены обработки файлов. */
internal class DiagnosticDocumentRecoveryChecks(private val context: Context) {
    fun acceptsQueuedResultBeforeRecovery() {
        for (queued in listOf(true, false)) withSnapshot { snapshot ->
            val (state, oldCode, request) = previousPicker(snapshot.name)
            withRestoredOwner(state, request) { owner, registry, picker ->
                onMain {
                    val result = Intent().setData(snapshot.goodUri)
                    if (queued) assertTrue(registry.dispatchResult(oldCode, Activity.RESULT_OK, result))
                    picker.register(checkNotNull(request.key))
                    owner.recoverIfNeeded(context) { it }
                    if (!queued) assertTrue(registry.dispatchResult(oldCode, Activity.RESULT_OK, result))
                }
                await {
                    var idle = false
                    onMain { idle = !owner.busy }
                    idle && snapshot.good.readText() == snapshot.report && !DiagnosticExports.hasPending(context, snapshot.name)
                }
                onMain {
                    assertEquals(null, owner.retry.value)
                    assertFalse(owner.busy)
                }
            }
        }
    }

    fun rejectsOldResultAfterRecoveryAndWhileRetryWaits() = withSnapshot { snapshot ->
        val (state, oldCode, request) = previousPicker(snapshot.name)
        assertEquals(DiagnosticExports.WriteClaim.Claimed, DiagnosticExports.claimWrite(context, snapshot.name))
        withRestoredOwner(state, request) { owner, registry, picker ->
            onMain {
                picker.register(checkNotNull(request.key))
                owner.recoverIfNeeded(context) { it }
            }
            awaitRetry(owner, snapshot.name)
            onMain {
                assertTrue(registry.dispatchResult(oldCode, Activity.RESULT_CANCELED, Intent()))
                assertEquals(snapshot.name, owner.retry.value)
            }
            assertEquals(DiagnosticExports.WriteClaim.Interrupted, DiagnosticExports.claimWrite(context, snapshot.name))
            assertEquals(snapshot.report, DiagnosticExports.readPending(context, snapshot.name))

            DiagnosticExports.armRetry(context, snapshot.name)
            onMain {
                owner.retry.value = null // Пользователь явно подтвердил повтор.
                picker.register(owner.startPicker(snapshot.name)).launch("retry.txt")
                val newCode = registry.requestCode
                assertNotEquals(oldCode, newCode)
                assertFalse(registry.dispatchResult(oldCode, Activity.RESULT_OK, Intent().setData(snapshot.staleUri)))
                owner.finishPicker(context, snapshot.staleUri, request.key, { it }, "transport")
                assertEquals(snapshot.name, owner.picker?.id)
                assertTrue(registry.dispatchResult(newCode, Activity.RESULT_OK, Intent().setData(snapshot.goodUri)))
            }
            await {
                snapshot.good.readText() == snapshot.report && !DiagnosticExports.hasPending(context, snapshot.name)
            }
            assertEquals("", snapshot.stale.readText())
        }
    }

    fun queuedCancellationPreservesAnAlreadyClaimedSnapshot() = withSnapshot { snapshot ->
        val (state, oldCode, request) = previousPicker(snapshot.name)
        assertEquals(DiagnosticExports.WriteClaim.Claimed, DiagnosticExports.claimWrite(context, snapshot.name))
        withRestoredOwner(state, request) { owner, registry, picker ->
            onMain {
                assertTrue(registry.dispatchResult(oldCode, Activity.RESULT_CANCELED, Intent()))
                picker.register(checkNotNull(request.key))
                owner.recoverIfNeeded(context) { it }
            }
            awaitRetry(owner, snapshot.name)
            assertEquals(DiagnosticExports.WriteClaim.Interrupted, DiagnosticExports.claimWrite(context, snapshot.name))
            assertEquals(snapshot.report, DiagnosticExports.readPending(context, snapshot.name))
            assertEquals("", snapshot.good.readText())
        }
    }

    private data class Snapshot(val name: String, val report: String, val good: File, val stale: File,
                                val goodUri: Uri, val staleUri: Uri)

    private fun withSnapshot(action: (Snapshot) -> Unit) {
        val report = "Снимок после остановки процесса ✓"
        val name = DiagnosticExports.storePending(context, report)
        val directory = File(context.cacheDir, "diagnostic-reports").apply { mkdirs() }
        val good = File.createTempFile("restored-result-", ".txt", directory)
        val stale = File.createTempFile("stale-result-", ".txt", directory)
        fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
        try {
            action(Snapshot(name, report, good, stale, uri(good), uri(stale)))
        } finally {
            DiagnosticExports.deletePending(context, name)
            good.delete()
            stale.delete()
        }
    }

    private fun previousPicker(name: String): Triple<Bundle, Int, DiagnosticSnapshotPreparation.Picker> {
        var saved: Triple<Bundle, Int, DiagnosticSnapshotPreparation.Picker>? = null
        val store = ViewModelStore()
        onMain {
            val owner = ownerFrom(store)
            owner.restore(null, null, null)
            val registry = DocumentRegistry()
            val launcher = DiagnosticDocumentPicker(registry) { _, _ -> error("Неожиданный ранний результат") }
            val key = owner.startPicker(name)
            launcher.register(key).launch("snapshot.txt")
            val state = Bundle().also(registry::onSaveInstanceState)
            saved = Triple(state, registry.requestCode, checkNotNull(owner.picker))
            launcher.close()
            store.clear()
        }
        return checkNotNull(saved)
    }

    private fun withRestoredOwner(
        state: Bundle,
        request: DiagnosticSnapshotPreparation.Picker,
        action: (DiagnosticSnapshotPreparation, DocumentRegistry, DiagnosticDocumentPicker) -> Unit,
    ) {
        val store = ViewModelStore()
        val owner = ownerFrom(store)
        val registry = DocumentRegistry()
        val picker = DiagnosticDocumentPicker(registry) { key, uri ->
            owner.finishPicker(context, uri, key, { it }, "transport")
        }
        try {
            onMain {
                registry.onRestoreInstanceState(state)
                assertTrue(owner.restore(request, null, null))
            }
            action(owner, registry, picker)
        } finally {
            onMain {
                picker.close()
                store.clear()
            }
        }
    }

    private fun ownerFrom(store: ViewModelStore): DiagnosticSnapshotPreparation {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
        return ViewModelProvider(owner)[DiagnosticSnapshotPreparation::class.java]
    }

    private fun awaitRetry(owner: DiagnosticSnapshotPreparation, name: String) = await {
        var retry = false
        onMain { retry = owner.writing == null && owner.retry.value == name && owner.picker == null }
        retry
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue(condition())
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private class DocumentRegistry : ActivityResultRegistry() {
        var requestCode = 0
            private set

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                                     options: ActivityOptionsCompat?) {
            this.requestCode = requestCode
        }
    }
}
