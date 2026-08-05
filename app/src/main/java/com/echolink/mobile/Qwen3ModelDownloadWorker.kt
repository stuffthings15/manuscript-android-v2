package com.echolink.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Qwen3ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val progressUpdateThrottle = WorkerProgressUpdateThrottle()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val attempt = DownloadWorkerAttemptDecoder.decodeLegacy(inputData, id)
            ?: return@withContext Result.failure()
        var initializedServices: AndroidDownloadServices? = null
        try {
            val services = AndroidDownloadServices.get(applicationContext)
            initializedServices = services
            if (!services.isCurrent(attempt)) return@withContext Result.success()
            val manager = Qwen3ModelManager.get(applicationContext)
            val tier = DownloadTargetCatalog.qwenTierFor(attempt.target)
            val allowMobileData =
                manager.downloadNetworkPolicy() == Qwen3DownloadNetworkPolicy.ALLOW_MOBILE_DATA
            services.networkDecision(attempt.target)
            val stopped = { isStopped || !services.isCurrent(attempt) }
            val operations = QwenDownloadModelOperations(
                manager = manager,
                tier = tier,
                progressObserver = { downloaded, total ->
                    publishWorkerProgress(attempt, tier, downloaded, total, allowMobileData)
                },
            )
            val port = ExactManagerDownloadAttemptPort(
                exactAttempt = attempt,
                modelOperations = operations,
                services = AndroidExactDownloadServiceOperations(services),
                stopped = stopped,
            )
            val restoredPercent = services.restoreTelemetry(attempt.target)?.percent ?: 0
            setForeground(createForegroundInfo(attempt, tier, restoredPercent, allowMobileData))

            when (ExactDownloadAttemptExecutor(port).execute(attempt) { isStopped }) {
                DownloadAttemptExecutionResult.SUCCESS -> Result.success()
                DownloadAttemptExecutionResult.RETRY -> Result.retry()
                DownloadAttemptExecutionResult.FAILURE -> Result.failure()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val services = initializedServices
            when (
                WorkerSetupFailurePolicy.persistUnknown(
                    attempt,
                    WorkerSetupFailureRecorder { exactAttempt, failureCode ->
                        (services ?: AndroidDownloadServices.get(applicationContext))
                            .failCurrent(exactAttempt, failureCode)
                    },
                )
            ) {
                WorkerSetupFailureResult.FAILURE -> Result.failure()
                WorkerSetupFailureResult.RETRY -> Result.retry()
            }
        }
    }

    private fun publishWorkerProgress(
        attempt: DownloadAttempt,
        tier: Qwen3Tier,
        downloaded: Long,
        total: Long,
        allowMobileData: Boolean,
    ) {
        val update = progressUpdateThrottle.next(
            downloadedBytes = downloaded,
            totalBytes = total,
            nowMillis = SystemClock.elapsedRealtime(),
        ) ?: return
        setProgressAsync(
            workDataOf(
                PROGRESS_DOWNLOADED to update.downloadedBytes,
                PROGRESS_TOTAL to update.totalBytes,
                PROGRESS_PERCENT to update.percent,
            ),
        )
        setForegroundAsync(
            createForegroundInfo(attempt, tier, update.percent, allowMobileData),
        )
    }

    private fun createForegroundInfo(
        attempt: DownloadAttempt,
        tier: Qwen3Tier,
        percent: Int,
        allowMobileData: Boolean,
    ): ForegroundInfo {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.qwen_download_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val cancel = DownloadCancelReceiver.pendingIntent(applicationContext, attempt)
        val networkLabel = if (allowMobileData) {
            R.string.qwen_download_network_any
        } else {
            R.string.qwen_download_network_wifi
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_manuscript)
            .setContentTitle(
                applicationContext.getString(R.string.qwen_download_title, tier.displayName),
            )
            .setContentText(
                applicationContext.getString(
                    R.string.qwen_download_progress,
                    percent,
                    applicationContext.getString(networkLabel),
                ),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, percent == 0)
            .addAction(0, applicationContext.getString(R.string.qwen_download_cancel), cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val PROGRESS_DOWNLOADED = "manuscript.download.progress.bytes.v1"
        const val PROGRESS_TOTAL = "manuscript.download.progress.total.v1"
        const val PROGRESS_PERCENT = "manuscript.download.progress.percent.v1"
        const val CHANNEL_ID = "manuscript_qwen3_download"
        const val NOTIFICATION_ID = 4103
    }
}
