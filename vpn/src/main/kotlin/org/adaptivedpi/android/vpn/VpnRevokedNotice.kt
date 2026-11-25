package org.adaptivedpi.android.vpn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Уведомление после `onRevoke`. Причину отзыва Android не сообщает: так выглядят и включение другого VPN,
 * и отключение в системных настройках, поэтому текст нейтральный. VPN обратно автоматически не включается.
 */
internal object VpnRevokedNotice {
    private const val CHANNEL_ID = "vpn_revoked"
    private const val NOTIFICATION_ID = 3

    fun show(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.vpn_revoked_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            val restart = PendingIntent.getActivity(
                context, 0,
                Intent(launch).setAction(VpnController.ACTION_RESTART)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val message = context.getString(R.string.vpn_revoked_message)
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_vpn_notification)
                    .setContentTitle(context.getString(R.string.vpn_revoked_title))
                    .setContentText(message)
                    .setStyle(Notification.BigTextStyle().bigText(message))
                    .setContentIntent(restart)
                    .addAction(Notification.Action.Builder(null, context.getString(R.string.vpn_revoked_restart), restart).build())
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .build(),
            )
        }
    }

    /** Новый сеанс делает уведомление устаревшим. */
    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }
}
