package org.adaptivedpi.android.vpn

import android.net.Network
import android.net.VpnService
import java.net.Socket

@Suppress("NOTHING_TO_INLINE", "UNUSED_PARAMETER")
internal object TransportTestHooks {
    inline fun fixtureRouteStarted(service: VpnService, network: Network) = Unit
    inline fun fixtureRouteEnded(service: VpnService) = Unit

    @Suppress("NOTHING_TO_INLINE", "UNUSED_PARAMETER")
    inline fun tcpPrepared(socket: Socket) = Unit
}
