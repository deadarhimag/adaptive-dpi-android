package org.adaptivedpi.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaptivedpi.android.designsystem.AdaptiveDpiTheme
import org.adaptivedpi.android.designsystem.ThemeMode
import org.adaptivedpi.android.ui.AdaptiveDpiApp
import org.adaptivedpi.android.vpn.VpnController
import org.adaptivedpi.android.vpn.VpnState
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticEvent
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val themeMode = mutableStateOf(ThemeMode.System)
    private var pendingConsentId: String? = null
    private var pendingReportId: String? = null
    private val diagnosticRequest = mutableStateOf<UUID?>(null)

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingConsentId ?: return@registerForActivityResult
        pendingConsentId = null
        VpnController.completeConsent(this, id, result.resultCode == Activity.RESULT_OK)
        if (VpnController.state.value == VpnState.Starting) requestNotificationsOnce()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val createDiagnosticDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val id = pendingReportId
            pendingReportId = null
            if (uri == null || id == null) return@registerForActivityResult
            val event = VpnDiagnostics.events.value.firstOrNull { it.id.toString() == id }
            if (event == null) {
                Toast.makeText(this, R.string.diagnostic_missing, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { DiagnosticExports.save(applicationContext, uri, event) }
                }
                result.onSuccess { Toast.makeText(this@MainActivity, R.string.diagnostic_saved, Toast.LENGTH_SHORT).show() }
                    .onFailure { VpnDiagnostics.record(DiagnosticCode.SaveFailed, it) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingConsentId = savedInstanceState?.getString(PENDING_VPN_CONSENT)
        pendingReportId = savedInstanceState?.getString(PENDING_REPORT_ID)
        diagnosticRequest.value = if (savedInstanceState == null) {
            VpnDiagnostics.eventId(intent)
        } else {
            savedInstanceState.getString(PENDING_DIAGNOSTIC_REQUEST)?.let(UUID::fromString)
        }

        val storedTheme = settings.getString("theme", null)
        themeMode.value = ThemeMode.entries.firstOrNull { it.name == storedTheme } ?: ThemeMode.System

        enableEdgeToEdge()
        setContent {
            val mode = themeMode.value
            val useDarkTheme = mode == ThemeMode.Dark || (mode == ThemeMode.System && isSystemInDarkTheme())
            SideEffect { updateSystemBars(useDarkTheme) }

            AdaptiveDpiTheme(mode) {
                AdaptiveDpiApp(
                    vpnState = VpnController.state.collectAsStateWithLifecycle().value,
                    themeMode = mode,
                    diagnosticEvents = VpnDiagnostics.events.collectAsStateWithLifecycle().value,
                    diagnosticRequest = diagnosticRequest.value,
                    onDiagnosticRequestHandled = { diagnosticRequest.value = null },
                    onDiagnosticAcknowledge = VpnDiagnostics::acknowledge,
                    onSaveDiagnostic = ::saveDiagnostic,
                    onShareDiagnostic = ::shareDiagnostic,
                    onStartVpn = ::startVpn,
                    onStopVpn = VpnController::stop,
                    onVpnSettings = ::openVpnSettings,
                    onThemeChange = ::saveTheme,
                    onNotificationSettings = ::openNotificationSettings,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PENDING_VPN_CONSENT, pendingConsentId)
        outState.putString(PENDING_REPORT_ID, pendingReportId)
        outState.putString(PENDING_DIAGNOSTIC_REQUEST, diagnosticRequest.value?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        VpnDiagnostics.prune()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        diagnosticRequest.value = VpnDiagnostics.eventId(intent)
    }

    override fun onDestroy() {
        if (isFinishing) pendingConsentId?.let(VpnController::abandonConsent)
        super.onDestroy()
    }

    private fun startVpn() {
        val request = VpnController.requestStart(this)
        if (request != null) {
            pendingConsentId = request.id
            try {
                vpnConsent.launch(request.intent)
            } catch (exception: RuntimeException) {
                pendingConsentId = null
                VpnController.consentLaunchFailed(request.id, exception)
            }
        } else if (VpnController.state.value == VpnState.Starting) {
            requestNotificationsOnce()
        }
    }

    private fun saveTheme(mode: ThemeMode) {
        themeMode.value = mode
        settings.edit().putString("theme", mode.name).apply()
    }

    private fun saveDiagnostic(event: DiagnosticEvent) {
        if (pendingReportId != null) return
        pendingReportId = event.id.toString()
        try {
            createDiagnosticDocument.launch(DiagnosticExports.fileName(event))
        } catch (exception: RuntimeException) {
            pendingReportId = null
            VpnDiagnostics.record(DiagnosticCode.SaveFailed, exception)
        }
    }

    private fun shareDiagnostic(event: DiagnosticEvent) {
        lifecycleScope.launch {
            try {
                val send = withContext(Dispatchers.IO) { DiagnosticExports.shareIntent(applicationContext, event) }
                val chooser = Intent.createChooser(send, getString(R.string.diagnostic_share))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                chooser.clipData = send.clipData
                startActivity(chooser)
            } catch (exception: java.io.IOException) {
                VpnDiagnostics.record(DiagnosticCode.ShareFailed, exception)
            } catch (exception: RuntimeException) {
                if (exception is kotlinx.coroutines.CancellationException) throw exception
                VpnDiagnostics.record(DiagnosticCode.ShareFailed, exception)
            }
        }
    }

    private fun requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !settings.getBoolean(NOTIFICATION_REQUESTED, false)
        ) {
            settings.edit().putBoolean(NOTIFICATION_REQUESTED, true).apply()
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .onFailure { VpnDiagnostics.record(DiagnosticCode.NotificationSettingsFailed, it) }
        }
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }.onFailure { VpnDiagnostics.record(DiagnosticCode.NotificationSettingsFailed, it) }
    }

    private fun openVpnSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }.recoverCatching {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }.onFailure {
            Toast.makeText(this, R.string.vpn_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateSystemBars(dark: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    companion object {
        private const val PENDING_VPN_CONSENT = "pending_vpn_consent"
        private const val NOTIFICATION_REQUESTED = "notification_requested"
        private const val PENDING_REPORT_ID = "pending_report"
        private const val PENDING_DIAGNOSTIC_REQUEST = "pending_diagnostic_request"
    }
}
