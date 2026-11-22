package org.adaptivedpi.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.adaptivedpi.android.R
import org.adaptivedpi.android.designsystem.AdaptivePanel
import org.adaptivedpi.android.designsystem.ThemeMode
import org.adaptivedpi.android.vpn.VpnState
import org.adaptivedpi.android.vpn.TransportSnapshot
import org.adaptivedpi.android.vpn.ExperimentalTarget
import org.adaptivedpi.android.vpn.ExperimentalApplication
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticEvent
import org.adaptivedpi.android.vpn.diagnostics.VpnEventHistoryState
import java.util.UUID

private enum class Screen { Home, Settings, Diagnostics, About }

@Composable
internal fun AdaptiveDpiApp(
    vpnState: VpnState,
    themeMode: ThemeMode,
    diagnosticEvents: List<DiagnosticEvent>,
    diagnosticRequest: UUID?,
    onDiagnosticRequestHandled: () -> Unit,
    onDiagnosticAcknowledge: (UUID) -> Unit,
    onSaveDiagnostic: (DiagnosticEvent) -> Unit,
    onShareDiagnostic: (DiagnosticEvent) -> Unit,
    vpnEventHistory: VpnEventHistoryState,
    onClearVpnEventHistory: () -> Unit,
    onBuildCombinedReport: () -> String,
    onSaveCombinedReport: (String) -> Unit,
    onShareCombinedReport: (String) -> Unit,
    onStartVpn: () -> Unit,
    onStartTransport: (String) -> Unit,
    onStartApplicationTransport: (String) -> Unit,
    transport: TransportSnapshot?,
    onStopVpn: () -> Unit,
    onVpnSettings: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onNotificationSettings: () -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var selectedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(diagnosticRequest) {
        if (diagnosticRequest != null) {
            screen = Screen.Diagnostics
            selectedEventId = diagnosticRequest.toString()
            onDiagnosticRequestHandled()
        }
    }
    BackHandler(enabled = screen != Screen.Home) {
        screen = if (screen == Screen.About) Screen.Settings else Screen.Home
    }

    Scaffold { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (screen) {
                Screen.Home -> ScreenContent(R.string.app_name) {
                    HomeScreen(
                        vpnState = vpnState,
                        onStartVpn = onStartVpn,
                        onStartTransport = onStartTransport,
                        onStartApplicationTransport = onStartApplicationTransport,
                        transport = transport,
                        onStopVpn = onStopVpn,
                        onVpnSettings = onVpnSettings,
                        onSettings = { screen = Screen.Settings },
                        onDiagnostics = { screen = Screen.Diagnostics },
                    )
                }

                Screen.Settings -> ScreenContent(R.string.settings, onBack = { screen = Screen.Home }) {
                    SettingsScreen(themeMode, onThemeChange, onNotificationSettings) { screen = Screen.About }
                }

                Screen.Diagnostics -> ScreenContent(R.string.diagnostics, onBack = { screen = Screen.Home }) {
                    DiagnosticExport(vpnEventHistory.loaded, onBuildCombinedReport, onSaveCombinedReport, onShareCombinedReport)
                    VpnEventJournal(vpnEventHistory, onClearVpnEventHistory)
                    DiagnosticsScreen(vpnState, transport)
                    VpnStopHelp(onVpnSettings)
                    DiagnosticHistory(diagnosticEvents) { selectedEventId = it.id.toString() }
                }

                Screen.About -> ScreenContent(
                    R.string.about_title,
                    onBack = { screen = Screen.Settings },
                    backLabel = R.string.back_to_settings,
                ) {
                    AboutScreen()
                }
            }
        }
    }
    val event = diagnosticEvents.firstOrNull { it.id.toString() == selectedEventId }
        ?: diagnosticEvents.firstOrNull { !it.acknowledged }
    if (event != null) {
        DiagnosticErrorDialog(
            event = event,
            canStartVpn = vpnState.canStart,
            canStopVpn = vpnState.canStop,
            onClose = {
                onDiagnosticAcknowledge(event.id)
                selectedEventId = null
            },
            onSave = onSaveDiagnostic,
            onShare = onShareDiagnostic,
            onStartVpn = onStartVpn,
            onStopVpn = onStopVpn,
            onVpnSettings = onVpnSettings,
        )
    }
}

@Composable
private fun ScreenContent(
    title: Int,
    onBack: (() -> Unit)? = null,
    backLabel: Int = R.string.back_to_home,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text(stringResource(backLabel)) }
        }
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun HomeScreen(
    vpnState: VpnState,
    onStartVpn: () -> Unit,
    onStartTransport: (String) -> Unit,
    onStartApplicationTransport: (String) -> Unit,
    transport: TransportSnapshot?,
    onStopVpn: () -> Unit,
    onVpnSettings: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    AdaptivePanel {
        VpnStatus(vpnState)
        Text(
            text = stringResource(
                when {
                    vpnState == VpnState.TransportStarting -> R.string.vpn_transport_initializing
                    vpnState == VpnState.EngineRunning && transport?.applicationMode == true -> R.string.vpn_app_transport_scope
                    vpnState == VpnState.EngineRunning -> R.string.vpn_transport_scope
                    else -> R.string.vpn_test_scope
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val canStop = vpnState.canStop
        Button(
            onClick = if (canStop) onStopVpn else onStartVpn,
            enabled = canStop || vpnState.canStart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (canStop) R.string.vpn_stop else R.string.vpn_start))
        }
    }

    if (vpnState == VpnState.Stopping || vpnState == VpnState.Failed || vpnState == VpnState.StartFailed) {
        VpnStopHelp(onVpnSettings)
    }

    AdaptivePanel {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.smart_bypass), style = MaterialTheme.typography.titleMedium)
                EngineStatus(vpnState, transport)
            }
            Switch(checked = false, onCheckedChange = null, enabled = false)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings))
        }
        OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.diagnostics))
        }
    }
    if (vpnState.canStart) {
        var application by rememberSaveable { mutableStateOf("com.android.chrome") }
        AdaptivePanel {
            Text(stringResource(R.string.vpn_app_transport_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.vpn_app_transport_scope))
            OutlinedTextField(
                value = application,
                onValueChange = { if (it.length <= 223) application = it },
                label = { Text(stringResource(R.string.vpn_app_transport_package)) },
                singleLine = true,
            )
            OutlinedButton(
                onClick = { onStartApplicationTransport(application.trim()) },
                enabled = ExperimentalApplication.isPackageName(application.trim()),
            ) { Text(stringResource(R.string.vpn_app_transport_start)) }
        }
        var target by rememberSaveable { mutableStateOf("") }
        AdaptivePanel {
            Text(stringResource(R.string.vpn_transport_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.vpn_transport_scope))
            OutlinedTextField(
                value = target,
                onValueChange = { if (it.length <= 15) target = it },
                label = { Text(stringResource(R.string.vpn_transport_address)) },
                singleLine = true,
            )
            OutlinedButton(
                onClick = { onStartTransport(target) },
                enabled = ExperimentalTarget.parse(target) != null,
            ) { Text(stringResource(R.string.vpn_transport_start)) }
        }
    }
}

@Composable
private fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onNotifications: () -> Unit,
    onAbout: () -> Unit,
) {
    AdaptivePanel {
        Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium)
        Column(modifier = Modifier.selectableGroup()) {
            for (mode in ThemeMode.entries) {
                val selected = mode == themeMode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected, onClick = { onThemeChange(mode) }, role = Role.RadioButton),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    val label = when (mode) {
                        ThemeMode.System -> R.string.theme_system
                        ThemeMode.Light -> R.string.theme_light
                        ThemeMode.Dark -> R.string.theme_dark
                    }
                    Text(stringResource(label))
                }
            }
        }
    }
    AdaptivePanel {
        Text(stringResource(R.string.notifications), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.notifications_description), style = MaterialTheme.typography.bodyMedium)
    }
    AdaptivePanel {
        Text(stringResource(R.string.diagnostic_notifications), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.diagnostic_notifications_description), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onNotifications) {
            Text(stringResource(R.string.open_notification_settings))
        }
    }
    OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.about_title))
    }
}

@Composable
private fun VpnStopHelp(onVpnSettings: () -> Unit) {
    AdaptivePanel {
        Text(stringResource(R.string.vpn_stop_help_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.vpn_stop_help), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onVpnSettings) { Text(stringResource(R.string.vpn_open_settings)) }
    }
}

@Composable
private fun DiagnosticsScreen(vpnState: VpnState, transport: TransportSnapshot?) {
    AdaptivePanel {
        VpnStatus(vpnState)
    }
    AdaptivePanel {
        Text(stringResource(R.string.engine_status), style = MaterialTheme.typography.titleMedium)
        EngineStatus(vpnState, transport)
        if (transport != null) {
            if (transport.applicationMode) {
                Text(stringResource(R.string.vpn_app_transport_scope))
                Text(stringResource(R.string.vpn_app_transport_connections, transport.openedTcp, transport.closedTcp, transport.openedUdp, transport.closedUdp))
            }
            Text(
                stringResource(
                    R.string.vpn_transport_counters, transport.tunSentPackets, transport.tunReceivedPackets,
                    transport.sentBytes, transport.receivedBytes, transport.connections, transport.errors,
                ),
            )
        }
        Text(stringResource(R.string.diagnostics_description), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VpnStatus(state: VpnState) {
    val title = when (state) {
        VpnState.Idle -> R.string.vpn_idle
        VpnState.AwaitingConsent -> R.string.vpn_awaiting_consent
        VpnState.Starting -> R.string.vpn_starting
        VpnState.TestSessionRunning -> R.string.vpn_test_session_running
        VpnState.TransportStarting -> R.string.vpn_transport_initializing
        VpnState.EngineRunning -> R.string.vpn_engine_running
        VpnState.Stopping -> R.string.vpn_stopping
        VpnState.ConsentDenied -> R.string.vpn_consent_denied
        VpnState.ConsentRevoked -> R.string.vpn_consent_revoked
        VpnState.StartFailed -> R.string.vpn_start_failed
        VpnState.Failed -> R.string.vpn_failed
    }
    Text(
        text = stringResource(R.string.vpn_status),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = stringResource(title), style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun EngineStatus(vpnState: VpnState, transport: TransportSnapshot?) {
    Text(
        text = stringResource(
            when {
                vpnState == VpnState.TransportStarting -> R.string.vpn_transport_initializing
                transport?.running == true && transport.transferred -> R.string.vpn_transport_transferred
                transport?.running == true -> R.string.vpn_transport_running
                transport != null -> R.string.vpn_transport_stopped
                else -> R.string.engine_unavailable
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
