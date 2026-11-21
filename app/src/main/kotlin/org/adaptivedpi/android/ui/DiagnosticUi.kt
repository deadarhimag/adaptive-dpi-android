package org.adaptivedpi.android.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaptivedpi.android.R
import org.adaptivedpi.android.designsystem.AdaptivePanel
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticEvent

@Composable
internal fun DiagnosticHistory(events: List<DiagnosticEvent>, open: (DiagnosticEvent) -> Unit) {
    AdaptivePanel {
        Text(stringResource(R.string.diagnostic_history), style = MaterialTheme.typography.titleMedium)
        if (events.isEmpty()) {
            Text(stringResource(R.string.diagnostic_empty), style = MaterialTheme.typography.bodyMedium)
        }
        for (event in events) {
            OutlinedButton(onClick = { open(event) }, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(event.code.description)
                    Text(event.time.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun DiagnosticErrorDialog(
    event: DiagnosticEvent,
    canStartVpn: Boolean,
    canStopVpn: Boolean,
    onClose: () -> Unit,
    onSave: (DiagnosticEvent) -> Unit,
    onShare: (DiagnosticEvent) -> Unit,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onVpnSettings: () -> Unit,
) {
    var details by rememberSaveable(event.id.toString()) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.diagnostic_error)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(event.code.description)
                if (event.vpnStopped) {
                    Text(event.connectionNotice())
                    Text(stringResource(R.string.vpn_stop_help))
                    TextButton(onClick = onVpnSettings) { Text(stringResource(R.string.vpn_open_settings)) }
                }
                if (details) {
                    Text(stringResource(R.string.diagnostic_preview), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(event.report(), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { onSave(event) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.diagnostic_save))
                    }
                    OutlinedButton(onClick = { onShare(event) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.diagnostic_share))
                    }
                } else {
                    OutlinedButton(onClick = { details = true }) {
                        Text(stringResource(R.string.diagnostic_technical))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.diagnostic_close)) }
        },
        dismissButton = if (canStopVpn) {
            {
                TextButton(onClick = onStopVpn) { Text(stringResource(R.string.vpn_stop)) }
            }
        } else if (event.vpnStopped && canStartVpn) {
            {
                TextButton(onClick = {
                    onClose()
                    onStartVpn()
                }) { Text(stringResource(R.string.diagnostic_restart_vpn)) }
            }
        } else null,
    )
}
