package org.adaptivedpi.android

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics
import java.util.UUID

/** Связывает callback AndroidX с ключом запуска, который переживает пересоздание Activity. */
internal class DiagnosticDocumentPicker(
    private val registry: ActivityResultRegistry,
    private val onResult: (String, Uri?) -> Unit,
) {
    private var launcher: ActivityResultLauncher<String>? = null

    fun register(key: String): ActivityResultLauncher<String> {
        close()
        return registry.register(key, ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            onResult(key, uri)
        }.also { launcher = it }
    }

    fun close() {
        launcher?.unregister()
        launcher = null
    }
}

/** Владелец подготовки и записи, пока существует задача Activity, включая пересоздание. */
internal class DiagnosticSnapshotPreparation(
    private val snapshotExists: suspend (Context, String) -> Boolean = { context, name ->
        withContext(Dispatchers.IO) { DiagnosticExports.hasPending(context, name) }
    },
) : ViewModel() {
    data class Picker(val id: String, val key: String?)
    data class Preparation(val name: String, val creating: Boolean, val job: Deferred<DiagnosticExports.PreparedSnapshot>)
    private data class Recovery(val id: String, val picker: Picker?)

    private var snapshot: Preparation? = null
    private var initialized = false
    private var write: Job? = null
    private var recovering: Recovery? = null
    private var recoveryJob: Job? = null
    var picker: Picker? = null
        private set
    var writing: String? = null
        private set
    val retry = mutableStateOf<String?>(null)
    val feedback = mutableStateOf<Int?>(null)
    val retryOnSave get() = retry.value ?: recovering?.takeIf { it.picker == null }?.id
    val busy get() = picker != null || writing != null || retryOnSave != null

    fun restore(picker: Picker?, writing: String?, retry: String?): Boolean {
        if (initialized) return false
        initialized = true
        this.picker = picker
        (picker?.id ?: retry ?: writing)?.let { recovering = Recovery(it, picker) }
        return true
    }

    @MainThread
    fun recoverIfNeeded(context: Context, snapshotName: (String) -> String?): Job? {
        val saved = recovering ?: return null
        recoveryJob?.let { return it }
        val name = snapshotName(saved.id)
        val app = context.applicationContext
        return viewModelScope.launch {
            val exists = name != null && snapshotExists(app, name)
            // Принятый callback уже передал запрос записи или отмене.
            if (recovering !== saved) return@launch
            // Эта публикация на Main окончательно завершает прежний ключ.
            recovering = null
            if (saved.picker != null) {
                picker = null
            }
            if (exists) retry.value = saved.id
            else if (name != null) DiagnosticExports.releasePending(name)
        }.also { recoveryJob = it }
    }

    fun startPicker(id: String): String {
        check(!busy)
        val key = "diagnostic-document:${UUID.randomUUID()}"
        picker = Picker(id, key)
        return key
    }

    fun abandonPicker(key: String) {
        if (picker?.key == key) picker = null
    }

    fun prepare(context: Context, name: String, report: String?) {
        check(snapshot == null)
        val app = context.applicationContext
        val job = viewModelScope.async(Dispatchers.IO) {
            if (report == null) DiagnosticExports.armRetry(app, name)
            else {
                DiagnosticExports.storePending(app, report, name)
                DiagnosticExports.PreparedSnapshot.Ready
            }
        }
        snapshot = Preparation(name, report != null, job)
    }

    fun taskFor(name: String) = snapshot?.takeIf { it.name == name }

    fun take(name: String) = taskFor(name)?.also { snapshot = null }?.job

    @MainThread
    fun finishPicker(context: Context, uri: Uri?, key: String?, snapshotName: (String) -> String?, transportId: String): Job? {
        val request = picker?.takeIf { it.key == key } ?: return null
        if (writing != null) return null
        val id = request.id
        val name = snapshotName(id)
        picker = null
        recovering = null
        writing = id
        val app = context.applicationContext
        write = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (uri == null) {
                        if (name != null && !DiagnosticExports.cancelPending(app, name)) {
                            return@withContext R.string.diagnostic_outcome_unknown
                        }
                        return@withContext null
                    }
                    if (id == transportId) {
                        ensureActive()
                        DiagnosticExports.saveTransportJournal(app, uri)
                    } else {
                        if (name != null) {
                            when (DiagnosticExports.claimWrite(app, name)) {
                                DiagnosticExports.WriteClaim.Missing -> return@withContext R.string.diagnostic_missing
                                DiagnosticExports.WriteClaim.Interrupted -> return@withContext R.string.diagnostic_outcome_unknown
                                DiagnosticExports.WriteClaim.Claimed -> Unit
                            }
                        }
                        val report = if (name != null) DiagnosticExports.readPending(app, name)
                            else VpnDiagnostics.events.value.firstOrNull { it.id.toString() == id }?.report()
                        if (report == null) return@withContext R.string.diagnostic_missing
                        ensureActive()
                        DiagnosticExports.save(app, uri, report)
                        if (name != null) DiagnosticExports.deletePending(app, name)
                    }
                    R.string.diagnostic_saved
                }
                if (result == R.string.diagnostic_outcome_unknown) {
                    retry.value = id
                } else {
                    if (result == R.string.diagnostic_missing && name != null) DiagnosticExports.releasePending(name)
                    feedback.value = result
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (name != null) retry.value = id
                VpnDiagnostics.recordAsync(DiagnosticCode.SaveFailed, error)
                feedback.value = R.string.diagnostic_save_failed_export
            } finally {
                writing = null
            }
        }
        return write
    }

    fun clearFinishedOwner(name: String) {
        val operation = take(name) ?: write
        if (operation == null) DiagnosticExports.releasePending(name)
        else operation.invokeOnCompletion { DiagnosticExports.releasePending(name) }
    }

    fun discard(context: Context, name: String) {
        DiagnosticExports.releasePending(name)
        val app = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) { DiagnosticExports.deletePending(app, name) }
    }
}
