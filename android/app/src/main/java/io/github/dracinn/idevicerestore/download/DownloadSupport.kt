package io.github.dracinn.idevicerestore.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.work.Data
import androidx.work.ForegroundInfo
import io.github.dracinn.idevicerestore.MainActivity

internal object DownloadCodec {
    private const val KEY_ID = "id"
    private const val KEY_URL = "url"
    private const val KEY_FILE_NAME = "fileName"
    private const val KEY_EXPECTED_BYTES = "expectedBytes"
    private const val KEY_DIGEST_ALGORITHM = "digestAlgorithm"
    private const val KEY_DIGEST_HEX = "digestHex"
    private const val KEY_ALLOW_METERED = "allowMetered"
    private const val NO_EXPECTED_BYTES = -1L

    fun toWorkData(request: FirmwareDownloadRequest): Data = Data.Builder()
        .putString(KEY_ID, request.id)
        .putString(KEY_URL, request.url)
        .putString(KEY_FILE_NAME, request.fileName)
        .putLong(KEY_EXPECTED_BYTES, request.expectedBytes ?: NO_EXPECTED_BYTES)
        .putString(KEY_DIGEST_ALGORITHM, request.expectedDigestAlgorithm)
        .putString(KEY_DIGEST_HEX, request.expectedDigestHex)
        .putBoolean(KEY_ALLOW_METERED, request.allowMetered)
        .build()

    fun fromWorkData(data: Data): FirmwareDownloadRequest = FirmwareDownloadRequest(
        id = requireNotNull(data.getString(KEY_ID)) { "Missing download id" },
        url = requireNotNull(data.getString(KEY_URL)) { "Missing firmware URL" },
        fileName = requireNotNull(data.getString(KEY_FILE_NAME)) { "Missing firmware file name" },
        expectedBytes = data.getLong(KEY_EXPECTED_BYTES, NO_EXPECTED_BYTES).takeIf { it > 0L },
        expectedDigestAlgorithm = data.getString(KEY_DIGEST_ALGORITHM),
        expectedDigestHex = data.getString(KEY_DIGEST_HEX),
        allowMetered = data.getBoolean(KEY_ALLOW_METERED, true),
    )

    fun toPersistableBundle(request: FirmwareDownloadRequest): PersistableBundle = PersistableBundle().apply {
        putString(KEY_ID, request.id)
        putString(KEY_URL, request.url)
        putString(KEY_FILE_NAME, request.fileName)
        putLong(KEY_EXPECTED_BYTES, request.expectedBytes ?: NO_EXPECTED_BYTES)
        request.expectedDigestAlgorithm?.let { putString(KEY_DIGEST_ALGORITHM, it) }
        request.expectedDigestHex?.let { putString(KEY_DIGEST_HEX, it) }
        putBoolean(KEY_ALLOW_METERED, request.allowMetered)
    }

    fun fromPersistableBundle(bundle: PersistableBundle): FirmwareDownloadRequest = FirmwareDownloadRequest(
        id = requireNotNull(bundle.getString(KEY_ID)) { "Missing download id" },
        url = requireNotNull(bundle.getString(KEY_URL)) { "Missing firmware URL" },
        fileName = requireNotNull(bundle.getString(KEY_FILE_NAME)) { "Missing firmware file name" },
        expectedBytes = bundle.getLong(KEY_EXPECTED_BYTES, NO_EXPECTED_BYTES).takeIf { it > 0L },
        expectedDigestAlgorithm = bundle.getString(KEY_DIGEST_ALGORITHM),
        expectedDigestHex = bundle.getString(KEY_DIGEST_HEX),
        allowMetered = bundle.getBoolean(KEY_ALLOW_METERED, true),
    )
}

object FirmwareDownloadEvents {
    const val ACTION_PROGRESS = "io.github.dracinn.idevicerestore.DOWNLOAD_PROGRESS"
    const val EXTRA_REQUEST_ID = "requestId"
    const val EXTRA_PHASE = "phase"
    const val EXTRA_BYTES_DOWNLOADED = "bytesDownloaded"
    const val EXTRA_TOTAL_BYTES = "totalBytes"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_COMPLETED_PATH = "completedPath"
    const val EXTRA_DIGEST = "digest"
    const val UNKNOWN_TOTAL_BYTES = -1L

    internal fun emit(context: Context, progress: FirmwareDownloadProgress) {
        context.sendBroadcast(
            Intent(ACTION_PROGRESS)
                .setPackage(context.packageName)
                .putExtra(EXTRA_REQUEST_ID, progress.requestId)
                .putExtra(EXTRA_PHASE, progress.phase.name)
                .putExtra(EXTRA_BYTES_DOWNLOADED, progress.bytesDownloaded)
                .putExtra(EXTRA_TOTAL_BYTES, progress.totalBytes ?: UNKNOWN_TOTAL_BYTES)
                .putExtra(EXTRA_MESSAGE, progress.message)
                .putExtra(EXTRA_COMPLETED_PATH, progress.completedPath)
                .putExtra(EXTRA_DIGEST, progress.computedDigestHex)
        )
    }
}

internal object DownloadNotifications {
    private const val CHANNEL_ID = "firmware_downloads"
    private const val CHANNEL_NAME = "Firmware downloads"
    private const val BASE_NOTIFICATION_ID = 4_200

    fun foregroundInfo(context: Context, progress: FirmwareDownloadProgress): ForegroundInfo =
        ForegroundInfo(notificationId(progress.requestId), notification(context, progress))

    fun notification(context: Context, progress: FirmwareDownloadProgress): Notification {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val total = progress.totalBytes
        val percent = if (total != null && total > 0L) {
            ((progress.bytesDownloaded * 100L) / total).coerceIn(0L, 100L).toInt()
        } else {
            null
        }
        val title = when (progress.phase) {
            FirmwareDownloadPhase.VERIFYING -> "Verifying firmware"
            FirmwareDownloadPhase.COMPLETE -> "Firmware ready"
            FirmwareDownloadPhase.FAILED -> "Firmware download failed"
            FirmwareDownloadPhase.CANCELLED -> "Firmware download cancelled"
            else -> "Downloading firmware"
        }
        val text = progress.message ?: when {
            percent != null -> "$percent%"
            progress.phase == FirmwareDownloadPhase.CONNECTING -> "Connecting…"
            else -> "Transfer in progress"
        }
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(
                if (progress.phase == FirmwareDownloadPhase.COMPLETE) {
                    android.R.drawable.stat_sys_download_done
                } else {
                    android.R.drawable.stat_sys_download
                }
            )
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(
                progress.phase in setOf(
                    FirmwareDownloadPhase.QUEUED,
                    FirmwareDownloadPhase.CONNECTING,
                    FirmwareDownloadPhase.DOWNLOADING,
                    FirmwareDownloadPhase.VERIFYING,
                )
            )
            .apply {
                when {
                    percent != null && progress.phase == FirmwareDownloadPhase.DOWNLOADING -> {
                        setProgress(100, percent, false)
                    }
                    progress.phase in setOf(
                        FirmwareDownloadPhase.CONNECTING,
                        FirmwareDownloadPhase.DOWNLOADING,
                    ) -> {
                        setProgress(0, 0, true)
                    }
                }
            }
            .build()
    }

    fun notificationId(requestId: String): Int =
        BASE_NOTIFICATION_ID + ((requestId.hashCode() and 0x7fffffff) % 1_000)

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
