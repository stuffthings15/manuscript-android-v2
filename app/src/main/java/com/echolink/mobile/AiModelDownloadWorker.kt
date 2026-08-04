package com.echolink.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val manager = AiModelManager.get(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val selection = selectionFromInput() ?: return@withContext Result.failure()
        if (selection.artifact.family != AiModelFamily.GEMMA) {
            return@withContext Result.failure()
        }
        if (!manager.hasDownloadConsent()) return@withContext Result.failure()
        manager.installedModel(selection)?.let {
            manager.onDownloadReady(selection, it)
            return@withContext Result.success()
        }
        if (!manager.isDownloadNetworkAllowed()) {
            manager.onDownloadWaiting(selection)
            return@withContext Result.retry()
        }
        manager.onDownloadWaiting(selection)
        setForeground(createForegroundInfo(0))
        var lastPublishedPercent = -1
        runCatching {
            manager.candidateModel(selection) ?: manager.stageDownload(
                selection = selection,
                stopped = { isStopped || !manager.isDownloadNetworkAllowed() },
                progress = { downloaded, total ->
                    val percent = if (total <= 0L) {
                        0
                    } else {
                        ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                    }
                    if (percent != lastPublishedPercent) {
                        lastPublishedPercent = percent
                        setProgressAsync(
                            workDataOf(
                                KEY_DOWNLOADED to downloaded,
                                KEY_TOTAL to total,
                                KEY_PERCENT to percent,
                            ),
                        )
                        setForegroundAsync(createForegroundInfo(percent))
                    }
                },
            )
        }.fold(
            onSuccess = { candidate ->
                setForeground(createForegroundInfo(100))
                val installed = manager.qualifyAndActivate(selection, candidate)
                if (installed == null) {
                    manager.onDownloadFailed(
                        selection,
                        IOException("AI failed its local self-test."),
                    )
                    manager.clearDownloadRegistration(selection)
                    Result.failure()
                } else {
                    manager.onDownloadReady(selection, installed)
                    Result.success()
                }
            },
            onFailure = { failure ->
                if (!manager.isDownloadNetworkAllowed()) {
                    manager.onDownloadWaiting(selection)
                    return@fold Result.retry()
                }
                manager.onDownloadFailed(selection, failure)
                val retry =
                    runAttemptCount < MAX_DOWNLOAD_ATTEMPTS &&
                        (failure is IOException || failure is InterruptedIOException)
                if (!retry) manager.clearDownloadRegistration(selection)
                if (retry) Result.retry() else Result.failure()
            },
        )
    }

    private fun selectionFromInput(): AiRuntimeSelection? {
        val tier = inputData.getString(KEY_ARTIFACT_TIER)
            ?.let { value -> AiModelTier.entries.firstOrNull { it.name == value } }
            ?: return null
        val backend = inputData.getString(KEY_BACKEND)
            ?.let { value -> BackendKind.entries.firstOrNull { it.name == value } }
            ?: return null
        val artifact = when (tier) {
            AiModelTier.FAST -> GemmaModelCatalog.fast
            AiModelTier.THOROUGH -> GemmaModelCatalog.thorough
            else -> return null
        }
        return AiRuntimeSelection(artifact, backend)
    }

    private fun createForegroundInfo(percent: Int): ForegroundInfo {
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
        val cancel = androidx.work.WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val networkLabel = if (
            manager.downloadNetworkPolicy() == AiDownloadNetworkPolicy.ALLOW_MOBILE_DATA
        ) {
            R.string.qwen_download_network_any
        } else {
            R.string.qwen_download_network_wifi
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_manuscript)
            .setContentTitle(
                applicationContext.getString(R.string.qwen_download_title, "AI"),
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

    companion object {
        const val KEY_ARTIFACT_TIER = "artifact_tier"
        const val KEY_BACKEND = "backend"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_PERCENT = "percent"
        private const val CHANNEL_ID = "manuscript_ai_download"
        private const val NOTIFICATION_ID = 4104
        private const val MAX_DOWNLOAD_ATTEMPTS = 5
    }
}
