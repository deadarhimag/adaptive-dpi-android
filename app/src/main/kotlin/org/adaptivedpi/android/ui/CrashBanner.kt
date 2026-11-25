package org.adaptivedpi.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticEvent

/** События, о которых говорит плашка на главном экране, а не окно при запуске. */
internal val DiagnosticEvent.isCrashNotice: Boolean
    get() = code == DiagnosticCode.AppCrash || code == DiagnosticCode.VpnInterrupted ||
        code == DiagnosticCode.ProcessExitNative || code == DiagnosticCode.ProcessExitAnr

/** Событие для плашки: сначала то, из-за которого остановился VPN, иначе самое новое. */
internal fun crashBannerEvent(events: List<DiagnosticEvent>): DiagnosticEvent? {
    val notices = events.filter { it.isCrashNotice && !it.acknowledged }
    return notices.firstOrNull { it.vpnStopped } ?: notices.firstOrNull()
}

@Composable
internal fun CrashBanner(
    event: DiagnosticEvent,
    canStartVpn: Boolean,
    onStartVpn: () -> Unit,
    onSave: (DiagnosticEvent) -> Unit,
    onShare: (DiagnosticEvent) -> Unit,
    onClose: () -> Unit,
) {
    var showExport by rememberSaveable(event.id.toString()) { mutableStateOf(false) }
    if (showExport) {
        DiagnosticExportDialog(
            R.string.diagnostic_export_error,
            onSave = { onSave(event) },
            onShare = { onShare(event) },
            onCancel = { showExport = false },
        )
    }
    AdaptivePanel {
        Text(
            stringResource(if (event.vpnStopped) R.string.crash_banner_vpn_stopped else R.string.crash_banner_title),
            style = MaterialTheme.typography.titleMedium,
        )
        if (event.vpnStopped) Text(event.connectionNotice(), style = MaterialTheme.typography.bodyMedium)
        if (event.vpnStopped && canStartVpn) {
            Button(onClick = onStartVpn, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.crash_banner_enable))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showExport = true }) { Text(stringResource(R.string.crash_banner_send)) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.diagnostic_close)) }
        }
    }
}
