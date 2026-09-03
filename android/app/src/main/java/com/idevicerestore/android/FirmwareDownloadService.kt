package com.idevicerestore.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong

class FirmwareDownloadService : Service() {
    private var handle: FirmwareDownloader.DownloadHandle? = null
    private val lastUiUpdateMs = AtomicLong(0L)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                handle?.cancel()
                broadcastState(STATE_CANCELLED, message = "Firmware download cancelled")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startDownload(intent)
        }
        return START_NOT_STICKY
    }

    private fun startDownload(intent: Intent) {
        if (handle != null) {
            broadcastState(STATE_RUNNING, message = "Firmware download already active")
            return
        }

        val url = intent.getStringExtra(EXTRA_URL) ?: return fail("Missing firmware URL")
        val destinationPath = intent.getStringExtra(EXTRA_DESTINATION) ?: return fail("Missing firmware destination")
        val expectedSize = intent.getLongExtra(EXTRA_EXPECTED_SIZE, -1L)
        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        val buildId = intent.getStringExtra(EXTRA_BUILD_ID).orEmpty()
        val destination = File(destinationPath)

        if (!url.startsWith("https://updates.cdn-apple.com/")) {
            return fail("Firmware payload host is not Apple's CDN")
        }

        if (destination.isFile && (expectedSize <= 0L || destination.length() == expectedSize)) {
            broadcastState(
                STATE_READY,
                downloaded = destination.length(),
                total = expectedSize,
                message = "Firmware already present and ready"
            )
            stopSelf()
            return
        }

        startAsForeground(version, buildId, 0, expectedSize)
        broadcastState(STATE_RUNNING, total = expectedSize, message = "Starting Apple CDN download")

        val downloader = FirmwareDownloader(logger = { message ->
            broadcastState(STATE_LOG, message = message)
        })
        val request = FirmwareDownloader.Request(
            url = url,
            destination = destination,
            expectedSize = expectedSize,
            expectedSha1 = intent.getStringExtra(EXTRA_SHA1),
            connections = 1
        )

        val active = downloader.start(request) { progress ->
            val now = System.currentTimeMillis()
            if (now - lastUiUpdateMs.get() >= 500L && lastUiUpdateMs.getAndSet(now) <= now) {
                updateNotification(version, buildId, progress.downloadedBytes, progress.totalBytes)
                broadcastState(
                    STATE_RUNNING,
                    downloaded = progress.downloadedBytes,
                    total = progress.totalBytes,
                    bytesPerSecond = progress.bytesPerSecond,
                    message = "Downloading from Apple CDN"
                )
            }
        }
        handle = active

        Thread {
            try {
                val result = active.await()
                broadcastState(
                    STATE_READY,
                    downloaded = result.bytes,
                    total = result.bytes,
                    message = "Firmware download complete and ready"
                )
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("iDeviceRestore firmware ready")
                    .setContentText("$version ($buildId)")
                    .setAutoCancel(true)
                    .build()
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            } catch (t: Throwable) {
                val cause = if (t is ExecutionException) t.cause ?: t else t
                if (active.isCancelled() || cause is InterruptedException) {
                    broadcastState(STATE_CANCELLED, message = "Firmware download cancelled")
                } else {
                    broadcastState(STATE_FAILED, message = "${cause.javaClass.simpleName}: ${cause.message}")
                }
            } finally {
                handle = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    private fun startAsForeground(version: String, buildId: String, downloaded: Long, total: Long) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(version, buildId, downloaded, total),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    private fun updateNotification(version: String, buildId: String, downloaded: Long, total: Long) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(version, buildId, downloaded, total))
    }

    private fun buildNotification(version: String, buildId: String, downloaded: Long, total: Long) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading Apple firmware")
            .setContentText("$version ($buildId) — ${formatBytes(downloaded)} / ${formatBytes(total)}")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(1000, if (total > 0) ((downloaded * 1000L) / total).toInt().coerceIn(0, 1000) else 0, total <= 0)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, FirmwareDownloadService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun fail(message: String) {
        broadcastState(STATE_FAILED, message = message)
        stopSelf()
    }

    private fun broadcastState(
        state: String,
        downloaded: Long = 0L,
        total: Long = -1L,
        bytesPerSecond: Long = 0L,
        message: String = ""
    ) {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_DOWNLOADED, downloaded)
                .putExtra(EXTRA_TOTAL, total)
                .putExtra(EXTRA_BYTES_PER_SECOND, bytesPerSecond)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Firmware downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Apple firmware download progress"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handle?.cancel()
        broadcastState(STATE_FAILED, message = "Android foreground data-sync time limit reached; download can be resumed")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.idevicerestore.android.action.DOWNLOAD_FIRMWARE"
        const val ACTION_CANCEL = "com.idevicerestore.android.action.CANCEL_FIRMWARE_DOWNLOAD"
        const val ACTION_STATE = "com.idevicerestore.android.action.FIRMWARE_DOWNLOAD_STATE"

        const val EXTRA_URL = "url"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_EXPECTED_SIZE = "expected_size"
        const val EXTRA_SHA1 = "sha1"
        const val EXTRA_VERSION = "version"
        const val EXTRA_BUILD_ID = "build_id"
        const val EXTRA_STATE = "state"
        const val EXTRA_DOWNLOADED = "downloaded"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_BYTES_PER_SECOND = "bytes_per_second"
        const val EXTRA_MESSAGE = "message"

        const val STATE_RUNNING = "running"
        const val STATE_READY = "ready"
        const val STATE_FAILED = "failed"
        const val STATE_CANCELLED = "cancelled"
        const val STATE_LOG = "log"

        private const val CHANNEL_ID = "firmware_downloads"
        private const val NOTIFICATION_ID = 4107

        fun formatBytes(value: Long): String {
            if (value < 0) return "unknown"
            val gib = value / (1024.0 * 1024.0 * 1024.0)
            return if (gib >= 1.0) "%.2f GiB".format(gib) else "%.1f MiB".format(value / (1024.0 * 1024.0))
        }
    }
}
