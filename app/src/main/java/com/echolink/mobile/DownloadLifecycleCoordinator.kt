package com.echolink.mobile

import java.util.concurrent.CancellationException

data class DownloadAttempt(
    val target: DownloadTargetKey,
    val generation: DownloadGeneration,
    val workIdentity: DownloadWorkIdentity,
) {
    companion object {
        fun from(record: DurableDownloadRecord): DownloadAttempt = DownloadAttempt(
            target = record.target,
            generation = record.telemetry.generation,
            workIdentity = record.workIdentity,
        )
    }
}

data class DownloadPartialSnapshot(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val network: DownloadNetwork,
) {
    init {
        require(downloadedBytes >= 0L) { "Partial download bytes cannot be negative." }
        require(totalBytes == null || totalBytes > 0L) {
            "Known download size must be positive."
        }
        require(totalBytes == null || downloadedBytes <= totalBytes) {
            "Partial download bytes cannot exceed the known download size."
        }
    }
}

fun interface DownloadWorkIdentitySource {
    fun next(): DownloadWorkIdentity
}

fun interface DownloadPartialSnapshotProvider {
    fun snapshot(target: DownloadTargetKey): DownloadPartialSnapshot
}

interface DownloadAttemptScheduler {
    fun ensureScheduled(attempt: DownloadAttempt)

    fun cancel(workIdentity: DownloadWorkIdentity)
}

internal class DownloadLifecycleCoordinator(
    private val store: DownloadTelemetryStore,
    private val scheduler: DownloadAttemptScheduler,
    private val workIdentitySource: DownloadWorkIdentitySource,
    private val partialSnapshotProvider: DownloadPartialSnapshotProvider,
    private val telemetryCalculator: DownloadTelemetryCalculator,
) {
    fun ensure(target: DownloadTargetKey): DownloadAttempt = synchronized(PROCESS_LOCK) {
        val existing = store.read(target)
        if (existing != null) {
            val attempt = DownloadAttempt.from(existing)
            if (existing.telemetry.phase in ACTIVE_OR_WAITING_PHASES) {
                ensureScheduledSafely(attempt)
            }
            return@synchronized attempt
        }

        reserveAndSchedule(
            target = target,
            snapshot = partialSnapshotProvider.snapshot(target),
            previousWorkIdentity = null,
        )
    }

    fun restoreTelemetry(target: DownloadTargetKey): DownloadTelemetry? =
        synchronized(PROCESS_LOCK) {
            store.read(target)?.telemetry
        }

    fun publish(
        attempt: DownloadAttempt,
        observation: DownloadObservation,
    ): Boolean = synchronized(PROCESS_LOCK) {
        if (observation.generation != attempt.generation) return@synchronized false
        val current = exactCurrent(attempt) ?: return@synchronized false
        if (current.telemetry.phase in TERMINAL_PHASES) return@synchronized false

        val telemetry = telemetryCalculator.record(
            observation = observation,
            previous = current.telemetry,
        )
        store.write(
            DurableDownloadRecord(
                target = current.target,
                workIdentity = current.workIdentity,
                telemetry = telemetry,
            ),
        )
    }

    fun cancel(attempt: DownloadAttempt): Boolean = synchronized(PROCESS_LOCK) {
        val current = exactCurrent(attempt) ?: return@synchronized false
        if (current.telemetry.phase !in CANCEL_ACCEPTING_PHASES) return@synchronized false
        val paused = telemetryCalculator.record(
            observation = DownloadObservation(
                generation = current.telemetry.generation,
                phase = DownloadPhase.PAUSED,
                downloadedBytes = current.telemetry.downloadedBytes,
                totalBytes = current.telemetry.totalBytes,
                network = current.telemetry.network,
                blockReason = DownloadBlockReason.USER_PAUSED,
                cancellationAllowed = false,
                retryAllowed = true,
            ),
            previous = current.telemetry,
        )
        val persisted = store.write(
            DurableDownloadRecord(
                target = current.target,
                workIdentity = current.workIdentity,
                telemetry = paused,
            ),
        )
        if (!persisted) return@synchronized false

        scheduler.cancel(current.workIdentity)
        true
    }

    fun retry(attempt: DownloadAttempt): DownloadAttempt? = synchronized(PROCESS_LOCK) {
        val current = exactCurrent(attempt) ?: return@synchronized null
        if (current.telemetry.phase !in RETRYABLE_PHASES) return@synchronized null

        scheduler.cancel(current.workIdentity)
        reserveAndSchedule(
            target = current.target,
            snapshot = partialSnapshotProvider.snapshot(current.target),
            previousWorkIdentity = current.workIdentity,
        )
    }

    private fun reserveAndSchedule(
        target: DownloadTargetKey,
        snapshot: DownloadPartialSnapshot,
        previousWorkIdentity: DownloadWorkIdentity?,
    ): DownloadAttempt {
        val workIdentity = workIdentitySource.next()
        check(workIdentity != previousWorkIdentity) {
            "Retry requires a fresh work identity."
        }
        val reserved = store.reserveNextGeneration(
            target = target,
            workIdentity = workIdentity,
            network = snapshot.network,
            downloadedBytes = snapshot.downloadedBytes,
            totalBytes = snapshot.totalBytes,
        )
        val attempt = DownloadAttempt.from(reserved)
        ensureScheduledSafely(attempt)
        return attempt
    }

    private fun ensureScheduledSafely(attempt: DownloadAttempt) {
        try {
            scheduler.ensureScheduled(attempt)
        } catch (cancelled: CancellationException) {
            failSafelyWithoutMasking(attempt, cancelled)
            throw cancelled
        } catch (failure: Exception) {
            failSafelyWithoutMasking(attempt, failure)
            throw failure
        }
    }

    private fun failSafelyWithoutMasking(
        attempt: DownloadAttempt,
        schedulingFailure: Exception,
    ) {
        try {
            if (!persistSchedulingFailure(attempt)) {
                schedulingFailure.addSuppressed(
                    IllegalStateException("The scheduler failure could not be persisted."),
                )
            }
        } catch (persistenceFailure: Exception) {
            schedulingFailure.addSuppressed(persistenceFailure)
        }
    }

    private fun persistSchedulingFailure(attempt: DownloadAttempt): Boolean {
        val current = exactCurrent(attempt) ?: return false
        if (current.telemetry.phase in TERMINAL_PHASES) return true
        val failed = telemetryCalculator.record(
            observation = DownloadObservation(
                generation = current.telemetry.generation,
                phase = DownloadPhase.FAILED,
                downloadedBytes = current.telemetry.downloadedBytes,
                totalBytes = current.telemetry.totalBytes,
                network = current.telemetry.network,
                failureCode = DownloadFailureCode.DOWNLOAD_INTERRUPTED,
                cancellationAllowed = false,
                retryAllowed = true,
            ),
            previous = current.telemetry,
        )
        return store.write(
            DurableDownloadRecord(
                target = current.target,
                workIdentity = current.workIdentity,
                telemetry = failed,
            ),
        )
    }

    private fun exactCurrent(attempt: DownloadAttempt): DurableDownloadRecord? {
        val current = store.read(attempt.target) ?: return null
        return current.takeIf {
            it.target == attempt.target &&
                it.telemetry.generation == attempt.generation &&
                it.workIdentity == attempt.workIdentity
        }
    }

    private companion object {
        val PROCESS_LOCK = Any()
        val TERMINAL_PHASES = setOf(
            DownloadPhase.READY,
            DownloadPhase.PAUSED,
            DownloadPhase.FAILED,
        )
        val CANCEL_ACCEPTING_PHASES = setOf(
            DownloadPhase.PREPARING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.VERIFYING,
            DownloadPhase.WAITING,
        )
        val RETRYABLE_PHASES = setOf(
            DownloadPhase.PAUSED,
            DownloadPhase.FAILED,
        )
        val ACTIVE_OR_WAITING_PHASES = setOf(
            DownloadPhase.PREPARING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.VERIFYING,
            DownloadPhase.WAITING,
        )
    }
}
