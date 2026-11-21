package org.adaptivedpi.android

import android.app.Application
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics

class AdaptiveDpiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { VpnDiagnostics.install(this) }
    }
}
