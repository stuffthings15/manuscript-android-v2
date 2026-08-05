package com.echolink.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val progressUpdateThrottle = WorkerProgressUpdateThrottle()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val attempt = DownloadWorkerAttemptDecoder.decodeGemma(inputData, id)
            ?: return@withContext Result.failure()
        var initializedServices: AndroidDownloadServices? = null
        try {
            val services = AndroidDownloadServices.get(applicationContext)
            initializedServices = services
            if (!services.isCurrent(attempt)) return@withContext Result.success()
            val manager = AiModelManager.get(applicationContext)
            val selection = DownloadTargetCatalog.selectionFor(attempt.target)
            services.networkDecision(attempt.target)
            val stopped = { isStopped || !services.isCurrent(attempt) }
            val operations = GemmaDownloadModelOperations(
                manager = manager,
                selection = selection,
                progressObserver = { downloaded, total ->
                    publishWorkerProgress(downloaded, total)
                },
            )
            val serviceOperations = NotifyingExactDownloadServiceOperations(
                AndroidExactDownloadServiceOperations(services),
            ) {
                refreshForeground(attempt, services)
            }
            val port = ExactManagerDownloadAttemptPort(
                exactAttempt = attempt,
                modelOperations = operations,
                services = serviceOperations,
                stopped = stopped,
            )
            setForeground(requireNotNull(createForegroundInfo(attempt, services)))

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
                        val currentServices =
                            services ?: AndroidDownloadServices.get(applicationContext)
                        currentServices.failCurrent(exactAttempt, failureCode).also { persisted ->
                            if (persisted) refreshForeground(exactAttempt, currentServices)
                        }
                    },
                )
            ) {
                WorkerSetupFailureResult.FAILURE -> Result.failure()
                WorkerSetupFailureResult.RETRY -> Result.retry()
            }
        }
    }

    private fun publishWorkerProgress(
        downloaded: Long,
        total: Long,
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
    }

    private fun createForegroundInfo(
        attempt: DownloadAttempt,
        services: AndroidDownloadServices,
    ): ForegroundInfo? {
        val snapshot = services.uiSnapshot(attempt.target) ?: return null
        val state = DownloadWorkerNotificationState.from(snapshot, attempt) ?: return null
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
        val notification = DownloadForegroundNotificationFactory.build(
            context = applicationContext,
            channelId = CHANNEL_ID,
            smallIcon = R.drawable.ic_manuscript,
            presentation = state.presentation,
            actionIntents = DownloadNotificationActionIntents(
                retry = DownloadRetryReceiver.pendingIntent(applicationContext, attempt),
                cancel = DownloadCancelReceiver.pendingIntent(applicationContext, attempt),
            )
        )
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

    private fun refreshForeground(
        attempt: DownloadAttempt,
        services: AndroidDownloadServices,
    ) {
        createForegroundInfo(attempt, services)?.let(::setForegroundAsync)
    }

    private companion object {
        const val PROGRESS_DOWNLOADED = "manuscript.download.progress.bytes.v1"
        const val PROGRESS_TOTAL = "manuscript.download.progress.total.v1"
        const val PROGRESS_PERCENT = "manuscript.download.progress.percent.v1"
        const val CHANNEL_ID = "manuscript_ai_download"
        const val NOTIFICATION_ID = 4104
    }
}
