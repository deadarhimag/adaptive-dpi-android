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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaptivedpi.android.designsystem.AdaptiveDpiTheme
import org.adaptivedpi.android.designsystem.ThemeMode
import org.adaptivedpi.android.ui.AdaptiveDpiApp
import org.adaptivedpi.android.ui.VpnSwitchDialog
import org.adaptivedpi.android.vpn.VpnAvailability
import org.adaptivedpi.android.vpn.VpnController
import org.adaptivedpi.android.vpn.VpnState
import org.adaptivedpi.android.vpn.diagnostics.BuildIdentity
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticEvent
import org.adaptivedpi.android.vpn.diagnostics.TransportDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnEventHistory
import org.adaptivedpi.android.vpn.diagnostics.combinedDiagnosticReport
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val themeMode = mutableStateOf(ThemeMode.System)
    private val vpnAvailability = mutableStateOf(VpnAvailability.Unknown)
    private val pendingVpnSwitch = mutableStateOf<VpnSelection?>(null)
    private var pendingConsentId: String? = null
    private var pendingTransportTarget: String? = null
    private var pendingTransportApplication: String? = null
    private var pendingReportId: String? = null
    private val diagnosticRequest = mutableStateOf<UUID?>(null)

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingConsentId ?: return@registerForActivityResult
        pendingConsentId = null
        val target = pendingTransportTarget.also { pendingTransportTarget = null }
        val application = pendingTransportApplication.also { pendingTransportApplication = null }
        VpnController.completeConsent(this, id, result.resultCode == Activity.RESULT_OK, target, application)
        if (VpnController.state.value == VpnState.Starting) requestNotificationsOnce()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val createDiagnosticDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val id = pendingReportId
            pendingReportId = null
            if (id == null) return@registerForActivityResult
            val pending = id.takeIf { it.startsWith(COMBINED_REPORT_PREFIX) }?.removePrefix(COMBINED_REPORT_PREFIX)
            lifecycleScope.launch {
                try {
                    if (uri == null) return@launch
                    val report = when {
                        pending != null -> withContext(Dispatchers.IO) { DiagnosticExports.readPending(applicationContext, pending) }
                        id == TRANSPORT_REPORT_ID -> null
                        else -> VpnDiagnostics.events.value.firstOrNull { it.id.toString() == id }?.report()
                    }
                    if (report == null && id != TRANSPORT_REPORT_ID) {
                        Toast.makeText(this@MainActivity, R.string.diagnostic_missing, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        if (id == TRANSPORT_REPORT_ID) {
                            DiagnosticExports.saveTransportJournal(applicationContext, uri)
                        } else {
                            DiagnosticExports.save(applicationContext, uri, checkNotNull(report))
                        }
                    }
                    Toast.makeText(this@MainActivity, R.string.diagnostic_saved, Toast.LENGTH_SHORT).show()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    reportExportError(DiagnosticCode.SaveFailed, error)
                } finally {
                    if (pending != null) withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { DiagnosticExports.deletePending(applicationContext, pending) }
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingConsentId = savedInstanceState?.getString(PENDING_VPN_CONSENT)
        pendingTransportTarget = savedInstanceState?.getString("pending_transport_target")
        pendingTransportApplication = savedInstanceState?.getString("pending_transport_application")
        savedInstanceState?.getBundle(PENDING_VPN_SWITCH)?.let {
            pendingVpnSwitch.value = VpnSelection(it.getString(SWITCH_TARGET), it.getString(SWITCH_APPLICATION))
        }
        // Пустой префикс - снимок ещё не был записан, системный диалог не открывался.
        pendingReportId = savedInstanceState?.getString(PENDING_REPORT_ID)?.takeUnless { it == COMBINED_REPORT_PREFIX }
        savedInstanceState?.getString(PREVIOUS_PROCESS)?.let(VpnController::activityRestored)
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
                val vpnState = VpnController.state.collectAsStateWithLifecycle().value
                val resourcesBlocked = VpnController.resourcesBlocked.collectAsStateWithLifecycle().value
                val cleanupDelay = VpnController.cleanupDelay.collectAsStateWithLifecycle().value
                LaunchedEffect(vpnState, resourcesBlocked) { refreshVpnAvailability() }
                AdaptiveDpiApp(
                    vpnState = vpnState,
                    resourcesBlocked = resourcesBlocked,
                    cleanupDelay = cleanupDelay,
                    vpnAvailability = vpnAvailability.value,
                    themeMode = mode,
                    diagnosticEvents = VpnDiagnostics.events.collectAsStateWithLifecycle().value,
                    diagnosticRequest = diagnosticRequest.value,
                    onDiagnosticRequestHandled = { diagnosticRequest.value = null },
                    onDiagnosticAcknowledge = VpnDiagnostics::acknowledgeAsync,
                    onSaveDiagnostic = ::saveDiagnostic,
                    onShareDiagnostic = ::shareDiagnostic,
                    vpnEventHistory = VpnEventHistory.state.collectAsStateWithLifecycle().value,
                    onClearVpnEventHistory = ::clearVpnEventHistory,
                    onBuildCombinedReport = ::buildCombinedReport,
                    onSaveCombinedReport = ::saveCombinedReport,
                    onShareCombinedReport = { shareReport(DiagnosticExports.COMBINED_FILE_NAME, it) },
                    onStartVpn = { requestVpnStart() },
                    onStartTransport = { requestVpnStart(it) },
                    onStartApplicationTransport = { requestVpnStart(application = it) },
                    transport = VpnController.transport.collectAsStateWithLifecycle().value,
                    transportJournal = TransportDiagnostics.state.collectAsStateWithLifecycle().value,
                    onSaveTransportJournal = ::requestSaveTransportJournal,
                    onShareTransportJournal = {
                        shareReport { DiagnosticExports.shareTransportJournal(applicationContext) }
                    },
                    onStopVpn = VpnController::stop,
                    onVpnSettings = ::openVpnSettings,
                    onThemeChange = ::saveTheme,
                    onNotificationSettings = ::openNotificationSettings,
                )
                if (pendingVpnSwitch.value != null && vpnState.canStart && !resourcesBlocked) {
                    VpnSwitchDialog(
                        restricted = vpnAvailability.value == VpnAvailability.Restricted,
                        onSwitch = ::confirmVpnSwitch,
                        onVpnSettings = {
                            pendingVpnSwitch.value = null
                            openVpnSettings()
                        },
                        onDismiss = { pendingVpnSwitch.value = null },
                    )
                }
            }
        }
        if (savedInstanceState == null) handleRestartRequest(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PENDING_VPN_CONSENT, pendingConsentId)
        outState.putString("pending_transport_target", pendingTransportTarget)
        outState.putString("pending_transport_application", pendingTransportApplication)
        pendingVpnSwitch.value?.let { selection ->
            outState.putBundle(PENDING_VPN_SWITCH, Bundle().apply {
                putString(SWITCH_TARGET, selection.target)
                putString(SWITCH_APPLICATION, selection.application)
            })
        }
        outState.putString(PENDING_REPORT_ID, pendingReportId)
        outState.putString(PREVIOUS_PROCESS, VpnController.processIdentity)
        outState.putString(PENDING_DIAGNOSTIC_REQUEST, diagnosticRequest.value?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        VpnDiagnostics.prune()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { DiagnosticExports.pruneExpiredShares(applicationContext) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshVpnAvailability()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        diagnosticRequest.value = VpnDiagnostics.eventId(intent)
        handleRestartRequest(intent)
    }

    // Действие «Включить снова» из уведомления об отзыве VPN: обычный путь согласия, только по нажатию пользователя.
    private fun handleRestartRequest(intent: Intent) {
        if (intent.action != VpnController.ACTION_RESTART) return
        intent.setAction(null)
        requestVpnStart()
    }

    override fun onDestroy() {
        if (isFinishing) pendingConsentId?.let(VpnController::abandonConsent)
        super.onDestroy()
    }

    private fun refreshVpnAvailability(): VpnAvailability = VpnController.availability(this).also {
        vpnAvailability.value = it
    }

    private fun requestVpnStart(target: String? = null, application: String? = null) {
        if (!VpnController.state.value.canStart || VpnController.resourcesBlocked.value || pendingVpnSwitch.value != null) return
        when (refreshVpnAvailability()) {
            VpnAvailability.OtherVpn, VpnAvailability.Restricted -> pendingVpnSwitch.value = VpnSelection(target, application)
            else -> startVpn(target, application)
        }
    }

    private fun confirmVpnSwitch() {
        val selection = pendingVpnSwitch.value ?: return
        if (refreshVpnAvailability() == VpnAvailability.Restricted) return
        pendingVpnSwitch.value = null
        startVpn(selection.target, selection.application)
    }

    private fun startVpn(target: String?, application: String?) {
        val request = VpnController.requestStart(this, target, application)
        if (request != null) {
            pendingConsentId = request.id
            pendingTransportTarget = target
            pendingTransportApplication = application
            try {
                vpnConsent.launch(request.intent)
                VpnController.consentLaunched(request.id)
            } catch (exception: RuntimeException) {
                pendingConsentId = null
                pendingTransportTarget = null
                pendingTransportApplication = null
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
        requestSave(event.id.toString(), DiagnosticExports.fileName(event))
    }

    private fun saveCombinedReport(report: String) {
        if (pendingReportId != null) return
        pendingReportId = COMBINED_REPORT_PREFIX
        lifecycleScope.launch {
            val name = try {
                withContext(Dispatchers.IO) { DiagnosticExports.storePending(applicationContext, report) }
            } catch (error: CancellationException) {
                pendingReportId = null
                throw error
            } catch (error: Exception) {
                pendingReportId = null
                reportExportError(DiagnosticCode.SaveFailed, error)
                return@launch
            }
            pendingReportId = null
            if (!requestSave(COMBINED_REPORT_PREFIX + name, DiagnosticExports.COMBINED_FILE_NAME)) {
                withContext(Dispatchers.IO) { runCatching { DiagnosticExports.deletePending(applicationContext, name) } }
            }
        }
    }

    private fun requestSaveTransportJournal() {
        requestSave(TRANSPORT_REPORT_ID, DiagnosticExports.TRANSPORT_FILE_NAME)
    }

    private fun requestSave(id: String, fileName: String): Boolean {
        if (pendingReportId != null) return false
        pendingReportId = id
        return try {
            createDiagnosticDocument.launch(fileName)
            true
        } catch (exception: RuntimeException) {
            pendingReportId = null
            reportExportError(DiagnosticCode.SaveFailed, exception)
            false
        }
    }

    private fun shareDiagnostic(event: DiagnosticEvent) = shareReport(DiagnosticExports.fileName(event), event.report())

    private fun shareReport(fileName: String, report: String) =
        shareReport { DiagnosticExports.shareIntent(applicationContext, fileName, report) }

    private fun shareReport(buildSendIntent: suspend () -> Intent) {
        lifecycleScope.launch {
            try {
                val send = withContext(Dispatchers.IO) { buildSendIntent() }
                startActivity(DiagnosticExports.shareChooser(send, getString(R.string.diagnostic_share)))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportExportError(DiagnosticCode.ShareFailed, error)
            }
        }
    }

    private fun reportExportError(code: DiagnosticCode, error: Exception) {
        Toast.makeText(this, code.description, Toast.LENGTH_LONG).show()
        VpnDiagnostics.recordAsync(code, error)
    }

    private fun clearVpnEventHistory() {
        lifecycleScope.launch {
            if (!VpnEventHistory.clear()) {
                Toast.makeText(this@MainActivity, R.string.vpn_journal_clear_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildCombinedReport(): String {
        val info = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }.getOrNull()
        // Тот же источник, что у экрана «О приложении»: ресурсы, которые задаёт app/build.gradle.kts.
        val build = BuildIdentity(
            info?.longVersionCode,
            getString(R.string.build_number),
            getString(R.string.build_git_revision).takeUnless { it == "unknown" }?.take(12),
            getString(R.string.build_git_modified) == "true",
        )
        return combinedDiagnosticReport(
            info?.versionName, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MODEL,
            VpnController.state.value, VpnEventHistory.state.value, VpnDiagnostics.events.value,
            VpnController.transport.value,
            TransportDiagnostics.state.value,
            build,
        )
    }

    private fun requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !settings.getBoolean(NOTIFICATION_REQUESTED, false)
        ) {
            settings.edit().putBoolean(NOTIFICATION_REQUESTED, true).apply()
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .onFailure { VpnDiagnostics.recordAsync(DiagnosticCode.NotificationSettingsFailed, it) }
        }
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }.onFailure { VpnDiagnostics.recordAsync(DiagnosticCode.NotificationSettingsFailed, it) }
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
        private const val PENDING_VPN_SWITCH = "pending_vpn_switch"
        private const val SWITCH_TARGET = "target"
        private const val SWITCH_APPLICATION = "application"
        private const val NOTIFICATION_REQUESTED = "notification_requested"
        private const val PENDING_REPORT_ID = "pending_report"
        private const val COMBINED_REPORT_PREFIX = "combined:"
        private const val TRANSPORT_REPORT_ID = "transport"
        private const val PREVIOUS_PROCESS = "previous_process"
        private const val PENDING_DIAGNOSTIC_REQUEST = "pending_diagnostic_request"
    }

    private data class VpnSelection(val target: String?, val application: String?)
}
