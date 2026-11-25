package org.adaptivedpi.android

import android.content.Context
import android.net.Uri
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

/** Владелец подготовки и записи, пока существует задача Activity, включая пересоздание. */
internal class DiagnosticSnapshotPreparation : ViewModel() {
    private var snapshot: Pair<String, Deferred<Unit>>? = null
    private var initialized = false
    private var write: Job? = null
    private var recovering: Pair<String?, String?>? = null
    var picker: String? = null
    var writing: String? = null
        private set
    val retry = mutableStateOf<String?>(null)
    val feedback = mutableStateOf<Int?>(null)
    val busy get() = picker != null || writing != null || retry.value != null

    fun restore(picker: String?, writing: String?, retry: String?): Boolean {
        if (initialized) return false
        initialized = true
        this.picker = picker
        this.retry.value = retry ?: writing
        if (picker != null || this.retry.value != null) recovering = picker to this.retry.value
        return true
    }

    fun recoverIfNeeded(context: Context, snapshotName: (String) -> String?) {
        val (savedPicker, savedRetry) = recovering ?: return
        recovering = null
        val id = checkNotNull(savedRetry ?: savedPicker)
        val name = snapshotName(id)
        val app = context.applicationContext
        viewModelScope.launch {
            val exists = name != null && withContext(Dispatchers.IO) { DiagnosticExports.hasPending(app, name) }
            if (savedPicker != null && picker == savedPicker) {
                picker = null
                if (exists) retry.value = id
            } else if (savedRetry != null && retry.value == savedRetry && !exists) {
                retry.value = null
            }
            if (!exists && name != null && writing != id) DiagnosticExports.releasePending(name)
        }
    }

    fun prepare(context: Context, name: String, report: String?) {
        check(snapshot == null)
        val app = context.applicationContext
        snapshot = name to viewModelScope.async<Unit>(Dispatchers.IO) {
            if (report == null) DiagnosticExports.armRetry(app, name)
            else DiagnosticExports.storePending(app, report, name)
        }
    }

    fun taskFor(name: String) = snapshot?.takeIf { it.first == name }?.second

    fun take(name: String) = taskFor(name)?.also { snapshot = null }

    fun finishPicker(context: Context, uri: Uri?, id: String, name: String?, transport: Boolean) {
        if (picker != id || writing != null) return
        picker = null
        writing = id
        val app = context.applicationContext
        write = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (uri == null) {
                        if (name != null) DiagnosticExports.deletePending(app, name)
                        return@withContext null
                    }
                    if (transport) {
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
