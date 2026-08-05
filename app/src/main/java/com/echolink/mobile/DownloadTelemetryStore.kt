package com.echolink.mobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

data class DownloadTargetKey(
    val family: AiModelFamily,
    val tier: AiModelTier,
    val backend: BackendKind,
) {
    init {
        val valid = when (family) {
            AiModelFamily.GEMMA ->
                tier == AiModelTier.FAST || tier == AiModelTier.THOROUGH
            AiModelFamily.LEGACY_QWEN -> tier in LEGACY_TIERS
        } && when (family) {
            AiModelFamily.GEMMA ->
                backend == BackendKind.LITERT_CPU || backend == BackendKind.LITERT_GPU
            AiModelFamily.LEGACY_QWEN -> backend == BackendKind.LEGACY_LLAMA_CPU
        }
        require(valid) { "The model family, tier, and backend do not form a valid target." }
    }

    private companion object {
        val LEGACY_TIERS = setOf(
            AiModelTier.LEGACY_COMPACT,
            AiModelTier.LEGACY_BALANCED,
            AiModelTier.LEGACY_ADVANCED,
        )
    }
}

data class DownloadWorkIdentity(
    val mostSignificantBits: Long,
    val leastSignificantBits: Long,
) {
    fun toUuid(): UUID = UUID(mostSignificantBits, leastSignificantBits)

    companion object {
        fun from(uuid: UUID): DownloadWorkIdentity = DownloadWorkIdentity(
            uuid.mostSignificantBits,
            uuid.leastSignificantBits,
        )
    }
}

data class DurableDownloadRecord(
    val target: DownloadTargetKey,
    val workIdentity: DownloadWorkIdentity,
    val telemetry: DownloadTelemetry,
) {
    init {
        DownloadTelemetryStore.validateSampleBound(telemetry.samples)
        require(telemetry.totalBytes == null || telemetry.downloadedBytes <= telemetry.totalBytes) {
            "Persisted progress cannot exceed the known download size."
        }
    }
}

fun interface DownloadReadyReconciler {
    fun isActiveAndSelfTested(
        target: DownloadTargetKey,
        generation: DownloadGeneration,
    ): Boolean
}

internal fun interface AtomicSnapshotCommitter {
    @Throws(IOException::class)
    fun commit(temporary: Path, destination: Path)
}

internal fun interface SnapshotReader {
    @Throws(IOException::class)
    fun read(file: File): ByteArray
}

internal object DownloadTelemetryWire {
    const val FAMILY_GEMMA = 1
    const val FAMILY_LEGACY_QWEN = 2

    const val TIER_FAST = 1
    const val TIER_THOROUGH = 2
    const val TIER_LEGACY_COMPACT = 101
    const val TIER_LEGACY_BALANCED = 102
    const val TIER_LEGACY_ADVANCED = 103

    const val BACKEND_LITERT_CPU = 1
    const val BACKEND_LITERT_GPU = 2
    const val BACKEND_LEGACY_LLAMA_CPU = 101

    const val PHASE_PREPARING = 1
    const val PHASE_DOWNLOADING = 2
    const val PHASE_VERIFYING = 3
    const val PHASE_READY = 4
    const val PHASE_WAITING = 5
    const val PHASE_PAUSED = 6
    const val PHASE_FAILED = 7

    const val NETWORK_UNKNOWN = 0
    const val NETWORK_OFFLINE = 1
    const val NETWORK_WIFI = 2
    const val NETWORK_MOBILE = 3
    const val NETWORK_ETHERNET = 4
    const val NETWORK_OTHER = 5

    const val BLOCK_NETWORK_UNAVAILABLE = 1
    const val BLOCK_WIFI_REQUIRED = 2
    const val BLOCK_USER_PAUSED = 3
    const val BLOCK_DEVICE_CONSTRAINT = 4

    const val FAILURE_NETWORK_UNAVAILABLE = 1
    const val FAILURE_STORAGE_UNAVAILABLE = 2
    const val FAILURE_INTEGRITY_CHECK_FAILED = 3
    const val FAILURE_SELF_TEST_FAILED = 4
    const val FAILURE_DOWNLOAD_INTERRUPTED = 5
    const val FAILURE_UNKNOWN = 6

    fun code(value: AiModelFamily): Int = when (value) {
        AiModelFamily.GEMMA -> FAMILY_GEMMA
        AiModelFamily.LEGACY_QWEN -> FAMILY_LEGACY_QWEN
    }

    fun code(value: AiModelTier): Int = when (value) {
        AiModelTier.FAST -> TIER_FAST
        AiModelTier.THOROUGH -> TIER_THOROUGH
        AiModelTier.LEGACY_COMPACT -> TIER_LEGACY_COMPACT
        AiModelTier.LEGACY_BALANCED -> TIER_LEGACY_BALANCED
        AiModelTier.LEGACY_ADVANCED -> TIER_LEGACY_ADVANCED
    }

    fun code(value: BackendKind): Int = when (value) {
        BackendKind.LITERT_CPU -> BACKEND_LITERT_CPU
        BackendKind.LITERT_GPU -> BACKEND_LITERT_GPU
        BackendKind.LEGACY_LLAMA_CPU -> BACKEND_LEGACY_LLAMA_CPU
    }

    fun code(value: DownloadPhase): Int = when (value) {
        DownloadPhase.PREPARING -> PHASE_PREPARING
        DownloadPhase.DOWNLOADING -> PHASE_DOWNLOADING
        DownloadPhase.VERIFYING -> PHASE_VERIFYING
        DownloadPhase.READY -> PHASE_READY
        DownloadPhase.WAITING -> PHASE_WAITING
        DownloadPhase.PAUSED -> PHASE_PAUSED
        DownloadPhase.FAILED -> PHASE_FAILED
    }

    fun code(value: DownloadNetwork): Int = when (value) {
        DownloadNetwork.UNKNOWN -> NETWORK_UNKNOWN
        DownloadNetwork.OFFLINE -> NETWORK_OFFLINE
        DownloadNetwork.WIFI -> NETWORK_WIFI
        DownloadNetwork.MOBILE -> NETWORK_MOBILE
        DownloadNetwork.ETHERNET -> NETWORK_ETHERNET
        DownloadNetwork.OTHER -> NETWORK_OTHER
    }

    fun code(value: DownloadBlockReason): Int = when (value) {
        DownloadBlockReason.NETWORK_UNAVAILABLE -> BLOCK_NETWORK_UNAVAILABLE
        DownloadBlockReason.WIFI_REQUIRED -> BLOCK_WIFI_REQUIRED
        DownloadBlockReason.USER_PAUSED -> BLOCK_USER_PAUSED
        DownloadBlockReason.DEVICE_CONSTRAINT -> BLOCK_DEVICE_CONSTRAINT
    }

    fun code(value: DownloadFailureCode): Int = when (value) {
        DownloadFailureCode.NETWORK_UNAVAILABLE -> FAILURE_NETWORK_UNAVAILABLE
        DownloadFailureCode.STORAGE_UNAVAILABLE -> FAILURE_STORAGE_UNAVAILABLE
        DownloadFailureCode.INTEGRITY_CHECK_FAILED -> FAILURE_INTEGRITY_CHECK_FAILED
        DownloadFailureCode.SELF_TEST_FAILED -> FAILURE_SELF_TEST_FAILED
        DownloadFailureCode.DOWNLOAD_INTERRUPTED -> FAILURE_DOWNLOAD_INTERRUPTED
        DownloadFailureCode.UNKNOWN -> FAILURE_UNKNOWN
    }

    fun family(code: Int): AiModelFamily? = when (code) {
        FAMILY_GEMMA -> AiModelFamily.GEMMA
        FAMILY_LEGACY_QWEN -> AiModelFamily.LEGACY_QWEN
        else -> null
    }

    fun tier(code: Int): AiModelTier? = when (code) {
        TIER_FAST -> AiModelTier.FAST
        TIER_THOROUGH -> AiModelTier.THOROUGH
        TIER_LEGACY_COMPACT -> AiModelTier.LEGACY_COMPACT
        TIER_LEGACY_BALANCED -> AiModelTier.LEGACY_BALANCED
        TIER_LEGACY_ADVANCED -> AiModelTier.LEGACY_ADVANCED
        else -> null
    }

    fun backend(code: Int): BackendKind? = when (code) {
        BACKEND_LITERT_CPU -> BackendKind.LITERT_CPU
        BACKEND_LITERT_GPU -> BackendKind.LITERT_GPU
        BACKEND_LEGACY_LLAMA_CPU -> BackendKind.LEGACY_LLAMA_CPU
        else -> null
    }

    fun phase(code: Int): DownloadPhase? = when (code) {
        PHASE_PREPARING -> DownloadPhase.PREPARING
        PHASE_DOWNLOADING -> DownloadPhase.DOWNLOADING
        PHASE_VERIFYING -> DownloadPhase.VERIFYING
        PHASE_READY -> DownloadPhase.READY
        PHASE_WAITING -> DownloadPhase.WAITING
        PHASE_PAUSED -> DownloadPhase.PAUSED
        PHASE_FAILED -> DownloadPhase.FAILED
        else -> null
    }

    fun network(code: Int): DownloadNetwork? = when (code) {
        NETWORK_UNKNOWN -> DownloadNetwork.UNKNOWN
        NETWORK_OFFLINE -> DownloadNetwork.OFFLINE
        NETWORK_WIFI -> DownloadNetwork.WIFI
        NETWORK_MOBILE -> DownloadNetwork.MOBILE
        NETWORK_ETHERNET -> DownloadNetwork.ETHERNET
        NETWORK_OTHER -> DownloadNetwork.OTHER
        else -> null
    }

    fun blockReason(code: Int): DownloadBlockReason? = when (code) {
        BLOCK_NETWORK_UNAVAILABLE -> DownloadBlockReason.NETWORK_UNAVAILABLE
        BLOCK_WIFI_REQUIRED -> DownloadBlockReason.WIFI_REQUIRED
        BLOCK_USER_PAUSED -> DownloadBlockReason.USER_PAUSED
        BLOCK_DEVICE_CONSTRAINT -> DownloadBlockReason.DEVICE_CONSTRAINT
        else -> null
    }

    fun failure(code: Int): DownloadFailureCode? = when (code) {
        FAILURE_NETWORK_UNAVAILABLE -> DownloadFailureCode.NETWORK_UNAVAILABLE
        FAILURE_STORAGE_UNAVAILABLE -> DownloadFailureCode.STORAGE_UNAVAILABLE
        FAILURE_INTEGRITY_CHECK_FAILED -> DownloadFailureCode.INTEGRITY_CHECK_FAILED
        FAILURE_SELF_TEST_FAILED -> DownloadFailureCode.SELF_TEST_FAILED
        FAILURE_DOWNLOAD_INTERRUPTED -> DownloadFailureCode.DOWNLOAD_INTERRUPTED
        FAILURE_UNKNOWN -> DownloadFailureCode.UNKNOWN
        else -> null
    }

    fun targetToken(target: DownloadTargetKey): String =
        "f${code(target.family)}-t${code(target.tier)}-b${code(target.backend)}"
}

internal object DownloadTelemetryCodec {
    const val MAGIC: Int = 0x4D563254
    const val SCHEMA_VERSION: Int = 1
    const val MAX_RECORD_BYTES: Int = 16 * 1024

    private const val CHECKSUM_BYTES = java.lang.Long.BYTES
    private const val MIN_RECORD_BYTES = 80

    fun encode(record: DurableDownloadRecord): ByteArray {
        DownloadTelemetryStore.validateSampleBound(record.telemetry.samples)
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(SCHEMA_VERSION)
                output.writeInt(DownloadTelemetryWire.code(record.target.family))
                output.writeInt(DownloadTelemetryWire.code(record.target.tier))
                output.writeInt(DownloadTelemetryWire.code(record.target.backend))
                output.writeLong(record.workIdentity.mostSignificantBits)
                output.writeLong(record.workIdentity.leastSignificantBits)
                output.writeInt(DownloadTelemetryWire.code(record.telemetry.phase))
                output.writeLong(record.telemetry.downloadedBytes)
                writeOptionalLong(output, record.telemetry.totalBytes)
                output.writeInt(DownloadTelemetryWire.code(record.telemetry.network))
                output.writeInt(
                    record.telemetry.blockReason?.let(DownloadTelemetryWire::code) ?: ABSENT_ENUM,
                )
                output.writeInt(
                    record.telemetry.failure?.code?.let(DownloadTelemetryWire::code) ?: ABSENT_ENUM,
                )
                writeBoolean(output, record.telemetry.canRetry)
                writeBoolean(output, record.telemetry.canCancel)
                output.writeLong(record.telemetry.generation.value)
                output.writeLong(record.telemetry.updatedAtMillis)
                writeBoolean(output, record.telemetry.verificationSucceeded)
                output.writeInt(record.telemetry.samples.size)
                record.telemetry.samples.forEach { sample ->
                    output.writeLong(sample.generation.value)
                    output.writeLong(sample.timestampMillis)
                    output.writeLong(sample.downloadedBytes)
                }
            }
        }.toByteArray()
        val encoded = appendChecksum(payload)
        require(encoded.size <= MAX_RECORD_BYTES) { "Download telemetry record is too large." }
        return encoded
    }

    fun decode(encoded: ByteArray): DurableDownloadRecord? {
        if (encoded.size !in MIN_RECORD_BYTES..MAX_RECORD_BYTES) return null
        val payloadSize = encoded.size - CHECKSUM_BYTES
        if (payloadSize <= 0 || !hasValidChecksum(encoded, payloadSize)) return null

        return try {
            DataInputStream(ByteArrayInputStream(encoded, 0, payloadSize)).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() != SCHEMA_VERSION) return null
                val target = DownloadTargetKey(
                    family = DownloadTelemetryWire.family(input.readInt()) ?: return null,
                    tier = DownloadTelemetryWire.tier(input.readInt()) ?: return null,
                    backend = DownloadTelemetryWire.backend(input.readInt()) ?: return null,
                )
                val workIdentity = DownloadWorkIdentity(input.readLong(), input.readLong())
                val phase = DownloadTelemetryWire.phase(input.readInt()) ?: return null
                val downloadedBytes = input.readLong()
                val totalBytes = input.readOptionalLong() ?: return null
                val network = DownloadTelemetryWire.network(input.readInt()) ?: return null
                val blockReason = input.readOptionalValue(DownloadTelemetryWire::blockReason)
                    ?: return null
                val failureCode = input.readOptionalValue(DownloadTelemetryWire::failure)
                    ?: return null
                val canRetry = input.readStrictBoolean() ?: return null
                val canCancel = input.readStrictBoolean() ?: return null
                val generation = DownloadGeneration(input.readLong())
                val updatedAtMillis = input.readLong()
                val verificationSucceeded = input.readStrictBoolean() ?: return null
                val sampleCount = input.readInt()
                if (sampleCount !in 0..DownloadTelemetryCalculator.DEFAULT_MAX_SAMPLE_COUNT) {
                    return null
                }
                val samples = ArrayList<DownloadSample>(sampleCount)
                repeat(sampleCount) {
                    samples += DownloadSample(
                        generation = DownloadGeneration(input.readLong()),
                        timestampMillis = input.readLong(),
                        downloadedBytes = input.readLong(),
                    )
                }
                if (input.available() != 0) return null
                if (downloadedBytes < 0L || totalBytes.value != null && downloadedBytes > totalBytes.value) {
                    return null
                }
                val observation = DownloadObservation(
                    generation = generation,
                    phase = phase,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes.value,
                    network = network,
                    blockReason = blockReason.value,
                    failureCode = failureCode.value,
                    verificationSucceeded = verificationSucceeded,
                    cancellationAllowed = canCancel,
                    retryAllowed = canRetry,
                )
                DurableDownloadRecord(
                    target = target,
                    workIdentity = workIdentity,
                    telemetry = DownloadTelemetryRestoreFactory().restore(
                        observation = observation,
                        updatedAtMillis = updatedAtMillis,
                        samples = samples,
                    ),
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    private fun writeOptionalLong(output: DataOutputStream, value: Long?) {
        writeBoolean(output, value != null)
        if (value != null) output.writeLong(value)
    }

    private fun writeBoolean(output: DataOutputStream, value: Boolean) {
        output.writeByte(if (value) TRUE_BYTE else FALSE_BYTE)
    }

    private fun appendChecksum(payload: ByteArray): ByteArray =
        ByteArrayOutputStream(payload.size + CHECKSUM_BYTES).also { bytes ->
            bytes.write(payload)
            DataOutputStream(bytes).use { output ->
                output.writeLong(CRC32().apply { update(payload) }.value)
            }
        }.toByteArray()

    private fun hasValidChecksum(encoded: ByteArray, payloadSize: Int): Boolean {
        val expected = DataInputStream(
            ByteArrayInputStream(encoded, payloadSize, CHECKSUM_BYTES),
        ).use { it.readLong() }
        val actual = CRC32().apply { update(encoded, 0, payloadSize) }.value
        return expected == actual
    }

    private fun DataInputStream.readStrictBoolean(): Boolean? = when (readUnsignedByte()) {
        FALSE_BYTE -> false
        TRUE_BYTE -> true
        else -> null
    }

    private fun DataInputStream.readOptionalLong(): DecodedOptional<Long>? =
        when (readUnsignedByte()) {
            FALSE_BYTE -> DecodedOptional(null)
            TRUE_BYTE -> DecodedOptional(readLong())
            else -> null
        }

    private fun <T> DataInputStream.readOptionalValue(decoder: (Int) -> T?): DecodedOptional<T>? {
        val code = readInt()
        if (code == ABSENT_ENUM) return DecodedOptional(null)
        return decoder(code)?.let(::DecodedOptional)
    }

    private data class DecodedOptional<T>(val value: T?)

    private const val ABSENT_ENUM = -1
    private const val FALSE_BYTE = 0
    private const val TRUE_BYTE = 1
}

internal class DownloadTelemetryRecoveryPolicy(
    private val clock: () -> Long,
    private val readyReconciler: DownloadReadyReconciler,
) {
    fun recover(record: DurableDownloadRecord): DurableDownloadRecord? {
        val now = clock()
        require(now >= 0L) { "Recovery time cannot be negative." }
        if (record.telemetry.updatedAtMillis > safelyAdd(now, FUTURE_TOLERANCE_MILLIS)) {
            return null
        }
        val activeAndSelfTested = when (record.telemetry.phase) {
            DownloadPhase.VERIFYING,
            DownloadPhase.READY,
            -> reconcileReady(record)
            else -> false
        }
        if (record.telemetry.phase == DownloadPhase.VERIFYING && activeAndSelfTested) {
            return readyAfterRecoveredActivation(record, now)
        }
        val stale = record.telemetry.updatedAtMillis <= now &&
            now - record.telemetry.updatedAtMillis > ACTIVE_STALE_MILLIS
        if (stale && record.telemetry.phase in STALE_SENSITIVE_PHASES) {
            return pausedForRecovery(record, now)
        }
        if (record.telemetry.phase == DownloadPhase.READY && !activeAndSelfTested) {
            return pausedForRecovery(record, now)
        }
        return record
    }

    private fun reconcileReady(record: DurableDownloadRecord): Boolean = try {
        readyReconciler.isActiveAndSelfTested(
            record.target,
            record.telemetry.generation,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        false
    }

    private fun readyAfterRecoveredActivation(
        record: DurableDownloadRecord,
        now: Long,
    ): DurableDownloadRecord {
        val downloadedBytes = record.telemetry.totalBytes?.let { total ->
            maxOf(record.telemetry.downloadedBytes, total)
        } ?: record.telemetry.downloadedBytes
        return DurableDownloadRecord(
            target = record.target,
            workIdentity = record.workIdentity,
            telemetry = DownloadTelemetryRestoreFactory().restore(
                observation = DownloadObservation(
                    generation = record.telemetry.generation,
                    phase = DownloadPhase.READY,
                    downloadedBytes = downloadedBytes,
                    totalBytes = record.telemetry.totalBytes,
                    network = record.telemetry.network,
                    verificationSucceeded = true,
                    cancellationAllowed = false,
                    retryAllowed = false,
                ),
                updatedAtMillis = now,
                samples = emptyList(),
            ),
        )
    }

    private fun pausedForRecovery(
        record: DurableDownloadRecord,
        now: Long,
    ): DurableDownloadRecord = DurableDownloadRecord(
        target = record.target,
        workIdentity = record.workIdentity,
        telemetry = DownloadTelemetryRestoreFactory().restore(
            observation = DownloadObservation(
                generation = record.telemetry.generation,
                phase = DownloadPhase.PAUSED,
                downloadedBytes = record.telemetry.downloadedBytes,
                totalBytes = record.telemetry.totalBytes,
                network = record.telemetry.network,
                blockReason = DownloadBlockReason.DEVICE_CONSTRAINT,
                cancellationAllowed = false,
                retryAllowed = true,
            ),
            updatedAtMillis = now,
            samples = emptyList(),
        ),
    )

    companion object {
        const val ACTIVE_STALE_MILLIS: Long = 7L * 24L * 60L * 60L * 1_000L
        const val FUTURE_TOLERANCE_MILLIS: Long = 5L * 60L * 1_000L

        private val STALE_SENSITIVE_PHASES = setOf(
            DownloadPhase.PREPARING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.VERIFYING,
            DownloadPhase.READY,
        )

        private fun safelyAdd(value: Long, increment: Long): Long =
            if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
    }
}

internal class DownloadTelemetryStore(
    private val directory: File,
    clock: () -> Long,
    readyReconciler: DownloadReadyReconciler,
    private val committer: AtomicSnapshotCommitter = DEFAULT_COMMITTER,
    private val snapshotReader: SnapshotReader = DEFAULT_READER,
) {
    private val clock = clock
    private val recoveryPolicy = DownloadTelemetryRecoveryPolicy(clock, readyReconciler)

    init {
        require(directory.isDirectory || directory.mkdirs()) {
            "Download telemetry storage is unavailable."
        }
    }

    fun read(target: DownloadTargetKey): DurableDownloadRecord? = synchronized(PROCESS_LOCK) {
        val fence = readGenerationCounter(target)
        if (fence !is GenerationFence.Present) return@synchronized null
        val exact = readExact(target) ?: return@synchronized null
        if (exact.telemetry.generation.value != fence.generation) return@synchronized null
        val recovered = recoveryPolicy.recover(exact) ?: return@synchronized null
        if (recovered != exact) {
            try {
                writeSnapshot(recovered)
            } catch (_: IOException) {
                return@synchronized null
            } catch (_: SecurityException) {
                return@synchronized null
            }
        }
        recovered
    }

    @Throws(IOException::class)
    fun write(record: DurableDownloadRecord): Boolean = synchronized(PROCESS_LOCK) {
        validateSampleBound(record.telemetry.samples)
        val fence = readGenerationCounter(record.target)
        if (
            fence !is GenerationFence.Present ||
            record.telemetry.generation.value != fence.generation
        ) {
            return@synchronized false
        }
        val current = readExact(record.target) ?: return@synchronized false
        if (current.telemetry.generation.value != fence.generation) return@synchronized false
        if (!canReplace(current, record)) return@synchronized false
        writeSnapshot(record)
        true
    }

    @Throws(IOException::class)
    fun reserveNextGeneration(
        target: DownloadTargetKey,
        workIdentity: DownloadWorkIdentity,
        network: DownloadNetwork,
        downloadedBytes: Long,
        totalBytes: Long?,
    ): DurableDownloadRecord = synchronized(PROCESS_LOCK) {
        require(downloadedBytes >= 0L) { "Resume offset cannot be negative." }
        require(totalBytes == null || totalBytes > 0L) { "Known total bytes must be positive." }
        require(totalBytes == null || downloadedBytes <= totalBytes) {
            "Resume offset cannot exceed the known download size."
        }
        val currentGeneration = readExact(target)?.telemetry?.generation?.value ?: 0L
        val durableGeneration = when (val fence = readGenerationCounter(target)) {
            GenerationFence.Missing -> 0L
            GenerationFence.Corrupt -> error("Download generation storage is corrupted.")
            is GenerationFence.Present -> fence.generation
        }
        val lastGeneration = maxOf(currentGeneration, durableGeneration)
        check(lastGeneration < Long.MAX_VALUE) { "Download generation is exhausted." }
        val generation = DownloadGeneration(lastGeneration + 1L)
        val now = clock()
        require(now >= 0L) { "Download reservation time cannot be negative." }
        val record = DurableDownloadRecord(
            target = target,
            workIdentity = workIdentity,
            telemetry = DownloadTelemetryRestoreFactory().restore(
                observation = DownloadObservation(
                    generation = generation,
                    phase = DownloadPhase.PREPARING,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    network = network,
                    cancellationAllowed = true,
                ),
                updatedAtMillis = now,
                samples = emptyList(),
            ),
        )
        updateGenerationCounter(target, generation.value)
        writeSnapshot(record)
        record
    }

    @Throws(IOException::class)
    fun clearRecord(target: DownloadTargetKey) = synchronized(PROCESS_LOCK) {
        repairCorruptGenerationBeforeClear(target)
        val record = recordFile(directory, target)
        val temporary = File(record.parentFile, "${record.name}.tmp")
        listOf(record, temporary).forEach { file ->
            if (file.exists() && !file.delete()) {
                throw IOException("Download telemetry could not be cleared.")
            }
        }
    }

    private fun repairCorruptGenerationBeforeClear(target: DownloadTargetKey) {
        if (readGenerationCounter(target) != GenerationFence.Corrupt) return
        val generation = readExact(target)?.telemetry?.generation?.value
        val destination = generationFile(directory, target)
        if (generation != null) {
            writeAtomic(
                destination,
                DownloadGenerationCounterCodec.encode(target, generation),
            )
            return
        }
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        listOf(destination, temporary).forEach { file ->
            if (file.exists() && !file.delete()) {
                throw IOException("Download generation storage could not be repaired.")
            }
        }
    }

    private fun readExact(target: DownloadTargetKey): DurableDownloadRecord? {
        val file = recordFile(directory, target)
        if (!file.isFile || file.length() !in 1..DownloadTelemetryCodec.MAX_RECORD_BYTES.toLong()) {
            return null
        }
        val encoded = try {
            snapshotReader.read(file)
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        val record = DownloadTelemetryCodec.decode(encoded) ?: return null
        return record.takeIf { it.target == target }
    }

    private fun updateGenerationCounter(target: DownloadTargetKey, generation: Long) {
        val current = when (val fence = readGenerationCounter(target)) {
            GenerationFence.Missing -> 0L
            GenerationFence.Corrupt -> error("Download generation storage is corrupted.")
            is GenerationFence.Present -> fence.generation
        }
        if (generation <= current) return
        writeAtomic(
            generationFile(directory, target),
            DownloadGenerationCounterCodec.encode(target, generation),
        )
    }

    private fun readGenerationCounter(target: DownloadTargetKey): GenerationFence {
        val file = generationFile(directory, target)
        if (!file.exists()) return GenerationFence.Missing
        if (!file.isFile || file.length() !in 1..DownloadGenerationCounterCodec.MAX_BYTES.toLong()) {
            return GenerationFence.Corrupt
        }
        val encoded = try {
            snapshotReader.read(file)
        } catch (_: IOException) {
            return GenerationFence.Corrupt
        } catch (_: SecurityException) {
            return GenerationFence.Corrupt
        }
        val generation = DownloadGenerationCounterCodec.decode(encoded, target)
            ?: return GenerationFence.Corrupt
        return GenerationFence.Present(generation)
    }

    private fun writeSnapshot(record: DurableDownloadRecord) {
        writeAtomic(recordFile(directory, record.target), DownloadTelemetryCodec.encode(record))
    }

    private fun writeAtomic(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            committer.commit(temporary.toPath(), destination.toPath())
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun canReplace(
        current: DurableDownloadRecord,
        next: DurableDownloadRecord,
    ): Boolean {
        val currentGeneration = current.telemetry.generation.value
        val nextGeneration = next.telemetry.generation.value
        if (nextGeneration < currentGeneration) return false
        if (nextGeneration > currentGeneration) return true
        if (current.workIdentity != next.workIdentity) return false
        if (current == next) return true
        if (current.telemetry.phase in SEALED_PHASES) return false
        if (next.telemetry.updatedAtMillis < current.telemetry.updatedAtMillis) return false
        if (
            next.telemetry.downloadedBytes < current.telemetry.downloadedBytes &&
            !isExactZeroByteRestart(current, next)
        ) {
            return false
        }
        if (
            current.telemetry.totalBytes != null &&
            current.telemetry.totalBytes != next.telemetry.totalBytes
        ) {
            return false
        }
        return next.telemetry.phase in ALLOWED_TRANSITIONS.getValue(current.telemetry.phase)
    }

    private fun isExactZeroByteRestart(
        current: DurableDownloadRecord,
        next: DurableDownloadRecord,
    ): Boolean =
        current.telemetry.phase in RESTARTABLE_PHASES &&
            current.telemetry.downloadedBytes > 0L &&
            next.telemetry.phase == DownloadPhase.DOWNLOADING &&
            next.telemetry.downloadedBytes == 0L &&
            next.telemetry.totalBytes == current.telemetry.totalBytes &&
            next.telemetry.samples.singleOrNull()?.downloadedBytes == 0L

    companion object {
        private val PROCESS_LOCK = Any()
        private val SEALED_PHASES = setOf(
            DownloadPhase.READY,
            DownloadPhase.PAUSED,
            DownloadPhase.FAILED,
        )
        private val RESTARTABLE_PHASES = setOf(
            DownloadPhase.PREPARING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.WAITING,
        )
        private val ALLOWED_TRANSITIONS = mapOf(
            DownloadPhase.PREPARING to setOf(
                DownloadPhase.PREPARING,
                DownloadPhase.DOWNLOADING,
                DownloadPhase.VERIFYING,
                DownloadPhase.WAITING,
                DownloadPhase.PAUSED,
                DownloadPhase.FAILED,
            ),
            DownloadPhase.DOWNLOADING to setOf(
                DownloadPhase.DOWNLOADING,
                DownloadPhase.VERIFYING,
                DownloadPhase.WAITING,
                DownloadPhase.PAUSED,
                DownloadPhase.FAILED,
            ),
            DownloadPhase.VERIFYING to setOf(
                DownloadPhase.VERIFYING,
                DownloadPhase.READY,
                DownloadPhase.PAUSED,
                DownloadPhase.FAILED,
            ),
            DownloadPhase.WAITING to setOf(
                DownloadPhase.WAITING,
                DownloadPhase.PREPARING,
                DownloadPhase.DOWNLOADING,
                DownloadPhase.PAUSED,
                DownloadPhase.FAILED,
            ),
            DownloadPhase.READY to emptySet(),
            DownloadPhase.PAUSED to emptySet(),
            DownloadPhase.FAILED to emptySet(),
        )

        internal fun validateSampleBound(samples: List<DownloadSample>) {
            require(samples.size <= DownloadTelemetryCalculator.DEFAULT_MAX_SAMPLE_COUNT) {
                "Persisted download sample history is too large."
            }
        }

        internal fun recordFile(directory: File, target: DownloadTargetKey): File =
            File(directory, "telemetry-${target.fileToken()}.bin")

        internal fun generationFile(directory: File, target: DownloadTargetKey): File =
            File(directory, "generation-${target.fileToken()}.gen")

        private fun DownloadTargetKey.fileToken(): String =
            DownloadTelemetryWire.targetToken(this)

        private val DEFAULT_COMMITTER = AtomicSnapshotCommitter { temporary, destination ->
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        private val DEFAULT_READER = SnapshotReader(File::readBytes)
    }

    private sealed interface GenerationFence {
        data object Missing : GenerationFence
        data object Corrupt : GenerationFence
        data class Present(val generation: Long) : GenerationFence
    }
}

internal object DownloadGenerationCounterCodec {
    const val MAX_BYTES = 64
    private const val MAGIC = 0x4D563247
    private const val SCHEMA_VERSION = 1
    private const val CHECKSUM_BYTES = java.lang.Long.BYTES

    fun encode(target: DownloadTargetKey, generation: Long): ByteArray {
        require(generation > 0L)
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(SCHEMA_VERSION)
                output.writeInt(DownloadTelemetryWire.code(target.family))
                output.writeInt(DownloadTelemetryWire.code(target.tier))
                output.writeInt(DownloadTelemetryWire.code(target.backend))
                output.writeLong(generation)
            }
        }.toByteArray()
        val checksum = CRC32().apply { update(payload) }.value
        return ByteArrayOutputStream().also { bytes ->
            bytes.write(payload)
            DataOutputStream(bytes).use { it.writeLong(checksum) }
        }.toByteArray()
    }

    fun decode(encoded: ByteArray, expectedTarget: DownloadTargetKey): Long? {
        if (encoded.size !in 1..MAX_BYTES || encoded.size <= CHECKSUM_BYTES) return null
        val payloadSize = encoded.size - CHECKSUM_BYTES
        val expectedChecksum = DataInputStream(
            ByteArrayInputStream(encoded, payloadSize, CHECKSUM_BYTES),
        ).use { it.readLong() }
        val actualChecksum = CRC32().apply { update(encoded, 0, payloadSize) }.value
        if (expectedChecksum != actualChecksum) return null
        return try {
            DataInputStream(ByteArrayInputStream(encoded, 0, payloadSize)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != SCHEMA_VERSION) return null
                val family = DownloadTelemetryWire.family(input.readInt()) ?: return null
                val tier = DownloadTelemetryWire.tier(input.readInt()) ?: return null
                val backend = DownloadTelemetryWire.backend(input.readInt()) ?: return null
                val target = DownloadTargetKey(family, tier, backend)
                val generation = input.readLong()
                if (input.available() != 0 || target != expectedTarget || generation <= 0L) {
                    null
                } else {
                    generation
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }
}
