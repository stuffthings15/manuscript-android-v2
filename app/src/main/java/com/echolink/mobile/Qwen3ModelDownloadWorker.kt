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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import java.io.IOException

class Qwen3ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val manager = Qwen3ModelManager.get(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tier = inputData.getString(KEY_TIER)
            ?.let { name -> Qwen3Tier.entries.firstOrNull { it.name == name } }
            ?: return@withContext Result.failure()
        if (!manager.hasDownloadConsent()) return@withContext Result.failure()
        manager.installedModel(tier)?.let { installed ->
            manager.onDownloadReady(tier, installed)
            return@withContext Result.success()
        }
        manager.downloadedModel(tier)?.let { downloaded ->
            setForeground(createForegroundInfo(tier, 100))
            return@withContext qualifyDownloadedModel(tier, downloaded)
        }
        manager.candidateModel(tier)?.let { candidate ->
            setForeground(createForegroundInfo(tier, 100))
            return@withContext qualifyDownloadedModel(tier, candidate)
        }

        manager.onDownloadWaiting(tier)
        setForeground(createForegroundInfo(tier, 0))
        var lastPublishedPercent = -1
        runCatching {
            manager.downloadTier(
                tier = tier,
                stopped = { isStopped },
                progress = { downloaded, total ->
                    val percent = if (total <= 0L) 0
                    else ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                    if (percent != lastPublishedPercent) {
                        lastPublishedPercent = percent
                        setProgressAsync(
                            workDataOf(
                                KEY_DOWNLOADED to downloaded,
                                KEY_TOTAL to total,
                                KEY_PERCENT to percent,
                            ),
                        )
                        setForegroundAsync(createForegroundInfo(tier, percent))
                    }
                },
            )
        }.fold(
            onSuccess = { file ->
                qualifyDownloadedModel(tier, file)
            },
            onFailure = { error ->
                val insufficientStorage =
                    manager.state is Qwen3ModelState.InsufficientStorage
                if (!insufficientStorage) manager.onDownloadFailed(tier, error)
                val retry =
                    !insufficientStorage &&
                        runAttemptCount < MAX_DOWNLOAD_ATTEMPTS &&
                        (error is InterruptedIOException || error is IOException)
                if (!retry) manager.clearDownloadRegistration(tier)
                if (retry) Result.retry() else Result.failure()
            },
        )
    }

    private fun qualifyDownloadedModel(tier: Qwen3Tier, file: java.io.File): Result {
        if (!Qwen3ModelSelfTester.verify(applicationContext, file)) {
            val error = IOException("AI failed its local self-test.")
            manager.onDownloadFailed(tier, error)
            return if (runAttemptCount < MAX_SELF_TEST_ATTEMPTS) {
                Result.retry()
            } else {
                manager.clearDownloadRegistration(tier)
                Result.failure()
            }
        }
        val active = if (file.name.endsWith(".candidate")) {
            manager.activateCandidate(tier, file)
        } else {
            file
        }
        manager.markSelfTested(tier)
        manager.onDownloadReady(tier, active)
        return Result.success()
    }

    private fun createForegroundInfo(tier: Qwen3Tier, percent: Int): ForegroundInfo {
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
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_manuscript)
            .setContentTitle(
                applicationContext.getString(R.string.qwen_download_title, tier.displayName),
            )
            .setContentText(
                applicationContext.getString(
                    R.string.qwen_download_progress,
                    percent,
                    applicationContext.getString(
                        if (
                            manager.downloadNetworkPolicy() ==
                            Qwen3DownloadNetworkPolicy.ALLOW_MOBILE_DATA
                        ) {
                            R.string.qwen_download_network_any
                        } else {
                            R.string.qwen_download_network_wifi
                        },
                    ),
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
        const val KEY_TIER = "tier"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_PERCENT = "percent"
        private const val CHANNEL_ID = "manuscript_qwen3_download"
        private const val NOTIFICATION_ID = 4103
        private const val MAX_DOWNLOAD_ATTEMPTS = 5
        private const val MAX_SELF_TEST_ATTEMPTS = 1
    }
}
