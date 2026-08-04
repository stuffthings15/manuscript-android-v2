package com.echolink.mobile

import java.math.BigInteger
import java.util.Collections

enum class DownloadPhase {
    PREPARING,
    DOWNLOADING,
    VERIFYING,
    READY,
    WAITING,
    PAUSED,
    FAILED,
}

enum class DownloadNetwork {
    UNKNOWN,
    OFFLINE,
    WIFI,
    MOBILE,
    ETHERNET,
    OTHER,
}

enum class DownloadBlockReason(
    val phase: DownloadPhase,
    val userMessage: String,
) {
    NETWORK_UNAVAILABLE(
        DownloadPhase.WAITING,
        "Waiting for a network connection.",
    ),
    WIFI_REQUIRED(
        DownloadPhase.WAITING,
        "Waiting for Wi-Fi.",
    ),
    USER_PAUSED(
        DownloadPhase.PAUSED,
        "Download paused by you.",
    ),
    DEVICE_CONSTRAINT(
        DownloadPhase.PAUSED,
        "Download paused because the device is not ready.",
    ),
}

enum class DownloadFailureCode {
    NETWORK_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
    INTEGRITY_CHECK_FAILED,
    SELF_TEST_FAILED,
    DOWNLOAD_INTERRUPTED,
    UNKNOWN,
}

enum class SafeDownloadFailure(
    val code: DownloadFailureCode,
    val userMessage: String,
) {
    NETWORK_UNAVAILABLE(
        DownloadFailureCode.NETWORK_UNAVAILABLE,
        "The AI download could not continue. Check the network and retry.",
    ),
    STORAGE_UNAVAILABLE(
        DownloadFailureCode.STORAGE_UNAVAILABLE,
        "There is not enough available storage. Free space and retry.",
    ),
    INTEGRITY_CHECK_FAILED(
        DownloadFailureCode.INTEGRITY_CHECK_FAILED,
        "The AI download did not pass its integrity check. Retry the download.",
    ),
    SELF_TEST_FAILED(
        DownloadFailureCode.SELF_TEST_FAILED,
        "The downloaded AI did not pass its local check. Retry the download.",
    ),
    DOWNLOAD_INTERRUPTED(
        DownloadFailureCode.DOWNLOAD_INTERRUPTED,
        "The AI download stopped before it finished. Retry when ready.",
    ),
    UNKNOWN(
        DownloadFailureCode.UNKNOWN,
        "The AI download failed. Retry when ready.",
    ),
    ;

    companion object {
        fun from(code: DownloadFailureCode): SafeDownloadFailure =
            entries.first { it.code == code }
    }
}

enum class DownloadAction {
    RETRY,
    CANCEL,
}

@JvmInline
value class DownloadGeneration(val value: Long) {
    init {
        require(value > 0L) { "Download generation must be positive." }
    }
}

data class DownloadSample(
    val generation: DownloadGeneration,
    val timestampMillis: Long,
    val downloadedBytes: Long,
) {
    init {
        require(timestampMillis >= 0L) { "Download sample time cannot be negative." }
        require(downloadedBytes >= 0L) { "Download sample bytes cannot be negative." }
    }
}

data class DownloadObservation(
    val generation: DownloadGeneration,
    val phase: DownloadPhase,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val network: DownloadNetwork = DownloadNetwork.UNKNOWN,
    val blockReason: DownloadBlockReason? = null,
    val failureCode: DownloadFailureCode? = null,
    val verificationSucceeded: Boolean = false,
    val cancellationAllowed: Boolean = true,
    val retryAllowed: Boolean = phase == DownloadPhase.FAILED,
) {
    init {
        require(downloadedBytes >= 0L) { "Downloaded bytes cannot be negative." }
        require(totalBytes == null || totalBytes > 0L) {
            "Total bytes must be positive when known."
        }
        val requiresBlockReason = phase == DownloadPhase.WAITING || phase == DownloadPhase.PAUSED
        require(requiresBlockReason == (blockReason != null)) {
            "Only waiting or paused downloads require a reason."
        }
        require(blockReason == null || blockReason.phase == phase) {
            "The download reason does not match its phase."
        }
        require((phase == DownloadPhase.FAILED) == (failureCode != null)) {
            "Only failed downloads require failure data."
        }
        require((phase == DownloadPhase.READY) == verificationSucceeded) {
            "Ready requires explicit successful verification."
        }
        require(
            phase != DownloadPhase.READY || totalBytes == null || downloadedBytes >= totalBytes,
        ) {
            "Ready requires all known bytes to be downloaded."
        }
        require(!retryAllowed || phase in RETRYABLE_PHASES) {
            "Retry is only available for paused or failed downloads."
        }
        require(phase != DownloadPhase.FAILED || retryAllowed) {
            "Failed downloads must allow Retry."
        }
    }
}

class DownloadTelemetry private constructor(
    val phase: DownloadPhase,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val percent: Int?,
    val bytesPerSecond: Long?,
    val etaSeconds: Long?,
    val network: DownloadNetwork,
    val blockReason: DownloadBlockReason?,
    val failure: SafeDownloadFailure?,
    val canRetry: Boolean,
    val canCancel: Boolean,
    val generation: DownloadGeneration,
    val updatedAtMillis: Long,
    samples: List<DownloadSample>,
    val verificationSucceeded: Boolean,
) {
    val samples: List<DownloadSample> = Collections.unmodifiableList(ArrayList(samples))

    init {
        require(downloadedBytes >= 0L)
        require(totalBytes == null || totalBytes > 0L)
        require(percent == null || percent in 0..100)
        require(bytesPerSecond == null || bytesPerSecond > 0L)
        require(etaSeconds == null || etaSeconds > 0L)
        if (phase == DownloadPhase.DOWNLOADING) {
            require(samples.isNotEmpty())
            require(samples.last().timestampMillis == updatedAtMillis)
            require(samples.last().downloadedBytes == downloadedBytes)
        }
        require(phase == DownloadPhase.DOWNLOADING || bytesPerSecond == null)
        require(phase == DownloadPhase.DOWNLOADING || etaSeconds == null)
        require(percent == derivedDownloadPercent(downloadedBytes, totalBytes))
        require(
            bytesPerSecond == if (phase == DownloadPhase.DOWNLOADING) {
                derivedDownloadSpeed(samples)
            } else {
                null
            },
        )
        require(
            etaSeconds == derivedDownloadEta(
                phase,
                downloadedBytes,
                totalBytes,
                bytesPerSecond,
            ),
        )
        require((phase == DownloadPhase.FAILED) == (failure != null))
        require((phase == DownloadPhase.READY) == verificationSucceeded)
        require(
            phase != DownloadPhase.READY || totalBytes == null || downloadedBytes >= totalBytes,
        )
        require((phase == DownloadPhase.WAITING || phase == DownloadPhase.PAUSED) ==
            (blockReason != null))
        require(blockReason == null || blockReason.phase == phase)
        require(!canRetry || phase in RETRYABLE_PHASES)
        require(phase != DownloadPhase.FAILED || canRetry)
        require(!canCancel || phase in CANCELLABLE_PHASES)
        require(updatedAtMillis >= 0L)
        require(this.samples.all { it.generation == generation })
        require(this.samples.all { it.timestampMillis <= updatedAtMillis })
        require(this.samples.all { it.downloadedBytes <= downloadedBytes })
        this.samples.zipWithNext().forEach { (earlier, later) ->
            require(later.timestampMillis > earlier.timestampMillis)
            require(later.downloadedBytes > earlier.downloadedBytes)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is DownloadTelemetry &&
            phase == other.phase &&
            downloadedBytes == other.downloadedBytes &&
            totalBytes == other.totalBytes &&
            percent == other.percent &&
            bytesPerSecond == other.bytesPerSecond &&
            etaSeconds == other.etaSeconds &&
            network == other.network &&
            blockReason == other.blockReason &&
            failure == other.failure &&
            canRetry == other.canRetry &&
            canCancel == other.canCancel &&
            generation == other.generation &&
            updatedAtMillis == other.updatedAtMillis &&
            samples == other.samples &&
            verificationSucceeded == other.verificationSucceeded

    override fun hashCode(): Int {
        var result = phase.hashCode()
        result = 31 * result + downloadedBytes.hashCode()
        result = 31 * result + (totalBytes?.hashCode() ?: 0)
        result = 31 * result + (percent ?: 0)
        result = 31 * result + (bytesPerSecond?.hashCode() ?: 0)
        result = 31 * result + (etaSeconds?.hashCode() ?: 0)
        result = 31 * result + network.hashCode()
        result = 31 * result + (blockReason?.hashCode() ?: 0)
        result = 31 * result + (failure?.hashCode() ?: 0)
        result = 31 * result + canRetry.hashCode()
        result = 31 * result + canCancel.hashCode()
        result = 31 * result + generation.hashCode()
        result = 31 * result + updatedAtMillis.hashCode()
        result = 31 * result + samples.hashCode()
        result = 31 * result + verificationSucceeded.hashCode()
        return result
    }

    companion object {
        internal fun create(
            phase: DownloadPhase,
            downloadedBytes: Long,
            totalBytes: Long?,
            percent: Int?,
            bytesPerSecond: Long?,
            etaSeconds: Long?,
            network: DownloadNetwork,
            blockReason: DownloadBlockReason?,
            failure: SafeDownloadFailure?,
            canRetry: Boolean,
            canCancel: Boolean,
            generation: DownloadGeneration,
            updatedAtMillis: Long,
            samples: List<DownloadSample>,
            verificationSucceeded: Boolean,
        ): DownloadTelemetry = DownloadTelemetry(
            phase = phase,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            percent = percent,
            bytesPerSecond = bytesPerSecond,
            etaSeconds = etaSeconds,
            network = network,
            blockReason = blockReason,
            failure = failure,
            canRetry = canRetry,
            canCancel = canCancel,
            generation = generation,
            updatedAtMillis = updatedAtMillis,
            samples = samples,
            verificationSucceeded = verificationSucceeded,
        )
    }
}

class DownloadTelemetryCalculator(
    private val clock: () -> Long,
    private val sampleWindowMillis: Long = DEFAULT_SAMPLE_WINDOW_MILLIS,
    private val maxSampleCount: Int = DEFAULT_MAX_SAMPLE_COUNT,
) {
    init {
        require(sampleWindowMillis > 0L) { "Sample window must be positive." }
        require(maxSampleCount >= 2) { "At least two samples are required." }
    }

    fun record(
        observation: DownloadObservation,
        previous: DownloadTelemetry? = null,
    ): DownloadTelemetry {
        if (
            previous != null &&
            observation.generation.value < previous.generation.value
        ) {
            return previous
        }

        val now = clock()
        require(now >= 0L) { "Download update time cannot be negative." }

        val downloaded = observation.downloadedBytes
        val samples = samplesFor(observation, previous, now, downloaded)
        return buildTelemetry(observation, now, samples)
    }

    internal fun restore(
        observation: DownloadObservation,
        updatedAtMillis: Long,
        restoredSamples: List<DownloadSample>,
    ): DownloadTelemetry {
        require(updatedAtMillis >= 0L) { "Download update time cannot be negative." }
        require(restoredSamples.all { it.generation == observation.generation }) {
            "Restored samples must match the work generation."
        }
        require(restoredSamples.all { it.timestampMillis <= updatedAtMillis }) {
            "Restored samples cannot come from the future."
        }
        require(restoredSamples.all { it.downloadedBytes <= observation.downloadedBytes }) {
            "Restored samples cannot exceed current progress."
        }
        restoredSamples.zipWithNext().forEach { (earlier, later) ->
            require(later.timestampMillis > earlier.timestampMillis) {
                "Restored sample times must increase."
            }
            require(later.downloadedBytes > earlier.downloadedBytes) {
                "Restored sample bytes must increase."
            }
        }
        if (observation.phase == DownloadPhase.DOWNLOADING && restoredSamples.isNotEmpty()) {
            require(restoredSamples.last().timestampMillis == updatedAtMillis) {
                "Active download samples must include the exact update time."
            }
            require(restoredSamples.last().downloadedBytes == observation.downloadedBytes) {
                "Active download samples must match current progress."
            }
        }

        val bounded = immutableBoundedList(
            restoredSamples.filter { sample ->
                updatedAtMillis - sample.timestampMillis < sampleWindowMillis
            },
        )
        val samples = if (
            observation.phase == DownloadPhase.DOWNLOADING && bounded.isEmpty()
        ) {
            immutableBoundedList(
                listOf(
                    DownloadSample(
                        observation.generation,
                        updatedAtMillis,
                        observation.downloadedBytes,
                    ),
                ),
            )
        } else {
            bounded
        }
        return buildTelemetry(observation, updatedAtMillis, samples)
    }

    private fun buildTelemetry(
        observation: DownloadObservation,
        updatedAtMillis: Long,
        samples: List<DownloadSample>,
    ): DownloadTelemetry {
        val downloaded = observation.downloadedBytes
        val total = observation.totalBytes
        val speed = if (observation.phase == DownloadPhase.DOWNLOADING) {
            derivedDownloadSpeed(samples)
        } else {
            null
        }
        val eta = derivedDownloadEta(observation.phase, downloaded, total, speed)
        val failure = observation.failureCode?.let(SafeDownloadFailure::from)
        val canRetry = observation.retryAllowed && observation.phase in RETRYABLE_PHASES
        val canCancel = observation.cancellationAllowed &&
            observation.phase in CANCELLABLE_PHASES

        return DownloadTelemetry.create(
            phase = observation.phase,
            downloadedBytes = downloaded,
            totalBytes = total,
            percent = derivedDownloadPercent(downloaded, total),
            bytesPerSecond = speed,
            etaSeconds = eta,
            network = observation.network,
            blockReason = observation.blockReason,
            failure = failure,
            canRetry = canRetry,
            canCancel = canCancel,
            generation = observation.generation,
            updatedAtMillis = updatedAtMillis,
            samples = samples,
            verificationSucceeded = observation.verificationSucceeded,
        )
    }

    private fun samplesFor(
        observation: DownloadObservation,
        previous: DownloadTelemetry?,
        now: Long,
        downloaded: Long,
    ): List<DownloadSample> {
        if (observation.phase != DownloadPhase.DOWNLOADING) {
            return retainedSamples(previous, observation.generation, now)
        }

        val canContinueWindow = previous != null &&
            previous.generation == observation.generation &&
            previous.phase == DownloadPhase.DOWNLOADING &&
            now > previous.updatedAtMillis &&
            downloaded > previous.downloadedBytes
        val baseline = if (canContinueWindow) {
            retainedSamples(previous, observation.generation, now)
        } else {
            emptyList()
        }
        val current = DownloadSample(observation.generation, now, downloaded)
        return immutableBoundedList(baseline + current)
    }

    private fun retainedSamples(
        previous: DownloadTelemetry?,
        generation: DownloadGeneration,
        now: Long,
    ): List<DownloadSample> {
        if (previous == null || previous.generation != generation) return emptyList()
        if (now < previous.updatedAtMillis) return emptyList()

        return immutableBoundedList(
            previous.samples.filter { sample ->
                sample.timestampMillis <= now &&
                    now - sample.timestampMillis < sampleWindowMillis
            },
        )
    }

    private fun immutableBoundedList(samples: List<DownloadSample>): List<DownloadSample> =
        Collections.unmodifiableList(ArrayList(samples.takeLast(maxSampleCount)))

    companion object {
        const val DEFAULT_SAMPLE_WINDOW_MILLIS = 15_000L
        const val DEFAULT_MAX_SAMPLE_COUNT = 8
    }
}

internal class DownloadTelemetryRestoreFactory(
    sampleWindowMillis: Long = DownloadTelemetryCalculator.DEFAULT_SAMPLE_WINDOW_MILLIS,
    maxSampleCount: Int = DownloadTelemetryCalculator.DEFAULT_MAX_SAMPLE_COUNT,
) {
    private val calculator = DownloadTelemetryCalculator(
        clock = { error("Restore supplies an exact update time.") },
        sampleWindowMillis = sampleWindowMillis,
        maxSampleCount = maxSampleCount,
    )

    fun restore(
        observation: DownloadObservation,
        updatedAtMillis: Long,
        samples: List<DownloadSample>,
    ): DownloadTelemetry = calculator.restore(
        observation = observation,
        updatedAtMillis = updatedAtMillis,
        restoredSamples = samples,
    )
}

private val CANCELLABLE_PHASES = setOf(
    DownloadPhase.PREPARING,
    DownloadPhase.DOWNLOADING,
    DownloadPhase.VERIFYING,
)

private val RETRYABLE_PHASES = setOf(
    DownloadPhase.PAUSED,
    DownloadPhase.FAILED,
)

private val ONE_HUNDRED = BigInteger.valueOf(100L)
private val ONE_THOUSAND = BigInteger.valueOf(1_000L)
private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)

private fun derivedDownloadSpeed(samples: List<DownloadSample>): Long? {
    if (samples.size < 2) return null
    val first = samples.first()
    val last = samples.last()
    val elapsedMillis = last.timestampMillis - first.timestampMillis
    val transferredBytes = last.downloadedBytes - first.downloadedBytes
    if (elapsedMillis <= 0L || transferredBytes <= 0L) return null

    val bytesPerSecond = BigInteger.valueOf(transferredBytes)
        .multiply(ONE_THOUSAND)
        .divide(BigInteger.valueOf(elapsedMillis))
    if (bytesPerSecond <= BigInteger.ZERO) return null
    return bytesPerSecond.coerceToLong()
}

private fun derivedDownloadEta(
    phase: DownloadPhase,
    downloaded: Long,
    total: Long?,
    speed: Long?,
): Long? {
    if (phase != DownloadPhase.DOWNLOADING || total == null || speed == null) return null
    if (downloaded >= total) return null

    val remaining = BigInteger.valueOf(total - downloaded)
    val divisor = BigInteger.valueOf(speed)
    val seconds = remaining
        .add(divisor.subtract(BigInteger.ONE))
        .divide(divisor)
    return seconds.takeIf { it > BigInteger.ZERO }?.coerceToLong()
}

private fun derivedDownloadPercent(downloaded: Long, total: Long?): Int? {
    if (total == null) return null
    val boundedDownloaded = downloaded.coerceAtMost(total)
    return BigInteger.valueOf(boundedDownloaded)
        .multiply(ONE_HUNDRED)
        .divide(BigInteger.valueOf(total))
        .toInt()
        .coerceIn(0, 100)
}

private fun BigInteger.coerceToLong(): Long =
    if (this > LONG_MAX) Long.MAX_VALUE else toLong()
