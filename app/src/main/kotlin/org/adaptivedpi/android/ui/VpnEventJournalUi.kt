package org.adaptivedpi.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaptivedpi.android.R
import org.adaptivedpi.android.designsystem.AdaptivePanel
import org.adaptivedpi.android.vpn.diagnostics.VpnEventHistoryState
import org.adaptivedpi.android.vpn.diagnostics.VpnJournalEvent
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun VpnEventJournal(history: VpnEventHistoryState, onClear: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    AdaptivePanel {
        Text(stringResource(R.string.vpn_journal_title), style = MaterialTheme.typography.titleMedium)
        if (history.storageError) {
            Text(stringResource(R.string.vpn_journal_storage_error), color = MaterialTheme.colorScheme.error)
        }
        if (history.droppedInformational > 0 || history.droppedCritical > 0) {
            Text(stringResource(R.string.vpn_journal_drops, history.droppedInformational, history.droppedCritical))
        }
        if (!history.loaded) {
            Text(stringResource(R.string.vpn_journal_loading))
        } else if (history.entries.isEmpty()) {
            Text(stringResource(R.string.vpn_journal_empty))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(history.entries, key = { it.id.toString() }) { event ->
                    OutlinedButton(onClick = { selectedId = event.id.toString() }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(event.type.description)
                            Text(event.heading(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (history.loaded && (history.entries.isNotEmpty() || history.storageError)) {
            TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.vpn_journal_clear)) }
        }
    }

    history.entries.firstOrNull { it.id.toString() == selectedId }?.let { event ->
        AlertDialog(
            onDismissRequest = { selectedId = null },
            title = { Text(stringResource(R.string.diagnostic_technical)) },
            text = {
                SelectionContainer {
                    Text("${event.heading()}\n${event.type.description}\n\n${event.details()}")
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedId = null }) { Text(stringResource(R.string.diagnostic_close)) }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.vpn_journal_clear_title)) },
            text = { Text(stringResource(R.string.vpn_journal_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text(stringResource(R.string.vpn_journal_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
internal fun DiagnosticExport(
    ready: Boolean,
    buildReport: () -> String,
    onSave: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    var report by remember { mutableStateOf<String?>(null) }
    OutlinedButton(onClick = { report = buildReport() }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.diagnostic_export_all))
    }
    report?.let { snapshot ->
        DiagnosticExportDialog(
            R.string.diagnostic_preview,
            onSave = { onSave(snapshot) },
            onShare = { onShare(snapshot) },
            onCancel = { report = null },
            preview = snapshot,
        )
    }
}

private val eventTimeFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss xxx", Locale.ROOT)

private fun VpnJournalEvent.heading() = "${time.format(eventTimeFormat)} · ${type.level.name}"
