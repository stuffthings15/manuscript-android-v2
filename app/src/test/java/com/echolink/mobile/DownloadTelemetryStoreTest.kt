package com.echolink.mobile

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadTelemetryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var nowMillis = 1_000_000_000L
    private val target = DownloadTargetKey(
        family = AiModelFamily.GEMMA,
        tier = AiModelTier.FAST,
        backend = BackendKind.LITERT_CPU,
    )
    private val otherTarget = DownloadTargetKey(
        family = AiModelFamily.LEGACY_QWEN,
        tier = AiModelTier.LEGACY_COMPACT,
        backend = BackendKind.LEGACY_LLAMA_CPU,
    )
    private val work = DownloadWorkIdentity.from(UUID.fromString("4ba4d997-44a5-44a1-a1f0-55b6314eb55d"))

    @Test
    fun everyPhaseAndEveryPersistedFieldRoundTripsAcrossFreshStoreInstances() {
        DownloadPhase.entries.forEachIndexed { index, phase ->
            val directory = temporaryFolder.newFolder("phase-$phase")
            val expected = record(
                phase = phase,
                generation = (index + 1).toLong(),
                updatedAtMillis = nowMillis,
            )
            seedRecord(directory, expected)

            val restored = store(directory, ready = phase == DownloadPhase.READY).read(target)
            assertEquals(expected, restored)
            assertEquals(expected.telemetry.samples, restored?.telemetry?.samples)
            assertEquals(expected.workIdentity, restored?.workIdentity)
        }
    }

    @Test
    fun targetIdentityIsTypedAndEveryFamilyTierBackendCombinationIsValidated() {
        val valid = setOf(
            Triple(AiModelFamily.GEMMA, AiModelTier.FAST, BackendKind.LITERT_CPU),
            Triple(AiModelFamily.GEMMA, AiModelTier.FAST, BackendKind.LITERT_GPU),
            Triple(AiModelFamily.GEMMA, AiModelTier.THOROUGH, BackendKind.LITERT_CPU),
            Triple(AiModelFamily.GEMMA, AiModelTier.THOROUGH, BackendKind.LITERT_GPU),
            Triple(
                AiModelFamily.LEGACY_QWEN,
                AiModelTier.LEGACY_COMPACT,
                BackendKind.LEGACY_LLAMA_CPU,
            ),
            Triple(
                AiModelFamily.LEGACY_QWEN,
                AiModelTier.LEGACY_BALANCED,
                BackendKind.LEGACY_LLAMA_CPU,
            ),
            Triple(
                AiModelFamily.LEGACY_QWEN,
                AiModelTier.LEGACY_ADVANCED,
                BackendKind.LEGACY_LLAMA_CPU,
            ),
        )

        AiModelFamily.entries.forEach { family ->
            AiModelTier.entries.forEach { tier ->
                BackendKind.entries.forEach { backend ->
                    val combination = Triple(family, tier, backend)
                    if (combination in valid) {
                        assertEquals(
                            combination,
                            DownloadTargetKey(family, tier, backend).let {
                                Triple(it.family, it.tier, it.backend)
                            },
                        )
                    } else {
                        assertThrows(IllegalArgumentException::class.java) {
                            DownloadTargetKey(family, tier, backend)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun schemaOneWireCodesAndTargetFileNamesAreExplicitAndStable() {
        assertEquals(1, DownloadTelemetryCodec.SCHEMA_VERSION)
        assertEquals(
            listOf(1, 2),
            AiModelFamily.entries.map(DownloadTelemetryWire::code),
        )
        assertEquals(
            listOf(1, 2, 101, 102, 103),
            AiModelTier.entries.map(DownloadTelemetryWire::code),
        )
        assertEquals(
            listOf(1, 2, 101),
            BackendKind.entries.map(DownloadTelemetryWire::code),
        )
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6, 7),
            DownloadPhase.entries.map(DownloadTelemetryWire::code),
        )
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5),
            DownloadNetwork.entries.map(DownloadTelemetryWire::code),
        )
        assertEquals(
            listOf(1, 2, 3, 4),
            DownloadBlockReason.entries.map(DownloadTelemetryWire::code),
        )
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6),
            DownloadFailureCode.entries.map(DownloadTelemetryWire::code),
        )
        assertEquals(
            "telemetry-f1-t1-b1.bin",
            DownloadTelemetryStore.recordFile(temporaryFolder.root, target).name,
        )
        assertEquals(
            "generation-f2-t101-b101.gen",
            DownloadTelemetryStore.generationFile(temporaryFolder.root, otherTarget).name,
        )
    }

    @Test
    fun gemmaAndLegacyRecordsCannotOverwriteEachOther() {
        val directory = temporaryFolder.newFolder("families")
        val gemma = record(DownloadPhase.DOWNLOADING, generation = 3L)
        val legacy = record(
            phase = DownloadPhase.WAITING,
            generation = 9L,
            target = otherTarget,
            work = DownloadWorkIdentity.from(
                UUID.fromString("1cd44677-0268-4310-9813-f9fd6d324712"),
            ),
        )
        val store = store(directory, ready = true)

        seedRecord(directory, gemma)
        seedRecord(directory, legacy)

        assertEquals(gemma, store.read(target))
        assertEquals(legacy, store.read(otherTarget))
        assertEquals(2, directory.listFiles().orEmpty().count { it.extension == "bin" })
    }

    @Test
    fun staleGenerationAndWrongWorkIdentityCannotOverwriteCurrentState() {
        val directory = temporaryFolder.newFolder("stale-writes")
        val current = record(DownloadPhase.DOWNLOADING, generation = 4L)
        val stale = record(DownloadPhase.FAILED, generation = 3L)
        val impostor = record(
            phase = DownloadPhase.FAILED,
            generation = 4L,
            work = DownloadWorkIdentity.from(
                UUID.fromString("be48ffdf-c3f4-4d11-bdaa-c2f79f858f14"),
            ),
        )
        val store = store(directory, ready = true)

        seedRecord(directory, current)
        assertFalse(store.write(stale))
        assertFalse(store.write(impostor))
        assertEquals(current, store.read(target))
    }

    @Test
    fun missingOrCorruptSnapshotCannotBeRecreatedByOldOrOrphanCallbacks() {
        val directory = temporaryFolder.newFolder("fenced-callbacks")
        val current = record(DownloadPhase.DOWNLOADING, generation = 50L)
        seedRecord(directory, current)
        DownloadTelemetryStore.recordFile(directory, target).writeBytes(byteArrayOf(1, 2, 3))
        val store = store(directory, ready = true)
        val orphanWork = DownloadWorkIdentity(51L, 51L)
        val orphan = record(
            DownloadPhase.DOWNLOADING,
            generation = 51L,
            work = orphanWork,
        )

        assertFalse(store.write(current))
        assertFalse(store.write(orphan))
        val reservedWork = DownloadWorkIdentity(52L, 52L)
        val reserved = store.reserveNextGeneration(
            target,
            reservedWork,
            DownloadNetwork.WIFI,
            downloadedBytes = 400L,
            totalBytes = 1_000L,
        )

        assertEquals(51L, reserved.telemetry.generation.value)
        assertFalse(store.write(orphan))
        assertEquals(reservedWork, store.read(target)?.workIdentity)
    }

    @Test
    fun callbackCannotJumpBeyondTheReservedGeneration() {
        val directory = temporaryFolder.newFolder("generation-jump")
        val current = record(DownloadPhase.DOWNLOADING, generation = 60L)
        seedRecord(directory, current)
        val fabricated = record(DownloadPhase.READY, generation = 62L)

        assertFalse(store(directory, ready = true).write(fabricated))
        assertEquals(current, store(directory, ready = true).read(target))
    }

    @Test
    fun corruptGenerationWatermarkFencesReadWriteAndReservation() {
        val directory = temporaryFolder.newFolder("corrupt-watermark")
        val current = record(DownloadPhase.DOWNLOADING, generation = 70L)
        seedRecord(directory, current)
        DownloadTelemetryStore.generationFile(directory, target).writeBytes(byteArrayOf(7, 0))
        val store = store(directory, ready = true)

        assertNull(store.read(target))
        assertFalse(store.write(current))
        assertThrows(IllegalStateException::class.java) {
            store.reserveNextGeneration(
                target,
                DownloadWorkIdentity(71L, 71L),
                DownloadNetwork.WIFI,
                downloadedBytes = 400L,
                totalBytes = 1_000L,
            )
        }
    }

    @Test
    fun knownTotalCannotRegressToUnknownWithinAnAttempt() {
        val directory = temporaryFolder.newFolder("known-total")
        val current = record(DownloadPhase.DOWNLOADING, generation = 80L)
        seedRecord(directory, current)
        val unknownTotal = DurableDownloadRecord(
            target,
            work,
            DownloadTelemetryRestoreFactory().restore(
                DownloadObservation(
                    generation = DownloadGeneration(80L),
                    phase = DownloadPhase.DOWNLOADING,
                    downloadedBytes = 500L,
                    totalBytes = null,
                    network = DownloadNetwork.WIFI,
                ),
                updatedAtMillis = nowMillis + 1_000L,
                samples = listOf(
                    DownloadSample(DownloadGeneration(80L), nowMillis, 400L),
                    DownloadSample(DownloadGeneration(80L), nowMillis + 1_000L, 500L),
                ),
            ),
        )

        assertFalse(store(directory, ready = true).write(unknownTotal))
        assertEquals(current, store(directory, ready = true).read(target))
    }

    @Test
    fun exactZeroByteRestartCanRewindOneCurrentAttemptWithoutAllowingOtherRollback() {
        val directory = temporaryFolder.newFolder("exact-zero-restart")
        val current = record(DownloadPhase.DOWNLOADING, generation = 81L)
        seedRecord(directory, current)
        val targetStore = store(directory, ready = false)
        val resetTime = nowMillis + 1L
        val reset = DurableDownloadRecord(
            target = target,
            workIdentity = work,
            telemetry = DownloadTelemetryRestoreFactory().restore(
                observation = DownloadObservation(
                    generation = current.telemetry.generation,
                    phase = DownloadPhase.DOWNLOADING,
                    downloadedBytes = 0L,
                    totalBytes = current.telemetry.totalBytes,
                    network = DownloadNetwork.WIFI,
                    cancellationAllowed = true,
                ),
                updatedAtMillis = resetTime,
                samples = listOf(
                    DownloadSample(current.telemetry.generation, resetTime, 0L),
                ),
            ),
        )

        assertTrue(targetStore.write(reset))
        assertEquals(reset, targetStore.read(target))

        val resumedTime = resetTime + 1_000L
        val resumed = DurableDownloadRecord(
            target = target,
            workIdentity = work,
            telemetry = DownloadTelemetryRestoreFactory().restore(
                observation = DownloadObservation(
                    generation = current.telemetry.generation,
                    phase = DownloadPhase.DOWNLOADING,
                    downloadedBytes = 100L,
                    totalBytes = current.telemetry.totalBytes,
                    network = DownloadNetwork.WIFI,
                    cancellationAllowed = true,
                ),
                updatedAtMillis = resumedTime,
                samples = listOf(
                    DownloadSample(current.telemetry.generation, resetTime, 0L),
                    DownloadSample(current.telemetry.generation, resumedTime, 100L),
                ),
            ),
        )
        assertTrue(targetStore.write(resumed))
        assertEquals(resumed, targetStore.read(target))

        val blockedDirectory = temporaryFolder.newFolder("blocked-nonzero-rollback")
        seedRecord(blockedDirectory, current)
        val nonzeroRollback = DurableDownloadRecord(
            target = target,
            workIdentity = work,
            telemetry = DownloadTelemetryRestoreFactory().restore(
                observation = DownloadObservation(
                    generation = current.telemetry.generation,
                    phase = DownloadPhase.DOWNLOADING,
                    downloadedBytes = 399L,
                    totalBytes = current.telemetry.totalBytes,
                    network = DownloadNetwork.WIFI,
                    cancellationAllowed = true,
                ),
                updatedAtMillis = resetTime,
                samples = listOf(
                    DownloadSample(current.telemetry.generation, resetTime, 399L),
                ),
            ),
        )
        assertFalse(store(blockedDirectory, ready = false).write(nonzeroRollback))
        assertEquals(current, store(blockedDirectory, ready = false).read(target))

        val wrongIdentityReset = reset.copy(workIdentity = DownloadWorkIdentity(81L, 82L))
        assertFalse(store(blockedDirectory, ready = false).write(wrongIdentityReset))
        assertEquals(current, store(blockedDirectory, ready = false).read(target))
    }

    @Test
    fun generationReservationIsMonotonicAcrossFreshInstancesAndKeepsResumeOffset() {
        val directory = temporaryFolder.newFolder("generation")
        seedRecord(directory, record(DownloadPhase.FAILED, 41L))
        val nextWork = DownloadWorkIdentity.from(
            UUID.fromString("8aa39fd0-2616-4577-ae31-e311031e6355"),
        )

        val reserved = store(directory, ready = true).reserveNextGeneration(
            target = target,
            workIdentity = nextWork,
            network = DownloadNetwork.MOBILE,
            downloadedBytes = 417L,
            totalBytes = 1_000L,
        )

        assertEquals(DownloadGeneration(42L), reserved.telemetry.generation)
        assertEquals(DownloadPhase.PREPARING, reserved.telemetry.phase)
        assertEquals(417L, reserved.telemetry.downloadedBytes)
        assertEquals(1_000L, reserved.telemetry.totalBytes)
        assertTrue(reserved.telemetry.samples.isEmpty())
        assertEquals(nextWork, reserved.workIdentity)
        assertEquals(reserved, store(directory, ready = true).read(target))
    }

    @Test
    fun clearRecordRemovesOnlyExactSnapshotAndTempButPreservesGenerationFence() {
        val directory = temporaryFolder.newFolder("clear-record")
        val targetStore = store(directory, ready = false)
        targetStore.reserveNextGeneration(
            target = target,
            workIdentity = work,
            network = DownloadNetwork.WIFI,
            downloadedBytes = 25L,
            totalBytes = 1_000L,
        )
        val otherWork = DownloadWorkIdentity.from(
            UUID.fromString("ac832148-74ee-42b9-b5e9-aa920d46006c"),
        )
        val otherRecord = targetStore.reserveNextGeneration(
            target = otherTarget,
            workIdentity = otherWork,
            network = DownloadNetwork.MOBILE,
            downloadedBytes = 50L,
            totalBytes = 2_000L,
        )
        val recordFile = DownloadTelemetryStore.recordFile(directory, target)
        val temporary = File(recordFile.parentFile, "${recordFile.name}.tmp")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val generationFile = DownloadTelemetryStore.generationFile(directory, target)

        targetStore.clearRecord(target)
        targetStore.clearRecord(target)

        assertFalse(recordFile.exists())
        assertFalse(temporary.exists())
        assertTrue(generationFile.isFile)
        assertNull(store(directory, ready = false).read(target))
        assertEquals(otherRecord, store(directory, ready = false).read(otherTarget))

        val next = store(directory, ready = false).reserveNextGeneration(
            target = target,
            workIdentity = DownloadWorkIdentity.from(
                UUID.fromString("da7dd202-3527-446f-b6e9-0b8ac8b07f60"),
            ),
            network = DownloadNetwork.OFFLINE,
            downloadedBytes = 0L,
            totalBytes = 1_000L,
        )
        assertEquals(DownloadGeneration(2L), next.telemetry.generation)
    }

    @Test
    fun clearRecordRepairsCorruptGenerationBeforeAnyFreshReservation() {
        val directory = temporaryFolder.newFolder("clear-corrupt-generation")
        val current = record(DownloadPhase.DOWNLOADING, generation = 70L)
        seedRecord(directory, current)
        DownloadTelemetryStore.generationFile(directory, target)
            .writeBytes(byteArrayOf(7, 0))
        val targetStore = store(directory, ready = false)

        targetStore.clearRecord(target)

        assertNull(targetStore.read(target))
        val reserved = targetStore.reserveNextGeneration(
            target = target,
            workIdentity = DownloadWorkIdentity(71L, 72L),
            network = DownloadNetwork.WIFI,
            downloadedBytes = 0L,
            totalBytes = 1_000L,
        )
        assertEquals(DownloadGeneration(71L), reserved.telemetry.generation)

        val orphanDirectory = temporaryFolder.newFolder("clear-orphan-corrupt-generation")
        DownloadTelemetryStore.generationFile(orphanDirectory, target)
            .writeBytes(byteArrayOf(9, 9, 9))
        val orphanStore = store(orphanDirectory, ready = false)
        orphanStore.clearRecord(target)
        val first = orphanStore.reserveNextGeneration(
            target = target,
            workIdentity = DownloadWorkIdentity(1L, 2L),
            network = DownloadNetwork.OFFLINE,
            downloadedBytes = 0L,
            totalBytes = 1_000L,
        )
        assertEquals(DownloadGeneration(1L), first.telemetry.generation)
    }

    @Test
    fun concurrentReservationsFromFreshStoresNeverReuseAGeneration() {
        val directory = temporaryFolder.newFolder("concurrent-generation")
        seedRecord(directory, record(DownloadPhase.FAILED, 1L))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val generations = Collections.synchronizedList(mutableListOf<Long>())

        repeat(2) { index ->
            pool.submit {
                start.await()
                val reserved = store(directory, ready = true).reserveNextGeneration(
                    target,
                    DownloadWorkIdentity(index.toLong(), (index + 10L)),
                    DownloadNetwork.WIFI,
                    downloadedBytes = 25L,
                    totalBytes = 100L,
                )
                generations += reserved.telemetry.generation.value
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals(listOf(2L, 3L), generations.sorted())
    }

    @Test
    fun generationOverflowFailsClosedInsteadOfWrapping() {
        val directory = temporaryFolder.newFolder("generation-overflow")
        seedRecord(directory, record(DownloadPhase.FAILED, Long.MAX_VALUE))

        assertThrows(IllegalStateException::class.java) {
            store(directory, ready = true).reserveNextGeneration(
                target,
                DownloadWorkIdentity(1L, 2L),
                DownloadNetwork.WIFI,
                downloadedBytes = 0L,
                totalBytes = null,
            )
        }
    }

    @Test
    fun truncatedCorruptFutureVersionBadMagicBadEnumAndExtraBytesFailClosed() {
        val valid = DownloadTelemetryCodec.encode(record(DownloadPhase.DOWNLOADING, 7L))
        val mutations = listOf(
            valid.copyOf(valid.size / 2),
            valid.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() },
            valid.copyOf().also { ByteBuffer.wrap(it).putInt(0, 0x01020304); rewriteChecksum(it) },
            valid.copyOf().also { ByteBuffer.wrap(it).putInt(4, Int.MAX_VALUE); rewriteChecksum(it) },
            valid.copyOf().also { ByteBuffer.wrap(it).putInt(8, 99); rewriteChecksum(it) },
            valid + byteArrayOf(0x01),
        )

        mutations.forEach { bytes ->
            assertNull(DownloadTelemetryCodec.decode(bytes))
        }
    }

    @Test
    fun everyEnumAndNullablePresenceFieldRejectsUnknownEncoding() {
        val invalid = listOf(
            rawRecord(family = 99),
            rawRecord(tier = 99),
            rawRecord(backend = 99),
            rawRecord(phase = 99),
            rawRecord(network = 99),
            rawRecord(blockReason = 99),
            rawRecord(blockReason = -2),
            rawRecord(failureCode = 99),
            rawRecord(failureCode = -2),
            rawRecord(hasTotal = 2),
        )

        invalid.forEach { assertNull(DownloadTelemetryCodec.decode(it)) }
    }

    @Test
    fun impossibleNumbersBooleansSamplesAndReadyClaimsFailClosed() {
        val invalid = listOf(
            rawRecord(generation = 0L),
            rawRecord(generation = -1L),
            rawRecord(downloadedBytes = -1L),
            rawRecord(totalBytes = 0L),
            rawRecord(totalBytes = -1L),
            rawRecord(updatedAtMillis = -1L),
            rawRecord(canRetry = 2),
            rawRecord(canCancel = 2),
            rawRecord(verificationSucceeded = 2),
            rawRecord(
                samples = listOf(
                    RawSample(7L, nowMillis - 2_000L, 100L),
                    RawSample(8L, nowMillis, 400L),
                ),
            ),
            rawRecord(
                samples = listOf(
                    RawSample(7L, nowMillis, 100L),
                    RawSample(7L, nowMillis - 2_000L, 400L),
                ),
            ),
            rawRecord(
                samples = (0..DownloadTelemetryCalculator.DEFAULT_MAX_SAMPLE_COUNT).map { index ->
                    RawSample(7L, nowMillis - 8_000L + index * 1_000L, index * 10L)
                },
            ),
            rawRecord(
                phase = DownloadTelemetryWire.PHASE_READY,
                downloadedBytes = 400L,
                verificationSucceeded = 1,
                samples = emptyList(),
            ),
            rawRecord(
                phase = DownloadTelemetryWire.PHASE_READY,
                downloadedBytes = 1_000L,
                verificationSucceeded = 0,
                samples = emptyList(),
            ),
        )

        invalid.forEach { assertNull(DownloadTelemetryCodec.decode(it)) }
        assertNull(DownloadTelemetryCodec.decode(byteArrayOf()))
    }

    @Test
    fun encodedTargetMustMatchTheRequestedTarget() {
        val directory = temporaryFolder.newFolder("target-mismatch")
        val legacy = record(
            phase = DownloadPhase.DOWNLOADING,
            generation = 2L,
            target = otherTarget,
        )
        writeRaw(directory, target, DownloadTelemetryCodec.encode(legacy))
        writeGeneration(directory, target, legacy.telemetry.generation.value)

        assertNull(store(directory, ready = true).read(target))
    }

    @Test
    fun futureTimestampAndStaleUncommittedOrReadyStateFailClosed() {
        val directory = temporaryFolder.newFolder("recovery")
        val future = record(
            DownloadPhase.DOWNLOADING,
            generation = 1L,
            updatedAtMillis = nowMillis + DownloadTelemetryRecoveryPolicy.FUTURE_TOLERANCE_MILLIS + 1L,
        )
        seedRecord(directory, future)
        assertNull(store(directory, ready = true).read(target))

        val staleTime = nowMillis - DownloadTelemetryRecoveryPolicy.ACTIVE_STALE_MILLIS - 1L
        val staleActive = record(DownloadPhase.VERIFYING, 2L, updatedAtMillis = staleTime)
        seedRecord(directory, staleActive)
        val activeRecovered = store(directory, ready = false).read(target)
        assertEquals(DownloadPhase.PAUSED, activeRecovered?.telemetry?.phase)
        assertEquals(DownloadBlockReason.DEVICE_CONSTRAINT, activeRecovered?.telemetry?.blockReason)
        assertTrue(activeRecovered?.telemetry?.canRetry == true)

        val staleReady = record(DownloadPhase.READY, 3L, updatedAtMillis = staleTime)
        seedRecord(directory, staleReady)
        val readyRecovered = store(directory, ready = true).read(target)
        assertEquals(DownloadPhase.PAUSED, readyRecovered?.telemetry?.phase)
        assertFalse(readyRecovered?.telemetry?.verificationSucceeded == true)
    }

    @Test
    fun durableActivationMarkerRecoversFreshOrStaleVerifyingSnapshotToReady() {
        listOf(
            nowMillis,
            nowMillis - DownloadTelemetryRecoveryPolicy.ACTIVE_STALE_MILLIS - 1L,
        ).forEachIndexed { index, timestamp ->
            val directory = temporaryFolder.newFolder("activation-recovery-$index")
            val verifying = record(
                DownloadPhase.VERIFYING,
                generation = 30L + index,
                updatedAtMillis = timestamp,
            )
            seedRecord(directory, verifying)

            val recovered = store(directory, ready = true).read(target)

            assertEquals(DownloadPhase.READY, recovered?.telemetry?.phase)
            assertTrue(recovered?.telemetry?.verificationSucceeded == true)
            assertFalse(recovered?.telemetry?.canCancel == true)
            assertFalse(recovered?.telemetry?.canRetry == true)
            assertEquals(verifying.workIdentity, recovered?.workIdentity)
            assertEquals(verifying.telemetry.generation, recovered?.telemetry?.generation)
            assertEquals(nowMillis, recovered?.telemetry?.updatedAtMillis)

            val persisted = store(directory, ready = true).read(target)
            assertEquals(DownloadPhase.READY, persisted?.telemetry?.phase)
            assertTrue(persisted?.telemetry?.verificationSucceeded == true)
        }
    }

    @Test
    fun activationRecoveryCommitFailureReturnsNullAndLeavesVerifyingSnapshotRecoverable() {
        val directory = temporaryFolder.newFolder("activation-recovery-write-failure")
        val verifying = record(DownloadPhase.VERIFYING, generation = 41L)
        seedRecord(directory, verifying)
        val failingStore = DownloadTelemetryStore(
            directory = directory,
            clock = { nowMillis },
            readyReconciler = DownloadReadyReconciler { _, _ -> true },
            committer = AtomicSnapshotCommitter { _, _ ->
                throw IOException("Injected activation recovery commit failure")
            },
        )

        assertNull(failingStore.read(target))

        val recovered = store(directory, ready = true).read(target)
        assertEquals(DownloadPhase.READY, recovered?.telemetry?.phase)
        assertTrue(recovered?.telemetry?.verificationSucceeded == true)
    }

    @Test
    fun readyRequiresCurrentActiveSelfTestedArtifactEveryTimeItIsRead() {
        val directory = temporaryFolder.newFolder("ready")
        val ready = record(DownloadPhase.READY, generation = 5L)
        seedRecord(directory, ready)

        val restored = store(directory, ready = false).read(target)

        assertNotNull(restored)
        assertEquals(DownloadPhase.PAUSED, restored?.telemetry?.phase)
        assertEquals(DownloadBlockReason.DEVICE_CONSTRAINT, restored?.telemetry?.blockReason)
        assertFalse(restored?.telemetry?.verificationSucceeded == true)

        val cannotResurrect = store(directory, ready = true).read(target)
        assertEquals(DownloadPhase.PAUSED, cannotResurrect?.telemetry?.phase)
        assertFalse(cannotResurrect?.telemetry?.verificationSucceeded == true)
    }

    @Test
    fun snapshotReadFailureReturnsNullInsteadOfEscapingOrClaimingReady() {
        val directory = temporaryFolder.newFolder("read-io-failure")
        seedRecord(directory, record(DownloadPhase.READY, generation = 6L))
        val failingStore = DownloadTelemetryStore(
            directory = directory,
            clock = { nowMillis },
            readyReconciler = DownloadReadyReconciler { _, _ -> true },
            snapshotReader = SnapshotReader { throw IOException("Injected read failure") },
        )

        assertNull(failingStore.read(target))
    }

    @Test
    fun failedRecoveryCommitReturnsNullAndNeverExposesInvalidReady() {
        val directory = temporaryFolder.newFolder("recovery-io-failure")
        seedRecord(directory, record(DownloadPhase.READY, generation = 7L))
        val failingStore = DownloadTelemetryStore(
            directory = directory,
            clock = { nowMillis },
            readyReconciler = DownloadReadyReconciler { _, _ -> false },
            committer = AtomicSnapshotCommitter { _, _ ->
                throw IOException("Injected recovery commit failure")
            },
        )

        assertNull(failingStore.read(target))
    }

    @Test
    fun corruptSnapshotCannotResetGenerationOrLetOldWorkWriteAgain() {
        val directory = temporaryFolder.newFolder("corrupt-generation")
        val old = record(DownloadPhase.FAILED, generation = 8L)
        seedRecord(directory, old)
        DownloadTelemetryStore.recordFile(directory, target).writeBytes(byteArrayOf(1, 2, 3))
        val nextWork = DownloadWorkIdentity(99L, 100L)

        val reserved = store(directory, ready = true).reserveNextGeneration(
            target,
            nextWork,
            DownloadNetwork.WIFI,
            downloadedBytes = 200L,
            totalBytes = 1_000L,
        )

        assertEquals(9L, reserved.telemetry.generation.value)
        assertFalse(store(directory, ready = true).write(old))
        assertEquals(nextWork, store(directory, ready = true).read(target)?.workIdentity)
    }

    @Test
    fun sameGenerationTerminalStatesAreSealedAgainstLateCallbacks() {
        listOf(DownloadPhase.PAUSED, DownloadPhase.FAILED, DownloadPhase.READY).forEach { terminal ->
            val directory = temporaryFolder.newFolder("sealed-$terminal")
            val terminalRecord = record(terminal, generation = 14L)
            val store = store(directory, ready = true)
            seedRecord(directory, terminalRecord)
            listOf(
                DownloadPhase.DOWNLOADING,
                DownloadPhase.VERIFYING,
                DownloadPhase.READY,
            ).forEach { latePhase ->
                val late = record(latePhase, generation = 14L)
                if (late == terminalRecord) {
                    assertTrue(store.write(late))
                } else {
                    assertFalse(store.write(late))
                }
            }
            assertEquals(terminalRecord, store.read(target))
        }
    }

    @Test
    fun sameGenerationTerminalStateCannotBeMutatedInPlace() {
        listOf(DownloadPhase.PAUSED, DownloadPhase.FAILED, DownloadPhase.READY).forEach { terminal ->
            val directory = temporaryFolder.newFolder("sealed-mutation-$terminal")
            val original = record(terminal, generation = 15L)
            val store = store(directory, ready = true)
            seedRecord(directory, original)
            val mutatedObservation = DownloadObservation(
                generation = original.telemetry.generation,
                phase = terminal,
                downloadedBytes = original.telemetry.downloadedBytes,
                totalBytes = original.telemetry.totalBytes,
                network = DownloadNetwork.MOBILE,
                blockReason = when (terminal) {
                    DownloadPhase.PAUSED -> DownloadBlockReason.DEVICE_CONSTRAINT
                    else -> null
                },
                failureCode = when (terminal) {
                    DownloadPhase.FAILED -> DownloadFailureCode.SELF_TEST_FAILED
                    else -> null
                },
                verificationSucceeded = terminal == DownloadPhase.READY,
                cancellationAllowed = false,
                retryAllowed = terminal == DownloadPhase.PAUSED || terminal == DownloadPhase.FAILED,
            )
            val mutated = DurableDownloadRecord(
                target,
                work,
                DownloadTelemetryRestoreFactory().restore(
                    mutatedObservation,
                    updatedAtMillis = nowMillis + 1L,
                    samples = emptyList(),
                ),
            )

            assertFalse(store.write(mutated))
            assertEquals(original, store.read(target))
        }
    }

    @Test
    fun staleThresholdCannotExpireOrdinaryVerificationWork() {
        assertEquals(
            TimeUnit.DAYS.toMillis(7L),
            DownloadTelemetryRecoveryPolicy.ACTIVE_STALE_MILLIS,
        )
        assertTrue(
            DownloadTelemetryRecoveryPolicy.ACTIVE_STALE_MILLIS >= TimeUnit.DAYS.toMillis(1L),
        )
    }

    @Test
    fun binarySnapshotIsBoundedAndContainsNoSensitiveStrings() {
        val encoded = DownloadTelemetryCodec.encode(record(DownloadPhase.DOWNLOADING, 6L))
        val asText = encoded.toString(Charsets.ISO_8859_1)

        assertTrue(encoded.size <= DownloadTelemetryCodec.MAX_RECORD_BYTES)
        listOf(
            "https://model.example/private",
            "Bearer secret-token",
            "C:\\Users\\person\\model.gguf",
            "Rewrite my confidential draft",
            "corrected private answer",
        ).forEach { forbidden ->
            assertFalse(asText.contains(forbidden))
        }
        val durableFields = DurableDownloadRecord::class.java.declaredFields.map { it.type }
        val targetFields = DownloadTargetKey::class.java.declaredFields.map { it.type }
        val workFields = DownloadWorkIdentity::class.java.declaredFields.map { it.type }
        assertTrue((durableFields + targetFields + workFields).none { it == String::class.java })
        val source = File(
            System.getProperty("echoLinkAndroidDir"),
            "app/src/main/java/com/echolink/mobile/DownloadTelemetryStore.kt",
        ).readText()
        assertFalse(source.contains("writeUTF"))
        assertFalse(source.contains("writeChars"))
    }

    @Test
    fun readerObservesOnlyCompleteOldOrNewSnapshotsDuringConcurrentWrites() {
        val directory = temporaryFolder.newFolder("atomic")
        val first = record(DownloadPhase.DOWNLOADING, generation = 12L)
        val second = record(DownloadPhase.DOWNLOADING, generation = 12L).let { current ->
            DurableDownloadRecord(
                current.target,
                current.workIdentity,
                DownloadTelemetryRestoreFactory().restore(
                    observation = DownloadObservation(
                        generation = DownloadGeneration(12L),
                        phase = DownloadPhase.DOWNLOADING,
                        downloadedBytes = 700L,
                        totalBytes = 1_000L,
                        network = DownloadNetwork.WIFI,
                    ),
                    updatedAtMillis = nowMillis + 1_000L,
                    samples = listOf(
                        DownloadSample(DownloadGeneration(12L), nowMillis, 400L),
                        DownloadSample(DownloadGeneration(12L), nowMillis + 1_000L, 700L),
                    ),
                ),
            )
        }
        val store = store(directory, ready = true)
        seedRecord(directory, first)
        val observed = Collections.synchronizedList(mutableListOf<DurableDownloadRecord?>())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        pool.submit {
            start.await()
            repeat(100) { assertTrue(store.write(second)) }
        }
        pool.submit {
            start.await()
            repeat(200) { observed += store.read(target) }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { it == first || it == second })
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "tmp" })
    }

    @Test
    fun interruptedAtomicCommitLeavesPreviousCompleteSnapshotReadable() {
        val directory = temporaryFolder.newFolder("interrupted-commit")
        val first = record(DownloadPhase.DOWNLOADING, generation = 21L)
        val second = record(DownloadPhase.VERIFYING, generation = 21L)
        seedRecord(directory, first)
        val failingStore = DownloadTelemetryStore(
            directory = directory,
            clock = { nowMillis },
            readyReconciler = DownloadReadyReconciler { _, _ -> true },
            committer = AtomicSnapshotCommitter { temporary, _ ->
                assertTrue(Files.isRegularFile(temporary))
                assertTrue(Files.size(temporary) > 0L)
                assertNotNull(DownloadTelemetryCodec.decode(Files.readAllBytes(temporary)))
                throw IOException("Injected commit failure")
            },
        )

        assertThrows(IOException::class.java) { failingStore.write(second) }

        assertEquals(first, store(directory, ready = true).read(target))
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "tmp" })
    }

    @Test
    fun failedRecordCommitAdvancesWatermarkSoFreshReservationSkipsThatGeneration() {
        val directory = temporaryFolder.newFolder("record-commit-watermark")
        seedRecord(directory, record(DownloadPhase.FAILED, 30L))
        val failingStore = DownloadTelemetryStore(
            directory = directory,
            clock = { nowMillis },
            readyReconciler = DownloadReadyReconciler { _, _ -> true },
            committer = AtomicSnapshotCommitter { temporary, destination ->
                if (destination.fileName.toString().endsWith(".bin")) {
                    assertTrue(Files.size(temporary) > 0L)
                    throw IOException("Injected record commit failure")
                }
                moveReplacing(temporary, destination)
            },
        )

        assertThrows(IOException::class.java) {
            failingStore.reserveNextGeneration(
                target,
                DownloadWorkIdentity(31L, 31L),
                DownloadNetwork.WIFI,
                downloadedBytes = 300L,
                totalBytes = 1_000L,
            )
        }
        val orphan = record(
            DownloadPhase.DOWNLOADING,
            generation = 31L,
            work = DownloadWorkIdentity(31L, 31L),
        )
        assertFalse(store(directory, ready = true).write(orphan))
        val reserved = store(directory, ready = true).reserveNextGeneration(
            target,
            DownloadWorkIdentity(32L, 32L),
            DownloadNetwork.WIFI,
            downloadedBytes = 300L,
            totalBytes = 1_000L,
        )

        assertEquals(32L, reserved.telemetry.generation.value)
    }

    @Test
    fun failedWatermarkCommitLeavesOldRecordAndReturnsNoReservation() {
        val directory = temporaryFolder.newFolder("watermark-commit-failure")
        val old = record(DownloadPhase.FAILED, 40L)
        seedRecord(directory, old)
        val failingStore = DownloadTelemetryStore(
            directory = directory,
            clock = { nowMillis },
            readyReconciler = DownloadReadyReconciler { _, _ -> true },
            committer = AtomicSnapshotCommitter { temporary, destination ->
                if (destination.fileName.toString().endsWith(".gen")) {
                    assertTrue(Files.size(temporary) > 0L)
                    throw IOException("Injected watermark commit failure")
                }
                moveReplacing(temporary, destination)
            },
        )

        assertThrows(IOException::class.java) {
            failingStore.reserveNextGeneration(
                target,
                DownloadWorkIdentity(41L, 41L),
                DownloadNetwork.WIFI,
                downloadedBytes = 0L,
                totalBytes = 1_000L,
            )
        }

        assertEquals(old, store(directory, ready = true).read(target))
    }

    @Test
    fun oversizedSnapshotAndExcessiveSamplesAreRejected() {
        val directory = temporaryFolder.newFolder("bounds")
        val oversized = ByteArray(DownloadTelemetryCodec.MAX_RECORD_BYTES + 1)
        writeRaw(directory, target, oversized)
        writeGeneration(directory, target, 1L)
        assertNull(store(directory, ready = true).read(target))

        val tooManySamples = (0..DownloadTelemetryCalculator.DEFAULT_MAX_SAMPLE_COUNT).map { index ->
            DownloadSample(DownloadGeneration(2L), index * 1_000L, index * 100L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadTelemetryStore.validateSampleBound(tooManySamples)
        }
    }

    private fun store(directory: File, ready: Boolean): DownloadTelemetryStore =
        DownloadTelemetryStore(
            directory = directory,
            clock = { nowMillis },
            readyReconciler = DownloadReadyReconciler { _, _ -> ready },
        )

    private fun record(
        phase: DownloadPhase,
        generation: Long,
        target: DownloadTargetKey = this.target,
        work: DownloadWorkIdentity = this.work,
        updatedAtMillis: Long = nowMillis,
    ): DurableDownloadRecord {
        val downloaded = when (phase) {
            DownloadPhase.PREPARING -> 0L
            DownloadPhase.DOWNLOADING -> 400L
            DownloadPhase.VERIFYING,
            DownloadPhase.READY,
            -> 1_000L
            DownloadPhase.WAITING,
            DownloadPhase.PAUSED,
            DownloadPhase.FAILED,
            -> 600L
        }
        val total = 1_000L
        val samples = if (phase == DownloadPhase.DOWNLOADING) {
            listOf(
                DownloadSample(DownloadGeneration(generation), updatedAtMillis - 2_000L, 100L),
                DownloadSample(DownloadGeneration(generation), updatedAtMillis, downloaded),
            )
        } else {
            emptyList()
        }
        val observation = DownloadObservation(
            generation = DownloadGeneration(generation),
            phase = phase,
            downloadedBytes = downloaded,
            totalBytes = total,
            network = DownloadNetwork.WIFI,
            blockReason = when (phase) {
                DownloadPhase.WAITING -> DownloadBlockReason.NETWORK_UNAVAILABLE
                DownloadPhase.PAUSED -> DownloadBlockReason.USER_PAUSED
                else -> null
            },
            failureCode = if (phase == DownloadPhase.FAILED) {
                DownloadFailureCode.INTEGRITY_CHECK_FAILED
            } else {
                null
            },
            verificationSucceeded = phase == DownloadPhase.READY,
            cancellationAllowed = phase in setOf(
                DownloadPhase.PREPARING,
                DownloadPhase.DOWNLOADING,
                DownloadPhase.VERIFYING,
            ),
            retryAllowed = phase == DownloadPhase.PAUSED || phase == DownloadPhase.FAILED,
        )
        return DurableDownloadRecord(
            target = target,
            workIdentity = work,
            telemetry = DownloadTelemetryRestoreFactory().restore(
                observation = observation,
                updatedAtMillis = updatedAtMillis,
                samples = samples,
            ),
        )
    }

    private fun writeRaw(directory: File, target: DownloadTargetKey, bytes: ByteArray) {
        directory.mkdirs()
        DownloadTelemetryStore.recordFile(directory, target).writeBytes(bytes)
    }

    private fun seedRecord(directory: File, record: DurableDownloadRecord) {
        writeRaw(directory, record.target, DownloadTelemetryCodec.encode(record))
        writeGeneration(directory, record.target, record.telemetry.generation.value)
    }

    private fun writeGeneration(
        directory: File,
        target: DownloadTargetKey,
        generation: Long,
    ) {
        DownloadTelemetryStore.generationFile(directory, target).writeBytes(
            DownloadGenerationCounterCodec.encode(target, generation),
        )
    }

    private fun rewriteChecksum(bytes: ByteArray) {
        val checksumOffset = bytes.size - java.lang.Long.BYTES
        val crc = CRC32().apply { update(bytes, 0, checksumOffset) }.value
        ByteBuffer.wrap(bytes).putLong(checksumOffset, crc)
    }

    private data class RawSample(
        val generation: Long,
        val timestampMillis: Long,
        val downloadedBytes: Long,
    )

    private fun rawRecord(
        family: Int = DownloadTelemetryWire.FAMILY_GEMMA,
        tier: Int = DownloadTelemetryWire.TIER_FAST,
        backend: Int = DownloadTelemetryWire.BACKEND_LITERT_CPU,
        phase: Int = DownloadTelemetryWire.PHASE_DOWNLOADING,
        downloadedBytes: Long = 400L,
        hasTotal: Int = 1,
        totalBytes: Long = 1_000L,
        network: Int = DownloadTelemetryWire.NETWORK_WIFI,
        blockReason: Int = -1,
        failureCode: Int = -1,
        canRetry: Int = 0,
        canCancel: Int = 1,
        generation: Long = 7L,
        updatedAtMillis: Long = nowMillis,
        verificationSucceeded: Int = 0,
        samples: List<RawSample> = listOf(
            RawSample(generation, updatedAtMillis - 2_000L, 100L),
            RawSample(generation, updatedAtMillis, downloadedBytes),
        ),
    ): ByteArray {
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(DownloadTelemetryCodec.MAGIC)
                output.writeInt(DownloadTelemetryCodec.SCHEMA_VERSION)
                output.writeInt(family)
                output.writeInt(tier)
                output.writeInt(backend)
                output.writeLong(work.mostSignificantBits)
                output.writeLong(work.leastSignificantBits)
                output.writeInt(phase)
                output.writeLong(downloadedBytes)
                output.writeByte(hasTotal)
                if (hasTotal == 1) output.writeLong(totalBytes)
                output.writeInt(network)
                output.writeInt(blockReason)
                output.writeInt(failureCode)
                output.writeByte(canRetry)
                output.writeByte(canCancel)
                output.writeLong(generation)
                output.writeLong(updatedAtMillis)
                output.writeByte(verificationSucceeded)
                output.writeInt(samples.size)
                samples.forEach { sample ->
                    output.writeLong(sample.generation)
                    output.writeLong(sample.timestampMillis)
                    output.writeLong(sample.downloadedBytes)
                }
            }
        }.toByteArray()
        val crc = CRC32().apply { update(payload) }.value
        return ByteArrayOutputStream().also { bytes ->
            bytes.write(payload)
            DataOutputStream(bytes).use { it.writeLong(crc) }
        }.toByteArray()
    }

    private fun moveReplacing(temporary: java.nio.file.Path, destination: java.nio.file.Path) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}
