package org.adaptivedpi.android

import android.app.Application
import org.adaptivedpi.android.vpn.diagnostics.TransportDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnEventHistory

class AdaptiveDpiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        VpnEventHistory.install(this)
        TransportDiagnostics.install(this)
        runCatching { VpnDiagnostics.install(this) }
    }
}
