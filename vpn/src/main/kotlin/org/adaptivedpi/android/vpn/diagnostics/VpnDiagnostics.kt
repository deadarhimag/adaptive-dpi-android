package org.adaptivedpi.android.vpn.diagnostics

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.adaptivedpi.android.vpn.R
import org.adaptivedpi.android.vpn.VpnController
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess

object VpnDiagnostics {
    const val CHANNEL_ID = "vpn_critical_errors"
    private const val ACTION_DETAILS = "org.adaptivedpi.android.DIAGNOSTIC_DETAILS"
    private const val EXTRA_EVENT_ID = "org.adaptivedpi.android.DIAGNOSTIC_EVENT_ID"
    private const val NOTIFICATION_ID = 2

    private val empty = MutableStateFlow<List<DiagnosticEvent>>(emptyList()).asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var center: DiagnosticCenter? = null
    @Volatile private var activeVpn: Pair<UUID, VpnSnapshot>? = null
    private var notifications: CriticalNotifications? = null

    val events get() = center?.events ?: empty

    @Synchronized
    fun install(context: Context) {
        if (center != null) return
        val app = context.applicationContext
        val dispatcher = CriticalNotifications(app)
        val version = runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull()
        val initialized = DiagnosticCenter(
            DiagnosticJournal(File(app.noBackupFilesDir, "diagnostics")),
            version,
            Build.VERSION.SDK_INT,
            Build.VERSION.RELEASE,
            dispatcher::post,
        )
        notifications = dispatcher
        center = initialized
        initialized.recovered.forEach { (event, sessionId) ->
            val type = if (event.code == DiagnosticCode.AppCrash) VpnEventType.ProcessCrash else VpnEventType.InterruptedSession
            VpnEventHistory.record(type, sessionId, event.code, event.vpn.state, id = event.id)
        }
        runCatching(dispatcher::ensureChannel)
        installCrashHandler(initialized)
        initialized.events.value.firstOrNull {
            it.requiresNotification && !it.acknowledged && !it.notified
        }?.let { initialized.notifyOnce(it.id) }
    }

    fun record(code: DiagnosticCode, error: Throwable? = null, vpn: VpnSnapshot = snapshot()) {
        runCatching { center?.record(code, vpn, error) }
    }

    internal fun recordCleanupAsync(code: DiagnosticCode, vpn: VpnSnapshot, details: VpnCleanupDetails, error: Throwable? = null) {
        io.launch { runCatching { center?.record(code, vpn, error, details) } }
    }

    fun prune() {
        io.launch { runCatching { center?.prune() } }
    }

    fun acknowledge(id: UUID) {
        runCatching { center?.acknowledge(id) }
        runCatching { notifications?.cancel(id) }
    }

    internal fun markActive(vpn: VpnSnapshot, sessionId: UUID?): UUID {
        val id = sessionId ?: UUID.randomUUID()
        activeVpn = id to vpn
        io.launch { center?.markActive(vpn, id) }
        return id
    }

    internal fun endActive(id: UUID, keepMarker: Boolean = false) {
        if (activeVpn?.first == id) activeVpn = null
        if (!keepMarker) io.launch { center?.clearActive(id) }
    }

    internal fun persistCritical(
        code: DiagnosticCode,
        error: Throwable?,
        vpn: VpnSnapshot,
        activeId: UUID?,
        afterJournalAttempt: () -> Unit = {},
    ) {
        io.launch {
            val recorder = center
            val id = try {
                runCatching { recorder?.persistCritical(code, vpn, error, activeId) }.getOrNull()
            } finally {
                runCatching(afterJournalAttempt)
            }
            if (id != null) runCatching { recorder?.notifyOnce(id) }
        }
    }

    fun eventId(intent: Intent?): UUID? = runCatching {
        if (intent?.action != ACTION_DETAILS) return@runCatching null
        intent.getStringExtra(EXTRA_EVENT_ID)?.let(UUID::fromString)
    }.getOrNull()

    private fun snapshot(active: Pair<UUID, VpnSnapshot>? = activeVpn): VpnSnapshot {
        val state = VpnController.state.value
        return active?.second?.copy(state = state) ?: VpnSnapshot(state)
    }

    private fun installCrashHandler(center: DiagnosticCenter) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                runCatching {
                    val active = activeVpn
                    center.captureCrash(snapshot(active), error, active?.first)
                }
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }

    private class CriticalNotifications(private val context: Context) {
        private val manager get() = context.getSystemService(NotificationManager::class.java)

        fun ensureChannel() {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.vpn_error_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }

        fun post(event: DiagnosticEvent): Boolean {
            val manager = manager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            if (!manager.areNotificationsEnabled()) return false
            ensureChannel()
            if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false

            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
            intent.setAction(ACTION_DETAILS)
                .setData(Uri.fromParts("adaptive-dpi-report", event.id.toString(), null))
                .putExtra(EXTRA_EVENT_ID, event.id.toString())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val details = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            val title = when {
                event.code == DiagnosticCode.VpnInterrupted -> event.code.description
                event.vpnStopped -> context.getString(R.string.vpn_error_title)
                else -> event.code.description
            }
            val message = if (event.vpnStopped) {
                "${context.getString(R.string.vpn_error_stopped)} ${event.connectionNotice()}"
            } else {
                context.getString(R.string.vpn_error_report_available)
            }
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(details)
                .addAction(Notification.Action.Builder(null, context.getString(R.string.vpn_error_details), details).build())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build()
            manager.notify(event.id.toString(), NOTIFICATION_ID, notification)
            return true
        }

        fun cancel(id: UUID) {
            manager?.cancel(id.toString(), NOTIFICATION_ID)
        }
    }
}
