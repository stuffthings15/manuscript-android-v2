package com.echolink.mobile

import java.util.concurrent.CancellationException

internal enum class WorkerSetupFailureResult {
    FAILURE,
    RETRY,
}

internal fun interface WorkerSetupFailureRecorder {
    fun failCurrent(
        attempt: DownloadAttempt,
        failureCode: DownloadFailureCode,
    ): Boolean
}

internal object WorkerSetupFailurePolicy {
    fun persistUnknown(
        attempt: DownloadAttempt,
        recorder: WorkerSetupFailureRecorder,
    ): WorkerSetupFailureResult = try {
        if (recorder.failCurrent(attempt, DownloadFailureCode.UNKNOWN)) {
            WorkerSetupFailureResult.FAILURE
        } else {
            WorkerSetupFailureResult.RETRY
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        WorkerSetupFailureResult.RETRY
    }
}
