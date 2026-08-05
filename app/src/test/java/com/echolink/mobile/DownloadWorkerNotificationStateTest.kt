package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadWorkerNotificationStateTest {
    @Test
    fun exactWorkerStateComesFromTheDurableSnapshotPresentation() {
        val attempt = attempt()
        val telemetry = DownloadTelemetryCalculator(clock = { 1_000L }).record(
            DownloadObservation(
                generation = attempt.generation,
                phase = DownloadPhase.DOWNLOADING,
                downloadedBytes = 500L,
                totalBytes = 1_000L,
                network = DownloadNetwork.MOBILE,
                cancellationAllowed = true,
            ),
        )
        val snapshot = DownloadUiSnapshot(
            target = attempt.target,
            telemetry = telemetry,
            actionableAttempt = attempt,
        )

        val state = DownloadWorkerNotificationState.from(snapshot, attempt)

        assertEquals(DownloadTelemetryPresentation.from(telemetry), state?.presentation)
        assertEquals(attempt, state?.attempt)
    }

    @Test
    fun staleGenerationCannotRenderOrReceiveNotificationActions() {
        val current = attempt()
        val stale = current.copy(generation = DownloadGeneration(8L))
        val telemetry = DownloadTelemetryCalculator(clock = { 1_000L }).record(
            DownloadObservation(
                generation = stale.generation,
                phase = DownloadPhase.FAILED,
                downloadedBytes = 500L,
                totalBytes = 1_000L,
                network = DownloadNetwork.OFFLINE,
                failureCode = DownloadFailureCode.DOWNLOAD_INTERRUPTED,
                cancellationAllowed = false,
                retryAllowed = true,
            ),
        )

        val state = DownloadWorkerNotificationState.from(
            DownloadUiSnapshot(current.target, telemetry, stale),
            current,
        )

        assertEquals(null, state)
    }

    @Test
    fun differentTargetCannotRenderOrReceiveNotificationActions() {
        val current = attempt()
        val otherTarget = current.target.copy(tier = AiModelTier.THOROUGH)
        val telemetry = DownloadTelemetryCalculator(clock = { 1_000L }).record(
            DownloadObservation(
                generation = current.generation,
                phase = DownloadPhase.FAILED,
                downloadedBytes = 500L,
                totalBytes = 1_000L,
                network = DownloadNetwork.OFFLINE,
                failureCode = DownloadFailureCode.DOWNLOAD_INTERRUPTED,
                cancellationAllowed = false,
                retryAllowed = true,
            ),
        )

        val state = DownloadWorkerNotificationState.from(
            DownloadUiSnapshot(otherTarget, telemetry, current.copy(target = otherTarget)),
            current,
        )

        assertEquals(null, state)
    }

    @Test
    fun differentActionableAttemptCannotReceiveNotificationActions() {
        val current = attempt()
        val otherAttempt = current.copy(workIdentity = DownloadWorkIdentity(21L, 22L))
        val telemetry = DownloadTelemetryCalculator(clock = { 1_000L }).record(
            DownloadObservation(
                generation = current.generation,
                phase = DownloadPhase.FAILED,
                downloadedBytes = 500L,
                totalBytes = 1_000L,
                network = DownloadNetwork.OFFLINE,
                failureCode = DownloadFailureCode.DOWNLOAD_INTERRUPTED,
                cancellationAllowed = false,
                retryAllowed = true,
            ),
        )

        val state = DownloadWorkerNotificationState.from(
            DownloadUiSnapshot(current.target, telemetry, otherAttempt),
            current,
        )

        assertEquals(null, state)
    }

    private fun attempt(): DownloadAttempt = DownloadAttempt(
        target = DownloadTargetKey(
            AiModelFamily.GEMMA,
            AiModelTier.FAST,
            BackendKind.LITERT_CPU,
        ),
        generation = DownloadGeneration(7L),
        workIdentity = DownloadWorkIdentity(11L, 12L),
    )
}
