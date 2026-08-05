package com.echolink.mobile

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

internal enum class DownloadAttemptExecutionResult {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal enum class ExactDownloadModelAvailability {
    MISSING,
    CANDIDATE,
    INSTALLED,
}

internal interface ExactDownloadQualification

internal class ExactDownloadFailure(
    val failureCode: DownloadFailureCode,
) : IOException(failureCode.name)

internal interface ExactDownloadAttemptPort {
    val canonicalTotalBytes: Long

    fun isCurrent(attempt: DownloadAttempt): Boolean

    fun restoreTelemetry(target: DownloadTargetKey): DownloadTelemetry?

    fun networkDecision(target: DownloadTargetKey): DownloadNetworkDecision

    fun publish(
        attempt: DownloadAttempt,
        observation: DownloadObservation,
        finalByteCount: Boolean,
    ): DownloadPublishResult

    fun commitReadyIfCurrent(
        attempt: DownloadAttempt,
        readyObservation: DownloadObservation,
        action: () -> Unit,
    ): Boolean

    fun modelAvailability(): ExactDownloadModelAvailability

    fun partialBytes(): Long

    fun download(
        stopped: () -> Boolean,
        progress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    )

    fun qualifyCandidate(): ExactDownloadQualification?

    fun activateCandidate(qualification: ExactDownloadQualification)

    fun onWaiting()

    fun onFailed(failureCode: DownloadFailureCode)

    fun onReady()
}

internal class ExactDownloadAttemptExecutor(
    private val port: ExactDownloadAttemptPort,
) {
    fun execute(
        attempt: DownloadAttempt,
        systemStopped: () -> Boolean = { false },
    ): DownloadAttemptExecutionResult {
        throwIfSystemStopped(systemStopped)
        if (!port.isCurrent(attempt)) return DownloadAttemptExecutionResult.SUCCESS

        val restored = port.restoreTelemetry(attempt.target)
            ?: return DownloadAttemptExecutionResult.SUCCESS
        terminalResult(restored)?.let { result ->
            if (restored.phase == DownloadPhase.READY) port.onReady()
            return result
        }
        throwIfSystemStopped(systemStopped)
        if (!port.isCurrent(attempt)) return DownloadAttemptExecutionResult.SUCCESS

        val availability = port.modelAvailability()
        val totalBytes = port.canonicalTotalBytes
        require(totalBytes > 0L) { "The exact model size must be positive." }
        if (
            restored.phase == DownloadPhase.WAITING &&
            availability != ExactDownloadModelAvailability.MISSING
        ) {
            resumeWaitingForLocalWork(attempt, totalBytes)?.let { return it }
        }

        return when (availability) {
            ExactDownloadModelAvailability.INSTALLED -> completeReady(
                attempt = attempt,
                totalBytes = totalBytes,
                qualification = null,
                systemStopped = systemStopped,
            )
            ExactDownloadModelAvailability.CANDIDATE -> qualifyAndComplete(
                attempt = attempt,
                totalBytes = totalBytes,
                systemStopped = systemStopped,
            )
            ExactDownloadModelAvailability.MISSING -> downloadAndComplete(
                attempt = attempt,
                totalBytes = totalBytes,
                restored = restored,
                systemStopped = systemStopped,
            )
        }
    }

    private fun downloadAndComplete(
        attempt: DownloadAttempt,
        totalBytes: Long,
        restored: DownloadTelemetry,
        systemStopped: () -> Boolean,
    ): DownloadAttemptExecutionResult {
        val initialNetwork = port.networkDecision(attempt.target)
        waitingReason(initialNetwork)?.let {
            return persistWaiting(attempt, totalBytes, initialNetwork)
        }
        val partialBytes = boundedPartialBytes(totalBytes)
        var durableBytes = restored.downloadedBytes
        if (restored.phase == DownloadPhase.WAITING) {
            val preparingBytes = maxOf(durableBytes, partialBytes)
            when (
                persistControlPhase(
                    attempt = attempt,
                    observation = observation(
                        attempt = attempt,
                        phase = DownloadPhase.PREPARING,
                        downloadedBytes = preparingBytes,
                        totalBytes = totalBytes,
                        network = initialNetwork.currentNetwork,
                    ),
                )
            ) {
                ControlPhaseResult.PERSISTED -> Unit
                ControlPhaseResult.STALE -> return DownloadAttemptExecutionResult.SUCCESS
                ControlPhaseResult.RETRY -> return DownloadAttemptExecutionResult.RETRY
            }
            durableBytes = preparingBytes
        }
        if (partialBytes < durableBytes) {
            when (
                persistControlPhase(
                    attempt = attempt,
                    observation = observation(
                        attempt = attempt,
                        phase = DownloadPhase.DOWNLOADING,
                        downloadedBytes = 0L,
                        totalBytes = totalBytes,
                        network = initialNetwork.currentNetwork,
                    ),
                )
            ) {
                ControlPhaseResult.PERSISTED -> Unit
                ControlPhaseResult.STALE -> return DownloadAttemptExecutionResult.SUCCESS
                ControlPhaseResult.RETRY -> return DownloadAttemptExecutionResult.RETRY
            }
        }

        try {
            port.download(
                stopped = {
                    when {
                        systemStopped() -> true
                        !port.isCurrent(attempt) -> true
                        else -> {
                            waitingReason(port.networkDecision(attempt.target)) != null
                        }
                    }
                },
                progress = { downloadedBytes, reportedTotalBytes ->
                    throwIfSystemStopped(systemStopped)
                    if (!port.isCurrent(attempt)) throw StaleAttemptSignal()
                    require(reportedTotalBytes == totalBytes) {
                        "Download progress must retain the canonical model size."
                    }
                    require(downloadedBytes in 0L..totalBytes) {
                        "Download progress is outside the canonical model size."
                    }
                    val progressNetwork = port.networkDecision(attempt.target).currentNetwork
                    when (
                        port.publish(
                            attempt = attempt,
                            observation = observation(
                                attempt = attempt,
                                phase = DownloadPhase.DOWNLOADING,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                network = progressNetwork,
                            ),
                            finalByteCount = false,
                        )
                    ) {
                        DownloadPublishResult.Persisted,
                        DownloadPublishResult.Throttled,
                        -> Unit
                        DownloadPublishResult.Stale -> throw StaleAttemptSignal()
                        DownloadPublishResult.Rejected -> throw RetryAttemptSignal()
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleAttemptSignal) {
            return DownloadAttemptExecutionResult.SUCCESS
        } catch (_: RetryAttemptSignal) {
            return currentRetryOrSuccess(attempt)
        } catch (failure: Exception) {
            return handleDownloadFailure(
                attempt = attempt,
                totalBytes = totalBytes,
                failure = failure,
                systemStopped = systemStopped,
            )
        }

        throwIfSystemStopped(systemStopped)
        if (!port.isCurrent(attempt)) return DownloadAttemptExecutionResult.SUCCESS
        val finalNetwork = port.networkDecision(attempt.target).currentNetwork
        when (
            port.publish(
                attempt = attempt,
                observation = observation(
                    attempt = attempt,
                    phase = DownloadPhase.DOWNLOADING,
                    downloadedBytes = totalBytes,
                    totalBytes = totalBytes,
                    network = finalNetwork,
                ),
                finalByteCount = true,
            )
        ) {
            DownloadPublishResult.Persisted -> Unit
            DownloadPublishResult.Stale -> return DownloadAttemptExecutionResult.SUCCESS
            DownloadPublishResult.Throttled,
            DownloadPublishResult.Rejected,
            -> return currentRetryOrSuccess(attempt)
        }
        return qualifyAndComplete(attempt, totalBytes, systemStopped)
    }

    private fun qualifyAndComplete(
        attempt: DownloadAttempt,
        totalBytes: Long,
        systemStopped: () -> Boolean,
    ): DownloadAttemptExecutionResult {
        throwIfSystemStopped(systemStopped)
        if (!port.isCurrent(attempt)) return DownloadAttemptExecutionResult.SUCCESS

        val network = port.networkDecision(attempt.target).currentNetwork
        when (
            persistControlPhase(
                attempt = attempt,
                observation = observation(
                    attempt = attempt,
                    phase = DownloadPhase.VERIFYING,
                    downloadedBytes = totalBytes,
                    totalBytes = totalBytes,
                    network = network,
                ),
                allowExistingPhase = DownloadPhase.VERIFYING,
            )
        ) {
            ControlPhaseResult.PERSISTED -> Unit
            ControlPhaseResult.STALE -> return DownloadAttemptExecutionResult.SUCCESS
            ControlPhaseResult.RETRY -> return DownloadAttemptExecutionResult.RETRY
        }

        val qualification = try {
            port.qualifyCandidate()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throwIfSystemStopped(systemStopped)
            if (!port.isCurrent(attempt)) return DownloadAttemptExecutionResult.SUCCESS
            return persistFailure(attempt, totalBytes, failureCode(failure))
        } ?: return persistFailure(
            attempt,
            totalBytes,
            DownloadFailureCode.SELF_TEST_FAILED,
        )

        throwIfSystemStopped(systemStopped)
        if (!port.isCurrent(attempt)) return DownloadAttemptExecutionResult.SUCCESS
        return completeReady(attempt, totalBytes, qualification, systemStopped)
    }

    private fun completeReady(
        attempt: DownloadAttempt,
        totalBytes: Long,
        qualification: ExactDownloadQualification?,
        systemStopped: () -> Boolean,
    ): DownloadAttemptExecutionResult {
        throwIfSystemStopped(systemStopped)
        if (!port.isCurrent(attempt)) return DownloadAttemptExecutionResult.SUCCESS

        val current = port.restoreTelemetry(attempt.target)
            ?: return DownloadAttemptExecutionResult.SUCCESS
        if (current.phase == DownloadPhase.READY) {
            port.onReady()
            return DownloadAttemptExecutionResult.SUCCESS
        }
        if (current.phase in TERMINAL_PHASES) return DownloadAttemptExecutionResult.SUCCESS
        if (current.phase != DownloadPhase.VERIFYING) {
            val network = port.networkDecision(attempt.target).currentNetwork
            when (
                persistControlPhase(
                    attempt = attempt,
                    observation = observation(
                        attempt = attempt,
                        phase = DownloadPhase.VERIFYING,
                        downloadedBytes = totalBytes,
                        totalBytes = totalBytes,
                        network = network,
                    ),
                    allowExistingPhase = DownloadPhase.VERIFYING,
                )
            ) {
                ControlPhaseResult.PERSISTED -> Unit
                ControlPhaseResult.STALE -> return DownloadAttemptExecutionResult.SUCCESS
                ControlPhaseResult.RETRY -> return DownloadAttemptExecutionResult.RETRY
            }
        }

        var readyActionStarted = false
        var readyActionCompleted = false
        val readyObservation = observation(
            attempt = attempt,
            phase = DownloadPhase.READY,
            downloadedBytes = totalBytes,
            totalBytes = totalBytes,
            network = port.networkDecision(attempt.target).currentNetwork,
            verificationSucceeded = true,
        )
        val committed = try {
            port.commitReadyIfCurrent(attempt, readyObservation) {
                readyActionStarted = true
                qualification?.let(port::activateCandidate)
                readyActionCompleted = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throwIfSystemStopped(systemStopped)
            if (!port.isCurrent(attempt)) return readyRecoveryResult(attempt)
            if (!readyActionStarted) return DownloadAttemptExecutionResult.RETRY
            if (!readyActionCompleted) {
                return persistFailure(attempt, totalBytes, failureCode(failure))
            }
            return readyRecoveryResult(attempt)
        }
        if (!committed) return readyRecoveryResult(attempt)

        port.onReady()
        return DownloadAttemptExecutionResult.SUCCESS
    }

    private fun handleDownloadFailure(
        attempt: DownloadAttempt,
        totalBytes: Long,
        failure: Throwable,
        systemStopped: () -> Boolean,
    ): DownloadAttemptExecutionResult {
        throwIfSystemStopped(systemStopped)
        if (!port.isCurrent(attempt)) return DownloadAttemptExecutionResult.SUCCESS
        if (failure is InterruptedIOException) {
            val decision = port.networkDecision(attempt.target)
            if (waitingReason(decision) != null) {
                return persistWaiting(attempt, totalBytes, decision)
            }
        }
        return persistFailure(attempt, totalBytes, failureCode(failure))
    }

    private fun persistWaiting(
        attempt: DownloadAttempt,
        totalBytes: Long,
        decision: DownloadNetworkDecision,
    ): DownloadAttemptExecutionResult {
        val reason = waitingReason(decision)
            ?: error("Only a blocked network decision can enter Waiting.")
        val result = persistControlPhase(
            attempt = attempt,
            observation = observation(
                attempt = attempt,
                phase = DownloadPhase.WAITING,
                downloadedBytes = boundedPartialBytes(totalBytes),
                totalBytes = totalBytes,
                network = decision.currentNetwork,
                blockReason = reason,
            ),
            allowExistingPhase = DownloadPhase.WAITING,
        )
        return when (result) {
            ControlPhaseResult.PERSISTED -> {
                port.onWaiting()
                DownloadAttemptExecutionResult.RETRY
            }
            ControlPhaseResult.STALE -> DownloadAttemptExecutionResult.SUCCESS
            ControlPhaseResult.RETRY -> DownloadAttemptExecutionResult.RETRY
        }
    }

    private fun resumeWaitingForLocalWork(
        attempt: DownloadAttempt,
        totalBytes: Long,
    ): DownloadAttemptExecutionResult? {
        val network = port.networkDecision(attempt.target).currentNetwork
        return when (
            persistControlPhase(
                attempt = attempt,
                observation = observation(
                    attempt = attempt,
                    phase = DownloadPhase.PREPARING,
                    downloadedBytes = boundedPartialBytes(totalBytes),
                    totalBytes = totalBytes,
                    network = network,
                ),
            )
        ) {
            ControlPhaseResult.PERSISTED -> null
            ControlPhaseResult.STALE -> DownloadAttemptExecutionResult.SUCCESS
            ControlPhaseResult.RETRY -> DownloadAttemptExecutionResult.RETRY
        }
    }

    private fun persistFailure(
        attempt: DownloadAttempt,
        totalBytes: Long,
        failureCode: DownloadFailureCode,
    ): DownloadAttemptExecutionResult {
        if (!port.isCurrent(attempt)) return DownloadAttemptExecutionResult.SUCCESS
        val network = port.networkDecision(attempt.target).currentNetwork
        val result = persistControlPhase(
            attempt = attempt,
            observation = observation(
                attempt = attempt,
                phase = DownloadPhase.FAILED,
                downloadedBytes = boundedPartialBytes(totalBytes),
                totalBytes = totalBytes,
                network = network,
                failureCode = failureCode,
                cancellationAllowed = false,
                retryAllowed = true,
            ),
            allowExistingPhase = DownloadPhase.FAILED,
        )
        return when (result) {
            ControlPhaseResult.PERSISTED -> {
                port.onFailed(failureCode)
                DownloadAttemptExecutionResult.FAILURE
            }
            ControlPhaseResult.STALE -> DownloadAttemptExecutionResult.SUCCESS
            ControlPhaseResult.RETRY -> DownloadAttemptExecutionResult.RETRY
        }
    }

    private fun persistControlPhase(
        attempt: DownloadAttempt,
        observation: DownloadObservation,
        allowExistingPhase: DownloadPhase? = null,
    ): ControlPhaseResult = when (
        port.publish(
            attempt = attempt,
            observation = observation,
            finalByteCount = observation.downloadedBytes == observation.totalBytes,
        )
    ) {
        DownloadPublishResult.Persisted -> ControlPhaseResult.PERSISTED
        DownloadPublishResult.Stale -> ControlPhaseResult.STALE
        DownloadPublishResult.Throttled -> {
            if (allowExistingPhase != null && durablePhase(attempt) == allowExistingPhase) {
                ControlPhaseResult.PERSISTED
            } else {
                currentControlRetryOrStale(attempt)
            }
        }
        DownloadPublishResult.Rejected -> currentControlRetryOrStale(attempt)
    }

    private fun currentControlRetryOrStale(attempt: DownloadAttempt): ControlPhaseResult =
        if (port.isCurrent(attempt)) ControlPhaseResult.RETRY else ControlPhaseResult.STALE

    private fun currentRetryOrSuccess(attempt: DownloadAttempt): DownloadAttemptExecutionResult =
        if (port.isCurrent(attempt)) {
            DownloadAttemptExecutionResult.RETRY
        } else {
            DownloadAttemptExecutionResult.SUCCESS
        }

    private fun readyRecoveryResult(attempt: DownloadAttempt): DownloadAttemptExecutionResult {
        val recovered = port.restoreTelemetry(attempt.target)
        if (recovered?.phase == DownloadPhase.READY) {
            port.onReady()
            return DownloadAttemptExecutionResult.SUCCESS
        }
        return currentRetryOrSuccess(attempt)
    }

    private fun durablePhase(attempt: DownloadAttempt): DownloadPhase? =
        port.restoreTelemetry(attempt.target)?.phase

    private fun boundedPartialBytes(totalBytes: Long): Long =
        port.partialBytes().coerceIn(0L, totalBytes)

    private fun waitingReason(decision: DownloadNetworkDecision): DownloadBlockReason? =
        (decision.evaluation as? DownloadNetworkPolicyEvaluation.Waiting)?.reason

    private fun failureCode(failure: Throwable): DownloadFailureCode = when (failure) {
        is ExactDownloadFailure -> failure.failureCode
        is AiModelStorageException -> DownloadFailureCode.STORAGE_UNAVAILABLE
        is AiModelIntegrityException -> DownloadFailureCode.INTEGRITY_CHECK_FAILED
        is InterruptedIOException -> DownloadFailureCode.DOWNLOAD_INTERRUPTED
        else -> DownloadFailureCode.UNKNOWN
    }

    private fun observation(
        attempt: DownloadAttempt,
        phase: DownloadPhase,
        downloadedBytes: Long,
        totalBytes: Long,
        network: DownloadNetwork,
        blockReason: DownloadBlockReason? = null,
        failureCode: DownloadFailureCode? = null,
        verificationSucceeded: Boolean = false,
        cancellationAllowed: Boolean = phase in CANCELLABLE_PHASES,
        retryAllowed: Boolean = phase == DownloadPhase.FAILED,
    ): DownloadObservation = DownloadObservation(
        generation = attempt.generation,
        phase = phase,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        network = network,
        blockReason = blockReason,
        failureCode = failureCode,
        verificationSucceeded = verificationSucceeded,
        cancellationAllowed = cancellationAllowed,
        retryAllowed = retryAllowed,
    )

    private fun terminalResult(telemetry: DownloadTelemetry): DownloadAttemptExecutionResult? =
        if (telemetry.phase in TERMINAL_PHASES) DownloadAttemptExecutionResult.SUCCESS else null

    private fun throwIfSystemStopped(systemStopped: () -> Boolean) {
        if (systemStopped()) throw CancellationException("Download work stopped.")
    }

    private enum class ControlPhaseResult {
        PERSISTED,
        STALE,
        RETRY,
    }

    private class StaleAttemptSignal : RuntimeException()
    private class RetryAttemptSignal : RuntimeException()

    private companion object {
        val TERMINAL_PHASES = setOf(
            DownloadPhase.READY,
            DownloadPhase.PAUSED,
            DownloadPhase.FAILED,
        )
        val CANCELLABLE_PHASES = setOf(
            DownloadPhase.PREPARING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.VERIFYING,
            DownloadPhase.WAITING,
        )
    }
}
