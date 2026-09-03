package com.idevicerestore.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Shared low-priority status-bar surface for long-running diagnostic and restore phases.
 *
 * Downloads keep their own byte-progress foreground notification. This class covers work that is
 * naturally phase-based (catalog lookup, IPSW inspection, TSS preparation, restore sequencing) and
 * can also display a determinate percentage when a caller has one.
 */
class OperationStatusNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        createChannel()
    }

    fun phase(title: String, detail: String, progress: Int? = null, ongoing: Boolean = true) {
        if (!notificationsAllowed()) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setContentIntent(openAppIntent())

        when {
            progress == null -> builder.setProgress(0, 0, true)
            progress in 0..100 -> builder.setProgress(100, progress, false)
            else -> builder.setProgress(0, 0, false)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    fun complete(title: String, detail: String) {
        if (!notificationsAllowed()) return
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setOngoing(false)
                .setProgress(0, 0, false)
                .setContentIntent(openAppIntent())
                .build()
        )
    }

    fun failed(title: String, detail: String) {
        if (!notificationsAllowed()) return
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setOngoing(false)
                .setProgress(0, 0, false)
                .setContentIntent(openAppIntent())
                .build()
        )
    }

    fun clear() {
        manager.cancel(NOTIFICATION_ID)
    }

    fun notificationsAllowed(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            4109,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Restore progress",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "iDeviceRestore catalog, firmware inspection, and restore progress"
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "restore_progress"
        private const val NOTIFICATION_ID = 4109
    }
}
