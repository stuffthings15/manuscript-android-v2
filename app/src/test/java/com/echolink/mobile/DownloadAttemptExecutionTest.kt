package com.echolink.mobile

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAttemptExecutionTest {
    @Test
    fun staleAttemptAtStartHasZeroModelCallbacks() {
        val port = FakePort().apply { current = false }

        val result = executor(port).execute(attempt)

        assertEquals(DownloadAttemptExecutionResult.SUCCESS, result)
        assertTrue(port.modelEvents.isEmpty())
        assertTrue(port.publications.isEmpty())
    }

    @Test
    fun everyTerminalAttemptExitsWithoutTouchingTheModel() {
        listOf(
            DownloadPhase.READY,
            DownloadPhase.PAUSED,
            DownloadPhase.FAILED,
        ).forEach { terminalPhase ->
            val port = FakePort().apply { phase = terminalPhase }

            assertEquals(
                terminalPhase.name,
                DownloadAttemptExecutionResult.SUCCESS,
                executor(port).execute(attempt),
            )
            assertTrue(terminalPhase.name, port.modelEvents.isEmpty())
            assertTrue(terminalPhase.name, port.publications.isEmpty())
        }
    }

    @Test
    fun initialWifiMobileAndOfflineDecisionsAreExact() {
        val wifi = FakePort().apply {
            networkDecision = allowed(DownloadNetwork.WIFI)
        }
        assertEquals(
            DownloadAttemptExecutionResult.SUCCESS,
            executor(wifi).execute(attempt),
        )
        assertFalse(wifi.publications.any { it.observation.phase == DownloadPhase.WAITING })

        val allowedMobile = FakePort().apply {
            networkDecision = allowed(DownloadNetwork.MOBILE)
        }
        assertEquals(
            DownloadAttemptExecutionResult.SUCCESS,
            executor(allowedMobile).execute(attempt),
        )
        assertFalse(
            allowedMobile.publications.any { it.observation.phase == DownloadPhase.WAITING },
        )

        val blockedMobile = FakePort().apply {
            networkDecision = waiting(
                DownloadNetwork.MOBILE,
                DownloadBlockReason.WIFI_REQUIRED,
            )
        }
        assertEquals(
            DownloadAttemptExecutionResult.RETRY,
            executor(blockedMobile).execute(attempt),
        )
        blockedMobile.assertWaiting(DownloadNetwork.MOBILE, DownloadBlockReason.WIFI_REQUIRED)

        val offline = FakePort().apply {
            networkDecision = waiting(
                DownloadNetwork.OFFLINE,
                DownloadBlockReason.NETWORK_UNAVAILABLE,
            )
        }
        assertEquals(
            DownloadAttemptExecutionResult.RETRY,
            executor(offline).execute(attempt),
        )
        offline.assertWaiting(DownloadNetwork.OFFLINE, DownloadBlockReason.NETWORK_UNAVAILABLE)
    }

    @Test
    fun midStreamNetworkLossPersistsWaitingAndRequestsRetry() {
        val port = FakePort()
        port.downloadScript = { stopped, progress ->
            progress(100L, port.canonicalTotalBytes)
            port.networkDecision = waiting(
                DownloadNetwork.OFFLINE,
                DownloadBlockReason.NETWORK_UNAVAILABLE,
            )
            assertTrue(stopped())
            throw InterruptedIOException("network disappeared")
        }

        val result = executor(port).execute(attempt)

        assertEquals(DownloadAttemptExecutionResult.RETRY, result)
        port.assertWaiting(DownloadNetwork.OFFLINE, DownloadBlockReason.NETWORK_UNAVAILABLE)
        assertEquals(1, port.waitingCallbacks)
        assertEquals(0, port.failedCallbacks.size)
    }

    @Test
    fun throttledProgressDoesNotStopMandatoryFinalBytesAndVerifying() {
        val port = FakePort()
        port.publishResult = { observation, finalByteCount ->
            if (
                observation.phase == DownloadPhase.DOWNLOADING &&
                observation.downloadedBytes == 101L &&
                !finalByteCount
            ) {
                DownloadPublishResult.Throttled
            } else {
                DownloadPublishResult.Persisted
            }
        }
        port.downloadScript = { _, progress ->
            progress(100L, port.canonicalTotalBytes)
            progress(101L, port.canonicalTotalBytes)
        }

        assertEquals(
            DownloadAttemptExecutionResult.SUCCESS,
            executor(port).execute(attempt),
        )

        assertTrue(
            port.publications.any {
                it.observation.downloadedBytes == 101L &&
                    it.result == DownloadPublishResult.Throttled
            },
        )
        val finalBytes = port.publications.single {
            it.observation.phase == DownloadPhase.DOWNLOADING && it.finalByteCount
        }
        assertEquals(port.canonicalTotalBytes, finalBytes.observation.downloadedBytes)
        assertEquals(DownloadPublishResult.Persisted, finalBytes.result)
        assertTrue(
            port.publications.any {
                it.observation.phase == DownloadPhase.VERIFYING &&
                    it.observation.downloadedBytes == port.canonicalTotalBytes
            },
        )
    }

    @Test
    fun durableProgressAheadOfPartialPublishesExactZeroBeforeDownloadingAgain() {
        var rewindAccepted = false
        val port = FakePort().apply {
            phase = DownloadPhase.DOWNLOADING
            downloadedBytes = 600L
            partialBytesOnDisk = 200L
            publishResult = { observation, _ ->
                when {
                    observation.phase == DownloadPhase.DOWNLOADING &&
                        observation.downloadedBytes == 0L -> {
                        rewindAccepted = true
                        DownloadPublishResult.Persisted
                    }
                    observation.phase == DownloadPhase.DOWNLOADING &&
                        observation.downloadedBytes < 600L &&
                        !rewindAccepted -> DownloadPublishResult.Rejected
                    else -> DownloadPublishResult.Persisted
                }
            }
            downloadScript = { _, progress ->
                progress(200L, canonicalTotalBytes)
            }
        }

        val result = executor(port).execute(attempt)

        assertEquals(DownloadAttemptExecutionResult.SUCCESS, result)
        assertTrue(rewindAccepted)
        assertEquals(
            0L,
            port.publications.first { it.observation.phase == DownloadPhase.DOWNLOADING }
                .observation.downloadedBytes,
        )
        assertEquals(1, port.downloadCalls)
        assertEquals(DownloadPhase.READY, port.phase)
    }

    @Test
    fun candidateQualifiesOutsideCommitAndActivatesExactlyOnceInsideCommit() {
        val port = FakePort().apply {
            availability = ExactDownloadModelAvailability.CANDIDATE
            downloadedBytes = canonicalTotalBytes
        }

        assertEquals(
            DownloadAttemptExecutionResult.SUCCESS,
            executor(port).execute(attempt),
        )

        assertEquals(1, port.qualificationCalls)
        assertTrue(port.qualifiedOutsideCommit)
        assertEquals(1, port.readyCommitCalls)
        assertEquals(1, port.activationCalls)
        assertTrue(port.activatedInsideCommit)
        assertEquals(1, port.readyCallbacks)
        assertEquals(DownloadPhase.READY, port.phase)
    }

    @Test
    fun exactInstalledModelUsesNoOpReadyCommit() {
        val port = FakePort().apply {
            availability = ExactDownloadModelAvailability.INSTALLED
            downloadedBytes = canonicalTotalBytes
        }

        assertEquals(
            DownloadAttemptExecutionResult.SUCCESS,
            executor(port).execute(attempt),
        )

        assertEquals(1, port.readyCommitCalls)
        assertEquals(1, port.readyCommitActionCalls)
        assertEquals(0, port.qualificationCalls)
        assertEquals(0, port.activationCalls)
        assertEquals(0, port.downloadCalls)
        assertEquals(1, port.readyCallbacks)
    }

    @Test
    fun waitingCandidateReturnsToPreparingBeforeVerifying() {
        val port = FakePort().apply {
            phase = DownloadPhase.WAITING
            availability = ExactDownloadModelAvailability.CANDIDATE
            downloadedBytes = canonicalTotalBytes
        }

        assertEquals(
            DownloadAttemptExecutionResult.SUCCESS,
            executor(port).execute(attempt),
        )

        val phases = port.publications.map { it.observation.phase }
        assertTrue(phases.indexOf(DownloadPhase.PREPARING) >= 0)
        assertTrue(phases.indexOf(DownloadPhase.VERIFYING) > phases.indexOf(DownloadPhase.PREPARING))
        assertEquals(DownloadPhase.READY, port.phase)
    }

    @Test
    fun cancelDuringProgressCannotWriteFailedOrActivate() {
        val port = FakePort()
        port.downloadScript = { stopped, progress ->
            progress(100L, port.canonicalTotalBytes)
            port.cancelDurably()
            assertTrue(stopped())
            throw InterruptedIOException("cancelled attempt")
        }

        assertEquals(
            DownloadAttemptExecutionResult.SUCCESS,
            executor(port).execute(attempt),
        )

        assertEquals(DownloadPhase.PAUSED, port.phase)
        assertFalse(port.publications.any { it.observation.phase == DownloadPhase.FAILED })
        assertEquals(0, port.failedCallbacks.size)
        assertEquals(0, port.activationCalls)
    }

    @Test
    fun cancelDuringSelfTestCannotWriteFailedOrActivate() {
        val port = FakePort().apply {
            availability = ExactDownloadModelAvailability.CANDIDATE
            downloadedBytes = canonicalTotalBytes
            qualificationScript = {
                cancelDurably()
                FakeQualification
            }
        }

        assertEquals(
            DownloadAttemptExecutionResult.SUCCESS,
            executor(port).execute(attempt),
        )

        assertTrue(port.qualifiedOutsideCommit)
        assertEquals(0, port.activationCalls)
        assertEquals(0, port.readyCallbacks)
        assertEquals(0, port.failedCallbacks.size)
        assertFalse(port.publications.any { it.observation.phase == DownloadPhase.FAILED })
        assertEquals(DownloadPhase.PAUSED, port.phase)
    }

    @Test
    fun systemStopPropagatesCancellationExceptionWithoutFailureMutation() {
        val port = FakePort()
        var systemStopped = false
        port.downloadScript = { stopped, progress ->
            progress(100L, port.canonicalTotalBytes)
            systemStopped = true
            assertTrue(stopped())
            throw CancellationException("WorkManager stopped")
        }

        assertThrows(CancellationException::class.java) {
            executor(port).execute(attempt) { systemStopped }
        }

        assertEquals(0, port.failedCallbacks.size)
        assertFalse(port.publications.any { it.observation.phase == DownloadPhase.FAILED })
        assertEquals(0, port.activationCalls)
    }

    @Test
    fun systemStopDuringQualificationConvertsInterruptionToCancellationWithoutFailure() {
        val port = FakePort().apply {
            availability = ExactDownloadModelAvailability.CANDIDATE
            downloadedBytes = canonicalTotalBytes
        }
        var systemStopped = false
        port.qualificationScript = {
            systemStopped = true
            throw InterruptedIOException("self-test interrupted")
        }

        assertThrows(CancellationException::class.java) {
            executor(port).execute(attempt) { systemStopped }
        }

        assertEquals(DownloadPhase.VERIFYING, port.phase)
        assertFalse(port.publications.any { it.observation.phase == DownloadPhase.FAILED })
        assertEquals(0, port.failedCallbacks.size)
        assertEquals(0, port.activationCalls)
    }

    @Test
    fun systemStopAfterSuccessfulQualificationStillPreventsReadyCommitAndActivation() {
        val port = FakePort().apply {
            availability = ExactDownloadModelAvailability.CANDIDATE
            downloadedBytes = canonicalTotalBytes
        }
        var systemStopped = false
        port.qualificationScript = {
            systemStopped = true
            FakeQualification
        }

        assertThrows(CancellationException::class.java) {
            executor(port).execute(attempt) { systemStopped }
        }

        assertEquals(DownloadPhase.VERIFYING, port.phase)
        assertEquals(0, port.readyCommitCalls)
        assertEquals(0, port.activationCalls)
        assertEquals(0, port.readyCallbacks)
        assertEquals(0, port.failedCallbacks.size)
        assertFalse(port.publications.any { it.observation.phase == DownloadPhase.FAILED })
    }

    @Test
    fun preActionReadyFailureRetriesButActivationFailureSealsMappedFailure() {
        val persistence = FakePort().apply {
            availability = ExactDownloadModelAvailability.CANDIDATE
            downloadedBytes = canonicalTotalBytes
            readyCommitBehavior = ReadyCommitBehavior.THROW_BEFORE_ACTION
        }

        assertEquals(
            DownloadAttemptExecutionResult.RETRY,
            executor(persistence).execute(attempt),
        )
        assertEquals(DownloadPhase.VERIFYING, persistence.phase)
        assertEquals(0, persistence.readyCommitActionCalls)
        assertEquals(0, persistence.activationCalls)
        assertFalse(
            persistence.publications.any { it.observation.phase == DownloadPhase.FAILED },
        )

        val activation = FakePort().apply {
            availability = ExactDownloadModelAvailability.CANDIDATE
            downloadedBytes = canonicalTotalBytes
            activationFailure = ExactDownloadFailure(
                DownloadFailureCode.INTEGRITY_CHECK_FAILED,
            )
        }
        assertEquals(
            DownloadAttemptExecutionResult.FAILURE,
            executor(activation).execute(attempt),
        )
        assertEquals(1, activation.readyCommitActionCalls)
        assertEquals(1, activation.activationCalls)
        assertEquals(DownloadPhase.FAILED, activation.phase)
        assertEquals(
            listOf(DownloadFailureCode.INTEGRITY_CHECK_FAILED),
            activation.failedCallbacks,
        )
    }

    @Test
    fun exactFailureMatrixPersistsSealedFailedAndNeverAutomaticallyRetries() {
        val cases = listOf(
            FailureCase(
                "storage",
                DownloadFailureCode.STORAGE_UNAVAILABLE,
            ) { port ->
                port.downloadFailure = ExactDownloadFailure(
                    DownloadFailureCode.STORAGE_UNAVAILABLE,
                )
            },
            FailureCase(
                "integrity",
                DownloadFailureCode.INTEGRITY_CHECK_FAILED,
            ) { port ->
                port.availability = ExactDownloadModelAvailability.CANDIDATE
                port.downloadedBytes = port.canonicalTotalBytes
                port.qualificationFailure = AiModelIntegrityException("checksum mismatch")
            },
            FailureCase(
                "self-test",
                DownloadFailureCode.SELF_TEST_FAILED,
            ) { port ->
                port.availability = ExactDownloadModelAvailability.CANDIDATE
                port.downloadedBytes = port.canonicalTotalBytes
                port.qualificationScript = { null }
            },
            FailureCase(
                "interrupted",
                DownloadFailureCode.DOWNLOAD_INTERRUPTED,
            ) { port ->
                port.downloadFailure = InterruptedIOException("short response")
            },
            FailureCase(
                "unknown",
                DownloadFailureCode.UNKNOWN,
            ) { port ->
                port.downloadFailure = IllegalStateException("unexpected")
            },
        )

        cases.forEach { case ->
            val port = FakePort()
            case.configure(port)

            assertEquals(
                case.name,
                DownloadAttemptExecutionResult.FAILURE,
                executor(port).execute(attempt),
            )
            assertEquals(case.name, DownloadPhase.FAILED, port.phase)
            assertEquals(case.name, listOf(case.expectedCode), port.failedCallbacks)
            assertEquals(
                case.name,
                case.expectedCode,
                port.publications.last().observation.failureCode,
            )

            val modelEventsAfterFailure = port.modelEvents.size
            assertEquals(
                case.name,
                DownloadAttemptExecutionResult.SUCCESS,
                executor(port).execute(attempt),
            )
            assertEquals(case.name, modelEventsAfterFailure, port.modelEvents.size)
        }
    }

    @Test
    fun readyWriteFailureAfterActivationRecoversWithoutReactivation() {
        val port = FakePort().apply {
            availability = ExactDownloadModelAvailability.CANDIDATE
            downloadedBytes = canonicalTotalBytes
            readyCommitBehavior = ReadyCommitBehavior.THROW_AND_RECOVER_READY
        }

        assertEquals(
            DownloadAttemptExecutionResult.SUCCESS,
            executor(port).execute(attempt),
        )
        assertEquals(1, port.activationCalls)
        assertEquals(DownloadPhase.READY, port.phase)
        assertEquals(1, port.readyCallbacks)

        assertEquals(
            DownloadAttemptExecutionResult.SUCCESS,
            executor(port).execute(attempt),
        )
        assertEquals(1, port.activationCalls)
    }

    @Test
    fun unavailableReadyRecoveryReturnsRetryAndLeavesVerifying() {
        val port = FakePort().apply {
            availability = ExactDownloadModelAvailability.CANDIDATE
            downloadedBytes = canonicalTotalBytes
            readyCommitBehavior = ReadyCommitBehavior.THROW_LEAVE_VERIFYING
        }

        assertEquals(
            DownloadAttemptExecutionResult.RETRY,
            executor(port).execute(attempt),
        )
        assertEquals(1, port.activationCalls)
        assertEquals(DownloadPhase.VERIFYING, port.phase)
        assertEquals(0, port.readyCallbacks)

        port.recoverReadyOnNextRestore = true
        assertEquals(
            DownloadAttemptExecutionResult.SUCCESS,
            executor(port).execute(attempt),
        )
        assertEquals(1, port.activationCalls)
        assertEquals(DownloadPhase.READY, port.phase)
        assertEquals(1, port.readyCallbacks)
    }

    @Test
    fun managerCallbacksWaitForTheMatchingDurablePhase() {
        val waiting = FakePort().apply {
            networkDecision = waiting(
                DownloadNetwork.OFFLINE,
                DownloadBlockReason.NETWORK_UNAVAILABLE,
            )
            publishResult = { observation, _ ->
                if (observation.phase == DownloadPhase.WAITING) {
                    DownloadPublishResult.Rejected
                } else {
                    DownloadPublishResult.Persisted
                }
            }
        }
        assertEquals(
            DownloadAttemptExecutionResult.RETRY,
            executor(waiting).execute(attempt),
        )
        assertEquals(0, waiting.waitingCallbacks)

        val failed = FakePort().apply {
            downloadFailure = IllegalStateException("failure")
            publishResult = { observation, _ ->
                if (observation.phase == DownloadPhase.FAILED) {
                    DownloadPublishResult.Rejected
                } else {
                    DownloadPublishResult.Persisted
                }
            }
        }
        assertEquals(
            DownloadAttemptExecutionResult.RETRY,
            executor(failed).execute(attempt),
        )
        assertEquals(0, failed.failedCallbacks.size)

        val ready = FakePort().apply {
            availability = ExactDownloadModelAvailability.CANDIDATE
            downloadedBytes = canonicalTotalBytes
            readyCommitBehavior = ReadyCommitBehavior.RETURN_FALSE_CURRENT
        }
        assertEquals(
            DownloadAttemptExecutionResult.RETRY,
            executor(ready).execute(attempt),
        )
        assertEquals(0, ready.readyCallbacks)
        assertEquals(0, ready.activationCalls)
    }

    private fun executor(port: FakePort): ExactDownloadAttemptExecutor =
        ExactDownloadAttemptExecutor(port)

    private data class FailureCase(
        val name: String,
        val expectedCode: DownloadFailureCode,
        val configure: (FakePort) -> Unit,
    )

    private data class Publication(
        val observation: DownloadObservation,
        val finalByteCount: Boolean,
        val result: DownloadPublishResult,
    )

    private enum class ReadyCommitBehavior {
        PERSIST_READY,
        RETURN_FALSE_CURRENT,
        THROW_BEFORE_ACTION,
        THROW_AND_RECOVER_READY,
        THROW_LEAVE_VERIFYING,
    }

    private object FakeQualification : ExactDownloadQualification

    private class FakePort : ExactDownloadAttemptPort {
        private val totalBytes = 1_000L

        var current = true
        var phase = DownloadPhase.PREPARING
        var downloadedBytes = 0L
        var partialBytesOnDisk: Long? = null
        var networkDecision = allowed(DownloadNetwork.WIFI)
        var availability = ExactDownloadModelAvailability.MISSING
        var downloadFailure: Throwable? = null
        var qualificationFailure: Throwable? = null
        var activationFailure: Throwable? = null
        var qualificationScript: (() -> ExactDownloadQualification?)? = null
        var downloadScript: ((() -> Boolean, (Long, Long) -> Unit) -> Unit)? = null
        var publishResult: (DownloadObservation, Boolean) -> DownloadPublishResult = { _, _ ->
            DownloadPublishResult.Persisted
        }
        var readyCommitBehavior = ReadyCommitBehavior.PERSIST_READY
        var recoverReadyOnNextRestore = false

        val modelEvents = mutableListOf<String>()
        val publications = mutableListOf<Publication>()
        val failedCallbacks = mutableListOf<DownloadFailureCode>()
        var waitingCallbacks = 0
        var readyCallbacks = 0
        var downloadCalls = 0
        var qualificationCalls = 0
        var activationCalls = 0
        var readyCommitCalls = 0
        var readyCommitActionCalls = 0
        var qualifiedOutsideCommit = false
        var activatedInsideCommit = false
        private var insideReadyCommit = false
        private var lastFailureCode = DownloadFailureCode.UNKNOWN

        override val canonicalTotalBytes: Long
            get() {
                modelEvents += "total"
                return totalBytes
            }

        override fun isCurrent(attempt: DownloadAttempt): Boolean =
            current && phase !in TERMINAL_PHASES

        override fun restoreTelemetry(target: DownloadTargetKey): DownloadTelemetry? {
            if (recoverReadyOnNextRestore) {
                recoverReadyOnNextRestore = false
                phase = DownloadPhase.READY
                current = false
            }
            return telemetry()
        }

        override fun networkDecision(target: DownloadTargetKey): DownloadNetworkDecision =
            networkDecision

        override fun publish(
            attempt: DownloadAttempt,
            observation: DownloadObservation,
            finalByteCount: Boolean,
        ): DownloadPublishResult {
            if (!isCurrent(attempt)) {
                return DownloadPublishResult.Stale.also { result ->
                    publications += Publication(observation, finalByteCount, result)
                }
            }
            val result = publishResult(observation, finalByteCount)
            publications += Publication(observation, finalByteCount, result)
            if (result == DownloadPublishResult.Persisted) {
                phase = observation.phase
                downloadedBytes = observation.downloadedBytes
                observation.failureCode?.let { lastFailureCode = it }
                if (phase in TERMINAL_PHASES) current = false
            }
            return result
        }

        override fun commitReadyIfCurrent(
            attempt: DownloadAttempt,
            readyObservation: DownloadObservation,
            action: () -> Unit,
        ): Boolean {
            readyCommitCalls += 1
            if (!isCurrent(attempt)) return false
            return when (readyCommitBehavior) {
                ReadyCommitBehavior.RETURN_FALSE_CURRENT -> false
                ReadyCommitBehavior.THROW_BEFORE_ACTION -> {
                    throw IOException("ready snapshot failed before action")
                }
                ReadyCommitBehavior.PERSIST_READY -> {
                    runReadyAction(action)
                    phase = DownloadPhase.READY
                    downloadedBytes = readyObservation.downloadedBytes
                    current = false
                    true
                }
                ReadyCommitBehavior.THROW_AND_RECOVER_READY -> {
                    runReadyAction(action)
                    phase = DownloadPhase.VERIFYING
                    recoverReadyOnNextRestore = true
                    throw IOException("ready snapshot failed")
                }
                ReadyCommitBehavior.THROW_LEAVE_VERIFYING -> {
                    runReadyAction(action)
                    phase = DownloadPhase.VERIFYING
                    throw IOException("ready snapshot failed")
                }
            }
        }

        override fun modelAvailability(): ExactDownloadModelAvailability {
            modelEvents += "availability"
            return availability
        }

        override fun partialBytes(): Long {
            modelEvents += "partial"
            return partialBytesOnDisk ?: downloadedBytes
        }

        override fun download(
            stopped: () -> Boolean,
            progress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        ) {
            modelEvents += "download"
            downloadCalls += 1
            downloadFailure?.let { throw it }
            val trackingProgress: (Long, Long) -> Unit = { downloaded, total ->
                downloadedBytes = downloaded
                progress(downloaded, total)
            }
            downloadScript?.invoke(stopped, trackingProgress)
            downloadedBytes = totalBytes
            availability = ExactDownloadModelAvailability.CANDIDATE
        }

        override fun qualifyCandidate(): ExactDownloadQualification? {
            modelEvents += "qualify"
            qualificationCalls += 1
            qualifiedOutsideCommit = !insideReadyCommit
            qualificationFailure?.let { throw it }
            val script = qualificationScript
            return if (script == null) FakeQualification else script()
        }

        override fun activateCandidate(qualification: ExactDownloadQualification) {
            modelEvents += "activate"
            activationCalls += 1
            activatedInsideCommit = insideReadyCommit
            activationFailure?.let { throw it }
            availability = ExactDownloadModelAvailability.INSTALLED
        }

        override fun onWaiting() {
            modelEvents += "waiting-callback"
            waitingCallbacks += 1
        }

        override fun onFailed(failureCode: DownloadFailureCode) {
            modelEvents += "failed-callback"
            failedCallbacks += failureCode
        }

        override fun onReady() {
            modelEvents += "ready-callback"
            readyCallbacks += 1
        }

        fun cancelDurably() {
            phase = DownloadPhase.PAUSED
            current = false
        }

        fun assertWaiting(network: DownloadNetwork, reason: DownloadBlockReason) {
            val waiting = publications.last { it.observation.phase == DownloadPhase.WAITING }
            assertEquals(DownloadPublishResult.Persisted, waiting.result)
            assertEquals(network, waiting.observation.network)
            assertEquals(reason, waiting.observation.blockReason)
            assertEquals(DownloadPhase.WAITING, phase)
            assertEquals(1, waitingCallbacks)
        }

        private fun runReadyAction(action: () -> Unit) {
            insideReadyCommit = true
            try {
                readyCommitActionCalls += 1
                action()
            } finally {
                insideReadyCommit = false
            }
        }

        private fun telemetry(): DownloadTelemetry = DownloadTelemetryRestoreFactory().restore(
            observation = DownloadObservation(
                generation = attempt.generation,
                phase = phase,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                network = networkDecision.currentNetwork,
                blockReason = when (phase) {
                    DownloadPhase.WAITING -> DownloadBlockReason.NETWORK_UNAVAILABLE
                    DownloadPhase.PAUSED -> DownloadBlockReason.USER_PAUSED
                    else -> null
                },
                failureCode = lastFailureCode.takeIf { phase == DownloadPhase.FAILED },
                verificationSucceeded = phase == DownloadPhase.READY,
                cancellationAllowed = phase in setOf(
                    DownloadPhase.PREPARING,
                    DownloadPhase.DOWNLOADING,
                    DownloadPhase.VERIFYING,
                    DownloadPhase.WAITING,
                ),
                retryAllowed = phase == DownloadPhase.PAUSED || phase == DownloadPhase.FAILED,
            ),
            updatedAtMillis = 1L,
            samples = emptyList(),
        )

        private companion object {
            val TERMINAL_PHASES = setOf(
                DownloadPhase.READY,
                DownloadPhase.PAUSED,
                DownloadPhase.FAILED,
            )
        }
    }

    private companion object {
        val target = DownloadTargetKey(
            AiModelFamily.GEMMA,
            AiModelTier.FAST,
            BackendKind.LITERT_CPU,
        )
        val attempt = DownloadAttempt(
            target = target,
            generation = DownloadGeneration(1L),
            workIdentity = DownloadWorkIdentity(11L, 12L),
        )

        fun allowed(network: DownloadNetwork) = DownloadNetworkDecision(
            currentNetwork = network,
            evaluation = DownloadNetworkPolicyEvaluation.Allowed,
        )

        fun waiting(
            network: DownloadNetwork,
            reason: DownloadBlockReason,
        ) = DownloadNetworkDecision(
            currentNetwork = network,
            evaluation = DownloadNetworkPolicyEvaluation.Waiting(reason),
        )
    }
}
