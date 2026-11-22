package org.adaptivedpi.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.adaptivedpi.android.R
import org.adaptivedpi.android.designsystem.AdaptivePanel
import org.adaptivedpi.android.vpn.diagnostics.TransportJournalView

@Composable
internal fun TransportJournalPanel(journal: TransportJournalView, onSave: () -> Unit, onShare: () -> Unit) {
    var visible by rememberSaveable { mutableIntStateOf(15) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    AdaptivePanel {
        Text(stringResource(R.string.transport_summary), style = MaterialTheme.typography.titleMedium)
        val session = journal.session
        if (session != null) Text(session.text(), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.transport_storage, journal.bytes, journal.storedRecords))
        if (journal.storageError) Text(stringResource(R.string.transport_storage_error), color = MaterialTheme.colorScheme.error)
    }
    AdaptivePanel {
        Text(stringResource(R.string.transport_journal), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { showExport = true }, enabled = journal.ready) {
            Text(stringResource(R.string.transport_export))
        }
        Text(stringResource(R.string.transport_privacy), style = MaterialTheme.typography.bodySmall)
        if (journal.entries.isEmpty()) {
            Text(stringResource(if (journal.ready) R.string.transport_empty else R.string.transport_loading))
        }
        for (entry in journal.entries.take(visible)) Text(entry.text(), style = MaterialTheme.typography.bodySmall)
        if (visible < journal.entries.size) {
            OutlinedButton(onClick = { visible += 15 }) { Text(stringResource(R.string.transport_show_more)) }
        }
    }
    if (showExport) {
        DiagnosticExportDialog(R.string.transport_export, onSave, onShare, onCancel = { showExport = false })
    }
}
