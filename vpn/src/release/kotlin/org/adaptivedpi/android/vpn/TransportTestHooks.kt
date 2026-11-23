package org.adaptivedpi.android.vpn

import java.net.Socket

internal object TransportTestHooks {
    @Suppress("NOTHING_TO_INLINE", "UNUSED_PARAMETER")
    inline fun tcpPrepared(socket: Socket) = Unit
}
