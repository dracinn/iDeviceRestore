package io.github.dracinn.idevicerestore.download

import android.annotation.TargetApi
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class FirmwareDownloadJobService : JobService() {
    private val cancelled = ConcurrentHashMap<Int, AtomicBoolean>()

    override fun onStartJob(params: JobParameters): Boolean {
        val request = try {
            DownloadCodec.fromPersistableBundle(params.extras)
        } catch (_: RuntimeException) {
            jobFinished(params, false)
            return false
        }

        val cancelFlag = AtomicBoolean(false)
        cancelled[params.jobId] = cancelFlag

        val initial = FirmwareDownloadProgress(
            requestId = request.id,
            phase = FirmwareDownloadPhase.CONNECTING,
            totalBytes = request.expectedBytes,
        )
        publish(params, initial)

        Thread({
            try {
                val outcome = FirmwareDownloadEngine(applicationContext).execute(
                    request = request,
                    isCancelled = { cancelFlag.get() || Thread.currentThread().isInterrupted },
                    onProgress = { progress -> publish(params, progress) },
                )
                publish(
                    params,
                    FirmwareDownloadProgress(
                        requestId = request.id,
                        phase = FirmwareDownloadPhase.COMPLETE,
                        bytesDownloaded = outcome.file.length(),
                        totalBytes = outcome.file.length(),
                        message = "Firmware is ready for inspection",
                        completedPath = outcome.file.absolutePath,
                        computedDigestHex = outcome.digestHex,
                    )
                )
            } catch (e: FirmwareDownloadEngine.CancelledException) {
                publish(
                    params,
                    FirmwareDownloadProgress(
                        requestId = request.id,
                        phase = FirmwareDownloadPhase.CANCELLED,
                        message = e.message,
                    )
                )
            } catch (e: Exception) {
                publish(
                    params,
                    FirmwareDownloadProgress(
                        requestId = request.id,
                        phase = FirmwareDownloadPhase.FAILED,
                        message = e.message ?: e.javaClass.simpleName,
                    )
                )
            } finally {
                cancelled.remove(params.jobId)
                jobFinished(params, false)
            }
        }, "firmware-download-${params.jobId}").start()

        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancelled.remove(params.jobId)?.set(true)
        // A stopped transfer leaves its .part file intact. The next explicit
        // user request can resume it; UIDT jobs are not silently rescheduled.
        return false
    }

    private fun publish(params: JobParameters, progress: FirmwareDownloadProgress) {
        FirmwareDownloadEvents.emit(applicationContext, progress)
        setNotification(
            params,
            DownloadNotifications.notificationId(progress.requestId),
            DownloadNotifications.notification(applicationContext, progress),
            JobService.JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
    }
}
