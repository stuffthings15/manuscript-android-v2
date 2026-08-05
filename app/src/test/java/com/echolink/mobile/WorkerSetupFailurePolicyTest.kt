package com.echolink.mobile

import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class WorkerSetupFailurePolicyTest {
    private val attempt = DownloadAttempt(
        target = DownloadTargetCatalog.targetFor(
            AiRuntimeSelection(GemmaModelCatalog.fast, BackendKind.LITERT_CPU),
        ),
        generation = DownloadGeneration(8L),
        workIdentity = DownloadWorkIdentity.from(
            UUID.fromString("20000000-0000-0000-0000-000000000008"),
        ),
    )

    @Test
    fun persistedUnknownFailureEndsTheWorker() {
        var recordedAttempt: DownloadAttempt? = null
        var recordedCode: DownloadFailureCode? = null

        val result = WorkerSetupFailurePolicy.persistUnknown(
            attempt,
            WorkerSetupFailureRecorder { exactAttempt, failureCode ->
                recordedAttempt = exactAttempt
                recordedCode = failureCode
                true
            },
        )

        assertEquals(WorkerSetupFailureResult.FAILURE, result)
        assertEquals(attempt, recordedAttempt)
        assertEquals(DownloadFailureCode.UNKNOWN, recordedCode)
    }

    @Test
    fun rejectedFailurePersistenceRetriesTheWorker() {
        val result = WorkerSetupFailurePolicy.persistUnknown(
            attempt,
            WorkerSetupFailureRecorder { _, _ -> false },
        )

        assertEquals(WorkerSetupFailureResult.RETRY, result)
    }

    @Test
    fun thrownFailurePersistenceRetriesTheWorker() {
        val result = WorkerSetupFailurePolicy.persistUnknown(
            attempt,
            WorkerSetupFailureRecorder { _, _ -> throw IOException("storage unavailable") },
        )

        assertEquals(WorkerSetupFailureResult.RETRY, result)
    }

    @Test
    fun cancellationDuringFailurePersistencePropagates() {
        val cancellation = CancellationException("stopped")

        try {
            WorkerSetupFailurePolicy.persistUnknown(
                attempt,
                WorkerSetupFailureRecorder { _, _ -> throw cancellation },
            )
            fail("Cancellation must propagate.")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
