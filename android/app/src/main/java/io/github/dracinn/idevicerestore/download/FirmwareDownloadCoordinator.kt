package io.github.dracinn.idevicerestore.download

import android.annotation.TargetApi
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object FirmwareDownloadCoordinator {
    data class Handle(
        val requestId: String,
        val backend: Backend,
    )

    enum class Backend {
        USER_INITIATED_DATA_TRANSFER,
        WORK_MANAGER,
    }

    fun enqueue(context: Context, request: FirmwareDownloadRequest): Handle {
        FirmwareDownloadEvents.emit(
            context,
            FirmwareDownloadProgress(
                requestId = request.id,
                phase = FirmwareDownloadPhase.QUEUED,
                totalBytes = request.expectedBytes,
                message = "Firmware download queued",
            )
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            enqueueUserInitiatedJob(context, request)
            Handle(request.id, Backend.USER_INITIATED_DATA_TRANSFER)
        } else {
            enqueueWorkManager(context, request)
            Handle(request.id, Backend.WORK_MANAGER)
        }
    }

    fun cancel(context: Context, handle: Handle) {
        when (handle.backend) {
            Backend.USER_INITIATED_DATA_TRANSFER -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    context.getSystemService(JobScheduler::class.java).cancel(jobId(handle.requestId))
                }
            }
            Backend.WORK_MANAGER -> {
                WorkManager.getInstance(context).cancelUniqueWork(workName(handle.requestId))
            }
        }
        FirmwareDownloadEvents.emit(
            context,
            FirmwareDownloadProgress(
                requestId = handle.requestId,
                phase = FirmwareDownloadPhase.CANCELLED,
                message = "Firmware download cancelled",
            )
        )
    }

    private fun enqueueWorkManager(context: Context, request: FirmwareDownloadRequest) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (request.allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresStorageNotLow(true)
            .build()
        val work = OneTimeWorkRequest.Builder(FirmwareDownloadWorker::class.java)
            .setInputData(DownloadCodec.toWorkData(request))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(request.id)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(request.id),
            ExistingWorkPolicy.KEEP,
            work,
        )
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun enqueueUserInitiatedJob(context: Context, request: FirmwareDownloadRequest) {
        val builder = JobInfo.Builder(
            jobId(request.id),
            ComponentName(context, FirmwareDownloadJobService::class.java),
        )
            .setUserInitiated(true)
            .setRequiredNetworkType(
                if (request.allowMetered) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED
            )
            .setRequiresStorageNotLow(true)
            .setExtras(DownloadCodec.toPersistableBundle(request))

        request.expectedBytes?.let { builder.setEstimatedNetworkBytes(it, 0L) }

        val result = context.getSystemService(JobScheduler::class.java).schedule(builder.build())
        check(result == JobScheduler.RESULT_SUCCESS) { "Android rejected the firmware download job" }
    }

    private fun workName(requestId: String): String = "firmware-download:$requestId"

    private fun jobId(requestId: String): Int =
        JOB_ID_NAMESPACE or (requestId.hashCode() and JOB_ID_MASK)

    private const val JOB_ID_NAMESPACE = 0x20000000
    private const val JOB_ID_MASK = 0x1fffffff
}
