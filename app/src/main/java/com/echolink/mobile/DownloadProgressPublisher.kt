package com.echolink.mobile

import java.util.LinkedHashMap

internal data class DownloadProgressPublication(
    val attempt: DownloadAttempt,
    val telemetry: DownloadTelemetry,
    val isFinalByteCount: Boolean,
)

internal class DownloadProgressPublisher(
    private val minimumByteDelta: Long = DEFAULT_MINIMUM_BYTE_DELTA,
    private val minimumTimeIntervalMillis: Long = DEFAULT_MINIMUM_TIME_INTERVAL_MILLIS,
    private val maxTrackedAttempts: Int = DEFAULT_MAX_TRACKED_ATTEMPTS,
    private val sampleWindowMillis: Long = DownloadTelemetryCalculator.DEFAULT_SAMPLE_WINDOW_MILLIS,
    private val maxSampleCount: Int = DownloadTelemetryCalculator.DEFAULT_MAX_SAMPLE_COUNT,
) {
    private val attempts = LinkedHashMap<DownloadAttempt, AttemptState>(
        maxTrackedAttempts.coerceAtMost(DEFAULT_MAX_TRACKED_ATTEMPTS),
        LOAD_FACTOR,
        true,
    )

    init {
        require(minimumByteDelta > 0L) { "Minimum byte delta must be positive." }
        require(minimumTimeIntervalMillis > 0L) {
            "Minimum time interval must be positive."
        }
        require(maxTrackedAttempts in 1..DEFAULT_MAX_TRACKED_ATTEMPTS) {
            "Tracked attempt capacity must stay within the fixed safety bound."
        }
        require(sampleWindowMillis > 0L) { "Sample window must be positive." }
        require(maxSampleCount >= 2) { "At least two samples are required." }
    }

    @Synchronized
    fun offer(
        attempt: DownloadAttempt,
        observation: DownloadObservation,
        timestampMillis: Long,
        isFinalByteCount: Boolean = false,
    ): DownloadProgressPublication? {
        require(observation.generation == attempt.generation) {
            "The progress generation must match the download attempt."
        }
        require(timestampMillis >= 0L) { "Progress time cannot be negative." }

        val previous = attempts[attempt]
        if (
            previous != null &&
            (observation.downloadedBytes < previous.lastSeenBytes ||
                timestampMillis < previous.lastSeenTimestampMillis)
        ) {
            return null
        }

        val knownCompletion = observation.totalBytes?.let { total ->
            observation.downloadedBytes >= total
        } ?: false
        val effectiveFinal = isFinalByteCount || knownCompletion
        if (
            previous?.finalPublished == true &&
            effectiveFinal &&
            observation.phase == previous.lastPublishedPhase
        ) {
            previous.lastSeenBytes = observation.downloadedBytes
            previous.lastSeenTimestampMillis = timestampMillis
            return null
        }
        val firstFinalPublication = effectiveFinal && previous?.finalPublished != true
        val shouldPublish = previous == null ||
            observation.phase != previous.lastPublishedPhase ||
            firstFinalPublication ||
            observation.downloadedBytes - previous.lastPublishedBytes >= minimumByteDelta ||
            timestampMillis - previous.lastPublishedTimestampMillis >= minimumTimeIntervalMillis

        if (previous != null) {
            previous.lastSeenBytes = observation.downloadedBytes
            previous.lastSeenTimestampMillis = timestampMillis
        }
        if (!shouldPublish) return null

        val telemetry = if (previous == null) {
            DownloadTelemetryRestoreFactory(
                sampleWindowMillis = sampleWindowMillis,
                maxSampleCount = maxSampleCount,
            ).restore(
                observation = observation,
                updatedAtMillis = timestampMillis,
                samples = listOf(
                    DownloadSample(
                        generation = attempt.generation,
                        timestampMillis = timestampMillis,
                        downloadedBytes = observation.downloadedBytes,
                    ),
                ),
            )
        } else {
            DownloadTelemetryCalculator(
                clock = { timestampMillis },
                sampleWindowMillis = sampleWindowMillis,
                maxSampleCount = maxSampleCount,
            ).record(
                observation = observation,
                previous = previous.telemetry,
            )
        }
        attempts[attempt] = AttemptState(
            lastSeenBytes = observation.downloadedBytes,
            lastSeenTimestampMillis = timestampMillis,
            lastPublishedBytes = observation.downloadedBytes,
            lastPublishedTimestampMillis = timestampMillis,
            lastPublishedPhase = observation.phase,
            telemetry = telemetry,
            finalPublished = previous?.finalPublished == true || firstFinalPublication,
        )
        trimOldestAttempts()
        return DownloadProgressPublication(
            attempt = attempt,
            telemetry = telemetry,
            isFinalByteCount = firstFinalPublication,
        )
    }

    internal val trackedAttemptCount: Int
        @Synchronized get() = attempts.size

    private fun trimOldestAttempts() {
        while (attempts.size > maxTrackedAttempts) {
            val iterator = attempts.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private data class AttemptState(
        var lastSeenBytes: Long,
        var lastSeenTimestampMillis: Long,
        val lastPublishedBytes: Long,
        val lastPublishedTimestampMillis: Long,
        val lastPublishedPhase: DownloadPhase,
        val telemetry: DownloadTelemetry,
        val finalPublished: Boolean,
    )

    companion object {
        const val DEFAULT_MINIMUM_BYTE_DELTA: Long = 16L * 1_024L * 1_024L
        const val DEFAULT_MINIMUM_TIME_INTERVAL_MILLIS: Long = 1_000L
        const val DEFAULT_MAX_TRACKED_ATTEMPTS: Int = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
