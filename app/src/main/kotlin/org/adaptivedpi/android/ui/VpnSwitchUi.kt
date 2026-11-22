package org.adaptivedpi.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.adaptivedpi.android.R
import org.adaptivedpi.android.designsystem.AdaptivePanel
import org.adaptivedpi.android.vpn.VpnAvailability
import org.adaptivedpi.android.vpn.VpnState

@Composable
internal fun VpnAvailabilityNotice(availability: VpnAvailability, state: VpnState, onVpnSettings: () -> Unit) {
    val title = when (availability) {
        VpnAvailability.Restricted -> R.string.vpn_switch_restricted_title
        VpnAvailability.OtherVpn -> R.string.vpn_switch_title
        else -> null
    }
    val message = when {
        availability == VpnAvailability.Restricted -> R.string.vpn_switch_restricted_message
        state == VpnState.ConsentDenied || state == VpnState.ConsentRevoked -> R.string.vpn_switch_consent_hint
        availability == VpnAvailability.OtherVpn -> R.string.vpn_switch_message
        else -> return
    }
    AdaptivePanel {
        if (title != null) Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(message))
        VpnSwitchSettingsButton(onVpnSettings)
    }
}

@Composable
internal fun VpnSwitchDialog(
    restricted: Boolean,
    onSwitch: () -> Unit,
    onVpnSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (restricted) R.string.vpn_switch_restricted_title else R.string.vpn_switch_title))
        },
        text = {
            Column {
                Text(stringResource(if (restricted) R.string.vpn_switch_restricted_message else R.string.vpn_switch_message))
                if (!restricted) VpnSwitchSettingsButton(onVpnSettings)
            }
        },
        dismissButton = {
            if (!restricted) TextButton(onClick = onDismiss) { Text(stringResource(R.string.vpn_switch_cancel)) }
        },
        confirmButton = {
            if (restricted) {
                VpnSwitchSettingsButton(onVpnSettings)
            } else {
                TextButton(onClick = onSwitch) { Text(stringResource(R.string.vpn_switch_confirm)) }
            }
        },
    )
}

@Composable
private fun VpnSwitchSettingsButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(R.string.vpn_switch_settings)) }
}
