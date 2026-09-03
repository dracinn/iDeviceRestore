package com.idevicerestore.android

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Shared persistent status-bar surface for diagnostic, preparation, and restore phases. */
class OperationStatusNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init { createChannel() }

    fun requestPermissionIfPossible() {
        if (Build.VERSION.SDK_INT < 33 || notificationsAllowed()) return
        val activity = context as? Activity ?: return
        activity.runOnUiThread {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
        }
    }

    fun phase(title: String, detail: String, progress: Int? = null, ongoing: Boolean = true) {
        if (!notificationsAllowed()) return
        val builder = baseBuilder(title, detail)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(ongoing)
            .setAutoCancel(false)

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
            baseBuilder(title, detail)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setAutoCancel(false)
                .setProgress(100, 100, false)
                .build()
        )
    }

    fun failed(title: String, detail: String) {
        if (!notificationsAllowed()) return
        manager.notify(
            NOTIFICATION_ID,
            baseBuilder(title, detail)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setOngoing(true)
                .setAutoCancel(false)
                .setProgress(0, 0, false)
                .build()
        )
    }

    fun clear() { manager.cancel(NOTIFICATION_ID) }

    fun notificationsAllowed(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun baseBuilder(title: String, detail: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, 4109, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Restore progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Persistent iDeviceRestore preparation and restore progress"
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "restore_progress"
        private const val NOTIFICATION_ID = 4109
        private const val REQUEST_NOTIFICATIONS = 4110
    }
}
