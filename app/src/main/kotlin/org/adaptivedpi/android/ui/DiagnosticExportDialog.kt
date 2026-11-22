package org.adaptivedpi.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaptivedpi.android.R

@Composable
internal fun DiagnosticExportDialog(
    title: Int,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    preview: String? = null,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                if (preview != null) {
                    SelectionContainer(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        Text(preview, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = {
                    onCancel()
                    onSave()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.diagnostic_save))
                }
                OutlinedButton(onClick = {
                    onCancel()
                    onShare()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.diagnostic_share))
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
