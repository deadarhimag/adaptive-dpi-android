package org.adaptivedpi.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Новый ViewModel и восстановленный AndroidX Registry, без подмены обработки файлов. */
internal class DiagnosticDocumentRecoveryChecks(private val context: Context) {
    fun acceptsQueuedResultBeforeRecovery() = withSnapshot { snapshot ->
        withRestoredOwner(snapshot.name) { restored ->
            onMain {
                restored.dispatchOld("A: URI до регистрации", snapshot.goodUri)
                assertNull("A: callback ещё не зарегистрирован", restored.lastWrite)
                restored.register()
                assertNull("A: принятый результат завершил ожидание", restored.recover())
            }
            awaitJob("A: запись ранее поставленного в очередь URI", restored.lastWrite)
            assertSaved("A", restored.owner, snapshot)
            onMain {
                assertNull("A: повторный callback не должен открывать второй URI",
                    restored.deliver(restored.request.key, snapshot.staleUri))
            }
            assertEquals("A: второй документ пуст", "", snapshot.stale.readText())
        }
    }

    fun acceptsResultDuringRecovery() = withSnapshot { snapshot ->
        val gate = RecoveryGate()
        withRestoredOwner(snapshot.name, gate) { restored ->
            var recovery: Job? = null
            onMain {
                restored.register()
                recovery = restored.recover()
            }
            assertTrue("Б: до callback диск подтвердил наличие снимка", awaitEvent("Б: результат чтения", gate.checked))
            onMain {
                assertTrue("Б: восстановление ещё удерживается", checkNotNull(recovery).isActive)
                assertEquals("Б: ожидается этот ключ", restored.request, restored.owner.picker)
                restored.dispatchOld("Б: URI во время восстановления", snapshot.goodUri)
            }
            awaitJob("Б: запись до освобождения восстановления", restored.lastWrite)
            assertSaved("Б: запись", restored.owner, snapshot)
            gate.release.complete(Unit)
            awaitJob("Б: позднее завершение чтения со старым результатом", recovery)
            assertSaved("Б: позднее чтение не вернуло retry", restored.owner, snapshot)
        }
    }

    fun offersRetryWithoutResult() = withSnapshot { snapshot ->
        withRestoredOwner(snapshot.name) { restored ->
            completeRecovery("В", restored)
            assertRetry("В", restored.owner, snapshot.name)
            assertEquals("В: готовый снимок сохранён", snapshot.report, DiagnosticExports.readPending(context, snapshot.name))
            assertFalse("В: запись ещё не начиналась", DiagnosticExports.hasInterruptedWrite(context, snapshot.name))
            assertEquals("В: документ остался пуст", "", snapshot.good.readText())
        }
    }

    fun rejectsOldResultWhileRetryWaits() = withSnapshot { snapshot ->
        assertEquals(DiagnosticExports.WriteClaim.Claimed, DiagnosticExports.claimWrite(context, snapshot.name))
        withRestoredOwner(snapshot.name) { restored ->
            completeRecovery("Г", restored)
            assertRetry("Г: до старого callback", restored.owner, snapshot.name)
            onMain {
                restored.dispatchOld("Г: отмена после перехода в retry", null)
                assertNull("Г: отмена не должна запустить очистку", restored.lastWrite)
                assertNull("Г: URI старого запроса не должен открыть документ",
                    restored.deliver(restored.request.key, snapshot.staleUri))
            }
            assertRetry("Г: после старого callback", restored.owner, snapshot.name)
            assertInterrupted("Г", snapshot)
            assertEquals("Г: старый URI пуст", "", snapshot.stale.readText())
        }
    }

    fun rejectsOldResultAfterNewLaunch() = withSnapshot { snapshot ->
        assertEquals(DiagnosticExports.WriteClaim.Claimed, DiagnosticExports.claimWrite(context, snapshot.name))
        withRestoredOwner(snapshot.name) { restored ->
            completeRecovery("Д", restored)
            assertRetry("Д: до подтверждения повтора", restored.owner, snapshot.name)
            DiagnosticExports.armRetry(context, snapshot.name)
            onMain {
                restored.owner.retry.value = null // Пользователь явно подтвердил повтор.
                val newKey = restored.launch(snapshot.name)
                val newCode = restored.registry.requestCode
                assertNotEquals("Д: у того же снимка новый ключ", restored.request.key, newKey)
                assertNotEquals("Д: у нового диалога другой код", restored.oldCode, newCode)
                // Registry может сохранить старый результат без callback, пока у него есть старый код.
                restored.registry.dispatchResult(restored.oldCode, Activity.RESULT_OK, Intent().setData(snapshot.staleUri))
                assertNull("Д: Registry не направил старый URI в запись", restored.lastWrite)
                assertNull("Д: приложение отклоняет старый ключ",
                    restored.deliver(restored.request.key, snapshot.staleUri))
                assertNull("Д: старая отмена не должна отменить новый диалог", restored.deliver(restored.request.key, null))
                assertEquals("Д: новый диалог всё ещё активен", newKey, restored.owner.picker?.key)
                assertTrue("Д: Registry принял результат нового диалога",
                    restored.registry.dispatchResult(newCode, Activity.RESULT_OK, Intent().setData(snapshot.goodUri)))
            }
            awaitJob("Д: запись по новому URI", restored.lastWrite)
            assertSaved("Д", restored.owner, snapshot)
            assertEquals("Д: старый URI остался пустым", "", snapshot.stale.readText())
        }
    }

    fun queuedCancellationPreservesAnAlreadyClaimedSnapshot() = withSnapshot { snapshot ->
        assertEquals(DiagnosticExports.WriteClaim.Claimed, DiagnosticExports.claimWrite(context, snapshot.name))
        withRestoredOwner(snapshot.name) { restored ->
            onMain {
                restored.dispatchOld("Е: отмена в очереди Registry", null)
                restored.register()
                assertNull("Е: принятая отмена передала владение обработчику", restored.recover())
            }
            awaitJob("Е: проверка .writing в обработчике отмены", restored.lastWrite)
            assertRetry("Е", restored.owner, snapshot.name)
            assertInterrupted("Е", snapshot)
            assertEquals("Е: документ остался пуст", "", snapshot.good.readText())
        }
    }

    fun missingSnapshotUnblocksAnotherExport() = withSnapshot { missing ->
        DiagnosticExports.deletePending(context, missing.name)
        withRestoredOwner(missing.name) { restored ->
            completeRecovery("Ж", restored)
            assertIdle("Ж: отсутствующий снимок", restored.owner)
            assertNull("Ж: снимок не был создан заново", DiagnosticExports.readPending(context, missing.name))
            withSnapshot { next ->
                onMain {
                    restored.launch(next.name)
                    assertTrue("Ж: Registry принял URI следующего экспорта",
                        restored.registry.dispatchResult(restored.registry.requestCode, Activity.RESULT_OK, Intent().setData(next.goodUri)))
                }
                awaitJob("Ж: запись следующего экспорта", restored.lastWrite)
                assertSaved("Ж: следующий экспорт", restored.owner, next)
                assertEquals("Ж: старый документ пуст", "", missing.good.readText())
            }
        }
    }

    fun activityRecreatedTwiceKeepsOneRecovery() = withSnapshot { snapshot ->
        val gate = RecoveryGate()
        val id = "snapshot:${DiagnosticExports.COMBINED_FILE_NAME}:${snapshot.name}"
        val request = DiagnosticSnapshotPreparation.Picker(id, "diagnostic-document:${UUID.randomUUID()}")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(
            IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                addDataType("text/plain")
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            null,
            true,
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var owner: DiagnosticSnapshotPreparation
                scenario.checkActivity { activity ->
                    activity.viewModelStore.clear()
                    owner = ownerFrom(activity, gate)
                    assertTrue("З: подготовлено состояние восстановленного запроса", owner.restore(request, null, null))
                }
                // Следующая Activity получает подготовленный ViewModel и запускает восстановление через onResume.
                scenario.recreate()
                assertTrue("З: чтение снимка началось", awaitEvent("З: чтение из onResume", gate.checked))
                var recovery: Job? = null
                scenario.checkActivity { recovery = owner.recoverIfNeeded(context) { error("З: повторное чтение") } }
                repeat(2) { index ->
                    scenario.recreate()
                    scenario.checkActivity { activity ->
                        val resumed = ViewModelProvider(activity)[DiagnosticSnapshotPreparation::class.java]
                        assertSame("З${index + 1}: ViewModel сохранён", owner, resumed)
                        assertSame("З${index + 1}: восстановление сохранено", recovery,
                            resumed.recoverIfNeeded(context) { error("З: второе восстановление") })
                        assertEquals("З${index + 1}: ключ не потерян", request, resumed.picker)
                        assertNull("З${index + 1}: повтор ещё не предложен", resumed.retry.value)
                    }
                }
                assertTrue("З: восстановление удержано до второго пересоздания", checkNotNull(recovery).isActive)
                gate.release.complete(Unit)
                awaitJob("З: завершение восстановления", recovery)
                assertRetry("З", owner, id)
                assertEquals("З: снимок прочитан ровно один раз", 1, gate.reads.get())
                assertEquals("З: диалог сам не запустился", 0, monitor.hits)
                assertEquals("З: исходный снимок сохранился", snapshot.report, DiagnosticExports.readPending(context, snapshot.name))
                assertEquals("З: документ остался пуст", "", snapshot.good.readText())
            }
        } finally {
            gate.release.complete(Unit)
            instrumentation.removeMonitor(monitor)
        }
    }

    fun clearingOwnerCancelsRecovery() = withSnapshot { snapshot ->
        val gate = RecoveryGate()
        withRestoredOwner(snapshot.name, gate) { restored ->
            lateinit var job: Job
            onMain {
                restored.register()
                job = checkNotNull(restored.recover())
            }
            assertTrue(awaitEvent("Проверка снимка началась до уничтожения владельца", gate.checked))
            onMain { restored.clearOwner() }
            awaitEvent("Отмена восстановления") { job.join() }
            assertTrue(job.isCancelled)
            gate.release.complete(Unit)
            onMain { assertNull(restored.owner.retry.value) }
            assertEquals(snapshot.report, DiagnosticExports.readPending(context, snapshot.name))
            assertEquals("", snapshot.good.readText())
        }
        withRestoredOwner(snapshot.name) { next ->
            completeRecovery("Новый владелец", next)
            assertRetry("Новый владелец", next.owner, snapshot.name)
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

    private fun withRestoredOwner(name: String, gate: RecoveryGate? = null, action: (Restored) -> Unit) {
        val (state, oldCode, request) = previousPicker(name)
        val store = ViewModelStore()
        lateinit var restored: Restored
        onMain {
            val owner = ownerFrom(store, gate)
            val registry = DocumentRegistry()
            registry.onRestoreInstanceState(state)
            assertTrue(owner.restore(request, null, null))
            restored = Restored(owner, registry, oldCode, request, store::clear)
        }
        try {
            action(restored)
        } finally {
            onMain {
                restored.close()
                store.clear()
            }
        }
    }

    private fun ownerFrom(store: ViewModelStore, gate: RecoveryGate? = null): DiagnosticSnapshotPreparation {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
        return ownerFrom(owner, gate)
    }

    private fun ownerFrom(owner: ViewModelStoreOwner, gate: RecoveryGate?): DiagnosticSnapshotPreparation {
        val provider = if (gate == null) ViewModelProvider(owner) else ViewModelProvider(owner, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                checkNotNull(modelClass.cast(DiagnosticSnapshotPreparation(gate::read)))
        })
        return provider[DiagnosticSnapshotPreparation::class.java]
    }

    private fun completeRecovery(label: String, restored: Restored) {
        var job: Job? = null
        onMain {
            restored.register()
            job = restored.recover()
        }
        awaitJob("$label: завершение восстановления", job)
    }

    private fun assertRetry(label: String, owner: DiagnosticSnapshotPreparation, id: String) = onMain {
        assertNull("$label: прежний ключ завершён", owner.picker)
        assertNull("$label: записи нет", owner.writing)
        assertEquals("$label: повтор относится к этому снимку", id, owner.retry.value)
        assertTrue("$label: новый экспорт ждёт решения", owner.busy)
    }

    private fun assertIdle(label: String, owner: DiagnosticSnapshotPreparation) = onMain {
        assertNull("$label: активного ключа нет", owner.picker)
        assertNull("$label: записи нет", owner.writing)
        assertNull("$label: повтор не предложен", owner.retry.value)
        assertFalse("$label: следующий экспорт доступен", owner.busy)
    }

    private fun assertSaved(label: String, owner: DiagnosticSnapshotPreparation, snapshot: Snapshot) {
        assertEquals("$label: точный текст документа", snapshot.report, snapshot.good.readText())
        assertFalse("$label: снимок удалён после записи", DiagnosticExports.hasPending(context, snapshot.name))
        assertIdle(label, owner)
    }

    private fun assertInterrupted(label: String, snapshot: Snapshot) {
        assertTrue("$label: сохранён дисковый маркер .writing", DiagnosticExports.hasInterruptedWrite(context, snapshot.name))
        assertEquals("$label: исходные байты снимка сохранены", snapshot.report, DiagnosticExports.readPending(context, snapshot.name))
        assertEquals("$label: повторная запись без подтверждения запрещена",
            DiagnosticExports.WriteClaim.Interrupted, DiagnosticExports.claimWrite(context, snapshot.name))
    }

    private fun awaitJob(label: String, job: Job?) {
        if (job == null) throw AssertionError("$label: операция не запущена")
        awaitEvent(label) { job.join() }
        assertFalse("$label: операция отменена", job.isCancelled)
    }

    private fun <T> awaitEvent(label: String, event: CompletableDeferred<T>): T = awaitEvent(label) { event.await() }

    private fun <T> awaitEvent(label: String, action: suspend () -> T): T = try {
        runBlocking { withTimeout(5_000) { action() } }
    } catch (error: TimeoutCancellationException) {
        throw AssertionError("$label: ожидаемое событие не наступило", error)
    }

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    private fun ActivityScenario<MainActivity>.checkActivity(action: (MainActivity) -> Unit) {
        var result: Result<Unit>? = null
        onActivity { activity -> result = runCatching { action(activity) } }
        checkNotNull(result).getOrThrow()
    }

    private inner class Restored(
        val owner: DiagnosticSnapshotPreparation,
        val registry: DocumentRegistry,
        val oldCode: Int,
        val request: DiagnosticSnapshotPreparation.Picker,
        val clearOwner: () -> Unit,
    ) {
        var lastWrite: Job? = null
            private set
        private val picker = DiagnosticDocumentPicker(registry) { key, uri -> lastWrite = deliver(key, uri) }

        fun register() = picker.register(checkNotNull(request.key))
        fun close() = picker.close()
        fun recover() = owner.recoverIfNeeded(context) { it }
        fun deliver(key: String?, uri: Uri?) = owner.finishPicker(context, uri, key, { it }, "transport")

        fun launch(name: String): String {
            val key = owner.startPicker(name)
            picker.register(key).launch("retry.txt")
            return key
        }

        fun dispatchOld(label: String, uri: Uri?) {
            val code = if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK
            assertTrue(label, registry.dispatchResult(oldCode, code, Intent().setData(uri)))
        }
    }

    private class RecoveryGate {
        val reads = AtomicInteger()
        val checked = CompletableDeferred<Boolean>()
        val release = CompletableDeferred<Unit>()

        suspend fun read(context: Context, name: String): Boolean {
            reads.incrementAndGet()
            val exists = withContext(Dispatchers.IO) { DiagnosticExports.hasPending(context, name) }
            checked.complete(exists)
            release.await()
            return exists
        }
    }

    private class DocumentRegistry : ActivityResultRegistry() {
        var requestCode = 0
            private set

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                                     options: ActivityOptionsCompat?) {
            this.requestCode = requestCode
        }
    }
}
