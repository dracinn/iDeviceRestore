package io.github.dracinn.idevicerestore.download

import android.content.Context
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters

internal class FirmwareDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val request = try {
            DownloadCodec.fromWorkData(inputData)
        } catch (e: RuntimeException) {
            return Result.failure(errorData(e.message ?: "Invalid firmware download request"))
        }

        val initial = FirmwareDownloadProgress(
            requestId = request.id,
            phase = FirmwareDownloadPhase.CONNECTING,
            totalBytes = request.expectedBytes,
        )
        setForegroundAsync(DownloadNotifications.foregroundInfo(applicationContext, initial)).get()
        FirmwareDownloadEvents.emit(applicationContext, initial)

        return try {
            val outcome = FirmwareDownloadEngine(applicationContext).execute(
                request = request,
                isCancelled = { isStopped },
                onProgress = { progress ->
                    FirmwareDownloadEvents.emit(applicationContext, progress)
                    setProgressAsync(progress.toWorkData())
                    setForegroundAsync(DownloadNotifications.foregroundInfo(applicationContext, progress))
                },
            )
            val complete = FirmwareDownloadProgress(
                requestId = request.id,
                phase = FirmwareDownloadPhase.COMPLETE,
                bytesDownloaded = outcome.file.length(),
                totalBytes = outcome.file.length(),
                message = "Firmware is ready for inspection",
                completedPath = outcome.file.absolutePath,
                computedDigestHex = outcome.digestHex,
            )
            FirmwareDownloadEvents.emit(applicationContext, complete)
            Result.success(complete.toWorkData())
        } catch (e: FirmwareDownloadEngine.CancelledException) {
            FirmwareDownloadEvents.emit(
                applicationContext,
                FirmwareDownloadProgress(
                    requestId = request.id,
                    phase = FirmwareDownloadPhase.CANCELLED,
                    message = e.message,
                )
            )
            Result.failure(errorData(e.message ?: "Download cancelled"))
        } catch (e: FirmwareDownloadEngine.TransferException) {
            val failed = FirmwareDownloadProgress(
                requestId = request.id,
                phase = FirmwareDownloadPhase.FAILED,
                message = e.message,
            )
            FirmwareDownloadEvents.emit(applicationContext, failed)
            if (e.retryable && runAttemptCount < MAX_AUTOMATIC_RETRIES) {
                Result.retry()
            } else {
                Result.failure(errorData(e.message ?: "Firmware download failed"))
            }
        } catch (e: Exception) {
            FirmwareDownloadEvents.emit(
                applicationContext,
                FirmwareDownloadProgress(
                    requestId = request.id,
                    phase = FirmwareDownloadPhase.FAILED,
                    message = e.message ?: e.javaClass.simpleName,
                )
            )
            Result.failure(errorData(e.message ?: "Firmware download failed"))
        }
    }

    private fun FirmwareDownloadProgress.toWorkData(): Data = Data.Builder()
        .putString(FirmwareDownloadEvents.EXTRA_REQUEST_ID, requestId)
        .putString(FirmwareDownloadEvents.EXTRA_PHASE, phase.name)
        .putLong(FirmwareDownloadEvents.EXTRA_BYTES_DOWNLOADED, bytesDownloaded)
        .putLong(
            FirmwareDownloadEvents.EXTRA_TOTAL_BYTES,
            totalBytes ?: FirmwareDownloadEvents.UNKNOWN_TOTAL_BYTES,
        )
        .putString(FirmwareDownloadEvents.EXTRA_MESSAGE, message)
        .putString(FirmwareDownloadEvents.EXTRA_COMPLETED_PATH, completedPath)
        .putString(FirmwareDownloadEvents.EXTRA_DIGEST, computedDigestHex)
        .build()

    private fun errorData(message: String): Data = Data.Builder()
        .putString(FirmwareDownloadEvents.EXTRA_MESSAGE, message)
        .build()

    companion object {
        private const val MAX_AUTOMATIC_RETRIES = 3
    }
}
