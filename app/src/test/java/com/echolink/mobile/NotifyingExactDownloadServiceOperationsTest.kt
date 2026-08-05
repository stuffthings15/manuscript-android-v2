package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifyingExactDownloadServiceOperationsTest {
    @Test
    fun persistedPhaseChangeRefreshesTheDurableForegroundOnce() {
        val delegate = FakeOperations(DownloadPublishResult.Persisted)
        var refreshes = 0
        val notifying = NotifyingExactDownloadServiceOperations(delegate) {
            refreshes += 1
        }

        val result = notifying.publish(
            attempt = attempt,
            observation = observation,
            finalByteCount = false,
        )

        assertEquals(DownloadPublishResult.Persisted, result)
        assertEquals(1, refreshes)
    }

    @Test
    fun successfulReadyCommitRefreshesAfterTheReadyActionOnce() {
        val events = mutableListOf<String>()
        val delegate = FakeOperations(
            publishResult = DownloadPublishResult.Persisted,
            commitResult = true,
        )
        val notifying = NotifyingExactDownloadServiceOperations(delegate) {
            events += "refresh"
        }

        val committed = notifying.commitReadyIfCurrent(attempt, observation) {
            events += "ready"
        }

        assertEquals(true, committed)
        assertEquals(listOf("ready", "refresh"), events)
    }

    private class FakeOperations(
        private val publishResult: DownloadPublishResult,
        private val commitResult: Boolean = false,
    ) : ExactDownloadServiceOperations {
        override fun isCurrent(attempt: DownloadAttempt): Boolean = true
        override fun restoreTelemetry(target: DownloadTargetKey): DownloadTelemetry? = null
        override fun networkDecision(target: DownloadTargetKey): DownloadNetworkDecision =
            error("Not used")
        override fun publish(
            attempt: DownloadAttempt,
            observation: DownloadObservation,
            finalByteCount: Boolean,
        ): DownloadPublishResult = publishResult
        override fun commitReadyIfCurrent(
            attempt: DownloadAttempt,
            readyObservation: DownloadObservation,
            action: () -> Unit,
        ): Boolean {
            if (commitResult) action()
            return commitResult
        }
    }

    private val target = DownloadTargetKey(
        AiModelFamily.GEMMA,
        AiModelTier.FAST,
        BackendKind.LITERT_CPU,
    )
    private val attempt = DownloadAttempt(
        target,
        DownloadGeneration(7L),
        DownloadWorkIdentity(11L, 12L),
    )
    private val observation = DownloadObservation(
        generation = attempt.generation,
        phase = DownloadPhase.DOWNLOADING,
        downloadedBytes = 500L,
        totalBytes = 1_000L,
        network = DownloadNetwork.WIFI,
    )
}
