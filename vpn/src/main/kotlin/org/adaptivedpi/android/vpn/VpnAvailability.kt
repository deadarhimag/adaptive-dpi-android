package org.adaptivedpi.android.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.os.UserManager

enum class VpnAvailability {
    Unknown, NoVisibleVpn, OwnVpn, OtherVpn, Restricted,
}

internal fun observeVpnAvailability(context: Context, state: VpnState): VpnAvailability {
    if (!state.canStart) return classifyVpnAvailability(state, null, false, false)
    val restricted = context.getSystemService(UserManager::class.java)
        ?.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN) == true
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
    val usesVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    val ownsVpn = usesVpn == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        capabilities?.ownerUid == Process.myUid()
    return classifyVpnAvailability(state, usesVpn, ownsVpn, restricted)
}

internal fun classifyVpnAvailability(
    state: VpnState,
    usesVpn: Boolean?,
    ownsVpn: Boolean,
    restricted: Boolean,
): VpnAvailability = when {
    state == VpnState.TestSessionRunning || state == VpnState.TransportStarting || state == VpnState.EngineRunning ->
        VpnAvailability.OwnVpn
    !state.canStart -> VpnAvailability.Unknown
    restricted -> VpnAvailability.Restricted
    // Сеть нашей остановленной службы может ещё быть видна Android.
    ownsVpn -> VpnAvailability.Unknown
    usesVpn == true -> VpnAvailability.OtherVpn
    usesVpn == false -> VpnAvailability.NoVisibleVpn
    else -> VpnAvailability.Unknown
}
