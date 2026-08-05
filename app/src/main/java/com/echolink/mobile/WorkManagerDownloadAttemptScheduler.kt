package com.echolink.mobile

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

internal interface DownloadWorkManagerGateway {
    fun enqueueUniqueWork(
        uniqueName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )

    fun cancelWorkById(id: UUID)

    fun cancelUniqueWork(uniqueName: String)
}

internal class AndroidDownloadWorkManagerGateway(
    private val workManager: WorkManager,
) : DownloadWorkManagerGateway {
    override fun enqueueUniqueWork(
        uniqueName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        workManager.enqueueUniqueWork(uniqueName, existingWorkPolicy, request)
    }

    override fun cancelWorkById(id: UUID) {
        workManager.cancelWorkById(id)
    }

    override fun cancelUniqueWork(uniqueName: String) {
        workManager.cancelUniqueWork(uniqueName)
    }
}

internal class WorkManagerDownloadAttemptScheduler(
    private val gateway: DownloadWorkManagerGateway,
    private val localWorkAvailable: (DownloadTargetKey) -> Boolean = { false },
) : DownloadAttemptScheduler {
    private val migratedLegacyNames = mutableSetOf<String>()
    private val migrationLock = Any()

    override fun ensureScheduled(attempt: DownloadAttempt) {
        val request = when (attempt.target.family) {
            AiModelFamily.GEMMA -> buildRequest<AiModelDownloadWorker>(attempt)
            AiModelFamily.LEGACY_QWEN -> buildRequest<Qwen3ModelDownloadWorker>(attempt)
        }
        gateway.enqueueUniqueWork(
            uniqueName = uniqueName(attempt.workIdentity),
            existingWorkPolicy = ExistingWorkPolicy.KEEP,
            request = request,
        )
    }

    override fun cancel(workIdentity: DownloadWorkIdentity) {
        gateway.cancelWorkById(workIdentity.toUuid())
    }

    fun migrateLegacyUniqueWorkOnce() {
        synchronized(migrationLock) {
            LEGACY_UNIQUE_WORK_NAMES.forEach { uniqueName ->
                if (uniqueName !in migratedLegacyNames) {
                    gateway.cancelUniqueWork(uniqueName)
                    migratedLegacyNames += uniqueName
                }
            }
        }
    }

    private inline fun <reified Worker : ListenableWorker> buildRequest(
        attempt: DownloadAttempt,
    ): OneTimeWorkRequest {
        val localOnly = localWorkAvailable(attempt.target)
        return OneTimeWorkRequestBuilder<Worker>()
        .setId(attempt.workIdentity.toUuid())
        .setInputData(DownloadWorkContract.encode(attempt))
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(
                    if (localOnly) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED,
                )
                .setRequiresStorageNotLow(!localOnly)
                .build(),
        )
        .setBackoffCriteria(
            BackoffPolicy.LINEAR,
            BACKOFF_SECONDS,
            TimeUnit.SECONDS,
        )
        .build()
    }

    private fun uniqueName(identity: DownloadWorkIdentity): String =
        "$UNIQUE_WORK_PREFIX${identity.toUuid()}"

    private companion object {
        const val UNIQUE_WORK_PREFIX = "manuscript.download.attempt.v1."
        const val BACKOFF_SECONDS = 10L

        val LEGACY_UNIQUE_WORK_NAMES = listOf(
            "manuscript-ai-model-download-v3",
            "manuscript-ai-model-download",
            "manuscript-qwen3-model-download",
        )
    }
}
