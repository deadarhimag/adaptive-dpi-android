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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.adaptivedpi.android.R
import org.adaptivedpi.android.designsystem.AdaptiveIcons
import org.adaptivedpi.android.designsystem.AdaptivePanel
import org.adaptivedpi.android.designsystem.ListRowItem
import org.adaptivedpi.android.designsystem.PowerButton
import org.adaptivedpi.android.designsystem.Spacing
import org.adaptivedpi.android.designsystem.StatusBadge
import org.adaptivedpi.android.designsystem.StatusLevel
import org.adaptivedpi.android.designsystem.ThemeMode
import org.adaptivedpi.android.vpn.VpnState
import org.adaptivedpi.android.vpn.VpnAvailability
import org.adaptivedpi.android.vpn.TransportSnapshot
import org.adaptivedpi.android.vpn.ExperimentalTarget
import org.adaptivedpi.android.vpn.ExperimentalApplication
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticEvent
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.TransportJournalView
import org.adaptivedpi.android.vpn.diagnostics.VpnEventHistoryState
import java.util.UUID

private enum class Screen { Home, Diagnostics, Settings, Developer, About }

private class NavigationItem(val screen: Screen, @DrawableRes val icon: Int, @StringRes val label: Int)

private val navigationItems = listOf(
    NavigationItem(Screen.Home, AdaptiveIcons.Home, R.string.nav_home),
    NavigationItem(Screen.Diagnostics, AdaptiveIcons.Journal, R.string.diagnostics),
    NavigationItem(Screen.Settings, AdaptiveIcons.Settings, R.string.settings),
)

/** Пункт нижней панели, к которому относится экран. */
private fun Screen.tab(): Screen = when (this) {
    Screen.Developer, Screen.About -> Screen.Settings
    else -> this
}

@Composable
internal fun AdaptiveDpiApp(
    vpnState: VpnState,
    resourcesBlocked: Boolean,
    cleanupDelay: String?,
    vpnAvailability: VpnAvailability,
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
    transportJournal: TransportJournalView,
    onSaveTransportJournal: () -> Unit,
    onShareTransportJournal: () -> Unit,
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
        screen = if (screen == Screen.About || screen == Screen.Developer) Screen.Settings else Screen.Home
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                for (item in navigationItems) {
                    NavigationBarItem(
                        selected = screen.tab() == item.screen,
                        onClick = { screen = item.screen },
                        icon = { Icon(painterResource(item.icon), contentDescription = null) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        },
    ) { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (screen) {
                Screen.Home -> ScreenContent(R.string.app_name) {
                    HomeScreen(
                        vpnState = vpnState,
                        resourcesBlocked = resourcesBlocked,
                        cleanupDelay = cleanupDelay,
                        vpnAvailability = vpnAvailability,
                        onStartVpn = onStartVpn,
                        transport = transport,
                        onStopVpn = onStopVpn,
                        onVpnSettings = onVpnSettings,
                    )
                }

                Screen.Settings -> ScreenContent(R.string.settings) {
                    SettingsScreen(
                        themeMode,
                        onThemeChange,
                        onNotificationSettings,
                        onDeveloper = { screen = Screen.Developer },
                        onAbout = { screen = Screen.About },
                    )
                }

                Screen.Developer -> ScreenContent(
                    R.string.developer_title,
                    onBack = { screen = Screen.Settings },
                    backLabel = R.string.back_to_settings,
                ) {
                    DeveloperScreen(
                        canRun = vpnState.canStart && !resourcesBlocked,
                        onStartTransport = onStartTransport,
                        onStartApplicationTransport = onStartApplicationTransport,
                    )
                }

                Screen.Diagnostics -> ScreenContent(R.string.diagnostics) {
                    DiagnosticExport(vpnEventHistory.loaded, onBuildCombinedReport, onSaveCombinedReport, onShareCombinedReport)
                    VpnEventJournal(vpnEventHistory, onClearVpnEventHistory)
                    DiagnosticsScreen(vpnState, transport, resourcesBlocked)
                    if (resourcesBlocked) Text(stringResource(R.string.vpn_cleanup_pending))
                    cleanupDelay?.let { Text(stringResource(R.string.vpn_cleanup_delayed, it)) }
                    VpnAvailabilityNotice(vpnAvailability, vpnState, onVpnSettings)
                    TransportJournalPanel(transportJournal, onSaveTransportJournal, onShareTransportJournal)
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
        ?: diagnosticEvents.firstOrNull { !it.acknowledged && it.code != DiagnosticCode.VpnCleanupDelayed }
    if (event != null) {
        DiagnosticErrorDialog(
            event = event,
            canStartVpn = vpnState.canStart && !resourcesBlocked,
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
            .widthIn(max = Spacing.contentMaxWidth)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xxl, vertical = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
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
    resourcesBlocked: Boolean,
    cleanupDelay: String?,
    vpnAvailability: VpnAvailability,
    onStartVpn: () -> Unit,
    transport: TransportSnapshot?,
    onStopVpn: () -> Unit,
    onVpnSettings: () -> Unit,
) {
    val canStop = vpnState.canStop
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        PowerButton(
            label = stringResource(if (canStop) R.string.vpn_stop else R.string.vpn_start),
            active = canStop,
            enabled = canStop || vpnState.canStart && !resourcesBlocked,
            onClick = if (canStop) onStopVpn else onStartVpn,
        )
        StatusBadge(stringResource(vpnTitle(vpnState)), vpnState.statusLevel())
    }

    AdaptivePanel {
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
        if (resourcesBlocked) Text(stringResource(R.string.vpn_cleanup_pending))
        cleanupDelay?.let { Text(stringResource(R.string.vpn_cleanup_delayed, it)) }
    }

    VpnAvailabilityNotice(vpnAvailability, vpnState, onVpnSettings)

    if (cleanupDelay != null || vpnState == VpnState.Stopping || vpnState == VpnState.Failed || vpnState == VpnState.StartFailed) {
        VpnStopHelp(onVpnSettings)
    }

    AdaptivePanel {
        Text(stringResource(R.string.smart_bypass), style = MaterialTheme.typography.titleMedium)
        EngineStatus(vpnState, transport, resourcesBlocked)
    }
}

@Composable
private fun DeveloperScreen(
    canRun: Boolean,
    onStartTransport: (String) -> Unit,
    onStartApplicationTransport: (String) -> Unit,
) {
    if (!canRun) {
        AdaptivePanel { Text(stringResource(R.string.developer_unavailable)) }
        return
    }
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

private fun VpnState.statusLevel(): StatusLevel = when (this) {
    VpnState.TestSessionRunning, VpnState.EngineRunning -> StatusLevel.Working
    VpnState.AwaitingConsent, VpnState.Starting, VpnState.TransportStarting, VpnState.Stopping -> StatusLevel.Fixing
    VpnState.StartFailed, VpnState.Failed -> StatusLevel.Error
    VpnState.Idle, VpnState.ConsentDenied, VpnState.ConsentRevoked -> StatusLevel.Unavailable
}

@Composable
private fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onNotifications: () -> Unit,
    onDeveloper: () -> Unit,
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
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
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
    AdaptivePanel {
        ListRowItem(
            title = stringResource(R.string.developer_title),
            subtitle = stringResource(R.string.developer_description),
            onClick = onDeveloper,
        )
        ListRowItem(title = stringResource(R.string.about_title), onClick = onAbout)
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
private fun DiagnosticsScreen(vpnState: VpnState, transport: TransportSnapshot?, resourcesBlocked: Boolean) {
    AdaptivePanel {
        VpnStatus(vpnState)
    }
    AdaptivePanel {
        Text(stringResource(R.string.engine_status), style = MaterialTheme.typography.titleMedium)
        EngineStatus(vpnState, transport, resourcesBlocked)
        if (transport != null) {
            Text(stringResource(if (transport.finalized) R.string.vpn_transport_final else R.string.vpn_transport_provisional))
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

private fun vpnTitle(state: VpnState): Int = when (state) {
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

@Composable
private fun VpnStatus(state: VpnState) {
    val title = vpnTitle(state)
    Text(
        text = stringResource(R.string.vpn_status),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = stringResource(title), style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun EngineStatus(vpnState: VpnState, transport: TransportSnapshot?, resourcesBlocked: Boolean) {
    Text(
        text = stringResource(
            when {
                resourcesBlocked -> R.string.vpn_transport_release_unknown
                vpnState == VpnState.Stopping -> R.string.vpn_stopping
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
