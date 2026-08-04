package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTelemetryCalculatorTest {
    private var nowMillis = 1_000L
    private val calculator = DownloadTelemetryCalculator(
        clock = { nowMillis },
        sampleWindowMillis = 5_000L,
        maxSampleCount = 4,
    )

    @Test
    fun firstDownloadSampleCannotClaimSpeedOrEta() {
        val telemetry = calculator.record(
            observation(downloadedBytes = 400L, totalBytes = 1_000L),
        )

        assertEquals(40, telemetry.percent)
        assertEquals(1_000L, telemetry.updatedAtMillis)
        assertNull(telemetry.bytesPerSecond)
        assertNull(telemetry.etaSeconds)
        assertEquals(listOf(DownloadSample(generation, 1_000L, 400L)), telemetry.samples)
    }

    @Test
    fun calculatorAndValueParametersRejectInvalidBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadTelemetryCalculator(clock = { 0L }, sampleWindowMillis = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadTelemetryCalculator(clock = { 0L }, maxSampleCount = 1)
        }
        listOf(0L, -1L).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                DownloadGeneration(value)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadSample(generation, timestampMillis = -1L, downloadedBytes = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadSample(generation, timestampMillis = 0L, downloadedBytes = -1L)
        }
    }

    @Test
    fun validRollingSamplesProduceExactSpeedAndRoundedUpEta() {
        val first = calculator.record(
            observation(downloadedBytes = 100L, totalBytes = 1_000L),
        )
        nowMillis = 3_000L

        val telemetry = calculator.record(
            observation(downloadedBytes = 500L, totalBytes = 1_000L),
            previous = first,
        )

        assertEquals(200L, telemetry.bytesPerSecond)
        assertEquals(3L, telemetry.etaSeconds)
        assertEquals(50, telemetry.percent)
    }

    @Test
    fun samplesOutsideWindowDoNotAffectSpeed() {
        nowMillis = 0L
        val first = calculator.record(observation(downloadedBytes = 0L, totalBytes = 2_000L))
        nowMillis = 3_000L
        val second = calculator.record(
            observation(downloadedBytes = 300L, totalBytes = 2_000L),
            first,
        )
        nowMillis = 6_000L

        val telemetry = calculator.record(
            observation(downloadedBytes = 900L, totalBytes = 2_000L),
            second,
        )

        assertEquals(listOf(3_000L, 6_000L), telemetry.samples.map { it.timestampMillis })
        assertEquals(200L, telemetry.bytesPerSecond)
        assertEquals(6L, telemetry.etaSeconds)
    }

    @Test
    fun sampleAtExactWindowBoundaryIsEvicted() {
        nowMillis = 0L
        val first = calculator.record(observation(downloadedBytes = 100L))
        nowMillis = 5_000L

        val telemetry = calculator.record(observation(downloadedBytes = 600L), first)

        assertEquals(listOf(5_000L), telemetry.samples.map { it.timestampMillis })
        assertNull(telemetry.bytesPerSecond)
        assertNull(telemetry.etaSeconds)
    }

    @Test
    fun sampleHistoryIsBoundedByCount() {
        val bounded = DownloadTelemetryCalculator(
            clock = { nowMillis },
            sampleWindowMillis = 60_000L,
            maxSampleCount = 3,
        )
        nowMillis = 0L
        var telemetry = bounded.record(observation(downloadedBytes = 0L))
        listOf(100L, 300L, 600L).forEachIndexed { index, bytes ->
            nowMillis = (index + 1) * 1_000L
            telemetry = bounded.record(observation(downloadedBytes = bytes), telemetry)
        }

        assertEquals(listOf(100L, 300L, 600L), telemetry.samples.map { it.downloadedBytes })
        assertEquals(250L, telemetry.bytesPerSecond)
    }

    @Test
    fun newGenerationAndPartialResumeStartWithARealBaseline() {
        val first = calculator.record(observation(downloadedBytes = 100L))
        nowMillis = 2_000L
        val proven = calculator.record(observation(downloadedBytes = 300L), first)
        assertEquals(200L, proven.bytesPerSecond)

        nowMillis = 3_000L
        val resumed = calculator.record(
            observation(
                generation = DownloadGeneration(8L),
                downloadedBytes = 700L,
                totalBytes = 1_000L,
            ),
            proven,
        )

        assertNull(resumed.bytesPerSecond)
        assertNull(resumed.etaSeconds)
        assertEquals(listOf(700L), resumed.samples.map { it.downloadedBytes })
    }

    @Test
    fun staleGenerationReturnsExactCurrentTelemetryWithoutMutation() {
        val currentGeneration = DownloadGeneration(8L)
        val first = calculator.record(
            observation(generation = currentGeneration, downloadedBytes = 100L),
        )
        nowMillis = 2_000L
        val current = calculator.record(
            observation(generation = currentGeneration, downloadedBytes = 500L),
            first,
        )
        assertEquals(400L, current.bytesPerSecond)

        nowMillis = 3_000L
        val originalSamples = current.samples
        val afterStale = calculator.record(
            observation(generation = generation, downloadedBytes = 900L),
            current,
        )

        assertSame(current, afterStale)
        assertEquals(currentGeneration, afterStale.generation)
        assertEquals(500L, afterStale.downloadedBytes)
        assertSame(originalSamples, afterStale.samples)
        assertEquals(400L, afterStale.bytesPerSecond)
    }

    @Test
    fun byteRollbackAndClockRollbackResetCalculation() {
        val first = calculator.record(observation(downloadedBytes = 500L))
        nowMillis = 2_000L
        val byteRollback = calculator.record(observation(downloadedBytes = 400L), first)

        assertNull(byteRollback.bytesPerSecond)
        assertEquals(listOf(400L), byteRollback.samples.map { it.downloadedBytes })

        nowMillis = 1_500L
        val clockRollback = calculator.record(observation(downloadedBytes = 600L), byteRollback)

        assertNull(clockRollback.bytesPerSecond)
        assertNull(clockRollback.etaSeconds)
        assertEquals(listOf(1_500L), clockRollback.samples.map { it.timestampMillis })
    }

    @Test
    fun negativeClockIsRejectedInsteadOfCreatingRates() {
        val invalidClock = DownloadTelemetryCalculator(clock = { -1L })

        assertThrows(IllegalArgumentException::class.java) {
            invalidClock.record(observation(downloadedBytes = 100L))
        }
    }

    @Test
    fun zeroTimeIntervalResetsSpeedEtaAndSampleBaseline() {
        val first = calculator.record(observation(downloadedBytes = 100L))

        val telemetry = calculator.record(observation(downloadedBytes = 300L), first)

        assertNull(telemetry.bytesPerSecond)
        assertNull(telemetry.etaSeconds)
        assertEquals(listOf(300L), telemetry.samples.map { it.downloadedBytes })
    }

    @Test
    fun sameGenerationResumeAfterWaitingUsesCurrentBytesAsBaseline() {
        listOf(
            DownloadPhase.WAITING to DownloadBlockReason.NETWORK_UNAVAILABLE,
            DownloadPhase.PAUSED to DownloadBlockReason.USER_PAUSED,
        ).forEach { (phase, reason) ->
            nowMillis = 1_000L
            val interruption = calculator.record(
                observation(
                    phase = phase,
                    downloadedBytes = 700L,
                    blockReason = reason,
                ),
            )
            nowMillis = 2_000L

            val resumed = calculator.record(
                observation(phase = DownloadPhase.DOWNLOADING, downloadedBytes = 700L),
                interruption,
            )

            assertNull("phase=$phase", resumed.bytesPerSecond)
            assertNull("phase=$phase", resumed.etaSeconds)
            assertEquals(
                "phase=$phase",
                listOf(700L),
                resumed.samples.map { it.downloadedBytes },
            )
        }
    }

    @Test
    fun unknownTotalAndZeroTransferNeverFabricateEta() {
        val first = calculator.record(observation(downloadedBytes = 100L, totalBytes = null))
        nowMillis = 2_000L
        val unknownTotal = calculator.record(
            observation(downloadedBytes = 300L, totalBytes = null),
            first,
        )
        assertEquals(200L, unknownTotal.bytesPerSecond)
        assertNull(unknownTotal.etaSeconds)

        nowMillis = 3_000L
        val zeroTransfer = calculator.record(
            observation(downloadedBytes = 300L, totalBytes = 1_000L),
            unknownTotal,
        )
        assertNull(zeroTransfer.bytesPerSecond)
        assertNull(zeroTransfer.etaSeconds)
    }

    @Test
    fun percentClampsAtOneHundred() {
        val over = calculator.record(observation(downloadedBytes = 150L, totalBytes = 100L))

        assertEquals(100, over.percent)
    }

    @Test
    fun invalidByteCountsAreRejectedAtTheInputBoundary() {
        assertThrows(IllegalArgumentException::class.java) {
            observation(downloadedBytes = -25L, totalBytes = 100L)
        }
        listOf(0L, -1L).forEach { invalidTotal ->
            assertThrows(IllegalArgumentException::class.java) {
                observation(downloadedBytes = 50L, totalBytes = invalidTotal)
            }
        }
    }

    @Test
    fun progressBeyondTotalNeverCreatesNegativeEta() {
        val first = calculator.record(observation(downloadedBytes = 90L, totalBytes = 100L))
        nowMillis = 2_000L

        val telemetry = calculator.record(
            observation(downloadedBytes = 150L, totalBytes = 100L),
            first,
        )

        assertEquals(100, telemetry.percent)
        assertNull(telemetry.etaSeconds)
    }

    @Test
    fun longMaximumProgressUsesOverflowSafeMath() {
        val total = Long.MAX_VALUE
        val first = calculator.record(
            observation(downloadedBytes = total - 1_000L, totalBytes = total),
        )
        nowMillis = 2_000L

        val telemetry = calculator.record(
            observation(downloadedBytes = total - 500L, totalBytes = total),
            first,
        )

        assertEquals(99, telemetry.percent)
        assertEquals(500L, telemetry.bytesPerSecond)
        assertEquals(1L, telemetry.etaSeconds)
    }

    @Test
    fun hugeByteDeltaClampsSpeedWithoutOverflow() {
        val first = calculator.record(observation(downloadedBytes = 0L, totalBytes = null))
        nowMillis = 1_001L

        val telemetry = calculator.record(
            observation(downloadedBytes = Long.MAX_VALUE, totalBytes = null),
            first,
        )

        assertEquals(Long.MAX_VALUE, telemetry.bytesPerSecond)
        assertNull(telemetry.etaSeconds)
    }

    @Test
    fun readyRequiresExplicitSuccessfulVerificationAndNoFailure() {
        assertThrows(IllegalArgumentException::class.java) {
            calculator.record(observation(phase = DownloadPhase.READY))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculator.record(
                observation(
                    phase = DownloadPhase.READY,
                    verificationSucceeded = true,
                    failureCode = DownloadFailureCode.INTEGRITY_CHECK_FAILED,
                ),
            )
        }

        val ready = calculator.record(
            observation(
                phase = DownloadPhase.READY,
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
                verificationSucceeded = true,
            ),
        )

        assertTrue(ready.verificationSucceeded)
        assertEquals(DownloadPhase.READY, ready.phase)
    }

    @Test
    fun readyRequiresCompleteKnownBytesButAllowsUnknownInstalledSize() {
        assertThrows(IllegalArgumentException::class.java) {
            observation(
                phase = DownloadPhase.READY,
                downloadedBytes = 999L,
                totalBytes = 1_000L,
                verificationSucceeded = true,
            )
        }

        val installed = calculator.record(
            observation(
                phase = DownloadPhase.READY,
                downloadedBytes = 0L,
                totalBytes = null,
                verificationSucceeded = true,
            ),
        )

        assertEquals(DownloadPhase.READY, installed.phase)
        assertNull(installed.totalBytes)
    }

    @Test
    fun internalCreationCannotForgeFailedActionsOrCalculatedRates() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadTelemetry.create(
                phase = DownloadPhase.FAILED,
                downloadedBytes = 0L,
                totalBytes = 1_000L,
                percent = 0,
                bytesPerSecond = null,
                etaSeconds = null,
                network = DownloadNetwork.WIFI,
                blockReason = null,
                failure = SafeDownloadFailure.UNKNOWN,
                canRetry = false,
                canCancel = false,
                generation = generation,
                updatedAtMillis = 1_000L,
                samples = emptyList(),
                verificationSucceeded = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadTelemetry.create(
                phase = DownloadPhase.DOWNLOADING,
                downloadedBytes = 500L,
                totalBytes = 1_000L,
                percent = 99,
                bytesPerSecond = 999L,
                etaSeconds = 1L,
                network = DownloadNetwork.WIFI,
                blockReason = null,
                failure = null,
                canRetry = false,
                canCancel = true,
                generation = generation,
                updatedAtMillis = 1_000L,
                samples = listOf(DownloadSample(generation, 1_000L, 500L)),
                verificationSucceeded = false,
            )
        }
        listOf(
            emptyList(),
            listOf(DownloadSample(generation, 500L, 400L)),
        ).forEach { staleSamples ->
            assertThrows(IllegalArgumentException::class.java) {
                DownloadTelemetry.create(
                    phase = DownloadPhase.DOWNLOADING,
                    downloadedBytes = 500L,
                    totalBytes = 1_000L,
                    percent = 50,
                    bytesPerSecond = null,
                    etaSeconds = null,
                    network = DownloadNetwork.WIFI,
                    blockReason = null,
                    failure = null,
                    canRetry = false,
                    canCancel = true,
                    generation = generation,
                    updatedAtMillis = 1_000L,
                    samples = staleSamples,
                    verificationSucceeded = false,
                )
            }
        }
    }

    @Test
    fun failedTelemetryAlwaysHasSafeFailureAndRetry() {
        assertThrows(IllegalArgumentException::class.java) {
            calculator.record(observation(phase = DownloadPhase.FAILED))
        }

        val failed = calculator.record(
            observation(
                phase = DownloadPhase.FAILED,
                failureCode = DownloadFailureCode.STORAGE_UNAVAILABLE,
                cancellationAllowed = true,
            ),
        )

        assertEquals(DownloadFailureCode.STORAGE_UNAVAILABLE, failed.failure?.code)
        assertFalse(failed.failure?.userMessage.isNullOrBlank())
        assertTrue(failed.canRetry)
        assertFalse(failed.canCancel)
    }

    @Test
    fun pausedAndFailedRetryAvailabilityIsExplicit() {
        val paused = calculator.record(
            observation(
                phase = DownloadPhase.PAUSED,
                blockReason = DownloadBlockReason.USER_PAUSED,
                retryAllowed = true,
            ),
        )
        val pausedWithoutRetry = calculator.record(
            observation(
                phase = DownloadPhase.PAUSED,
                blockReason = DownloadBlockReason.USER_PAUSED,
                retryAllowed = false,
            ),
        )
        val failedWithoutRetry = assertThrows(IllegalArgumentException::class.java) {
            observation(
                phase = DownloadPhase.FAILED,
                failureCode = DownloadFailureCode.UNKNOWN,
                retryAllowed = false,
            )
        }

        assertTrue(paused.canRetry)
        assertFalse(pausedWithoutRetry.canRetry)
        assertTrue(failedWithoutRetry.message.orEmpty().isNotBlank())
        listOf(
            DownloadPhase.PREPARING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.VERIFYING,
            DownloadPhase.READY,
            DownloadPhase.WAITING,
        ).forEach { phase ->
            assertThrows("phase=$phase retry", IllegalArgumentException::class.java) {
                observation(
                    phase = phase,
                    blockReason = if (phase == DownloadPhase.WAITING) {
                        DownloadBlockReason.NETWORK_UNAVAILABLE
                    } else {
                        null
                    },
                    verificationSucceeded = phase == DownloadPhase.READY,
                    retryAllowed = true,
                )
            }
        }
    }

    @Test
    fun phaseSpecificFieldsCannotCreateContradictoryTelemetry() {
        listOf(DownloadPhase.WAITING, DownloadPhase.PAUSED).forEach { phase ->
            assertThrows("phase=$phase", IllegalArgumentException::class.java) {
                calculator.record(observation(phase = phase, blockReason = null))
            }
        }
        listOf(
            DownloadPhase.PREPARING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.VERIFYING,
            DownloadPhase.READY,
            DownloadPhase.FAILED,
        ).forEach { phase ->
            assertThrows("phase=$phase", IllegalArgumentException::class.java) {
                calculator.record(
                    observation(
                        phase = phase,
                        blockReason = DownloadBlockReason.NETWORK_UNAVAILABLE,
                        verificationSucceeded = phase == DownloadPhase.READY,
                        failureCode = if (phase == DownloadPhase.FAILED) {
                            DownloadFailureCode.UNKNOWN
                        } else {
                            null
                        },
                    ),
                )
            }
        }
        listOf(
            DownloadPhase.PREPARING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.VERIFYING,
            DownloadPhase.WAITING,
            DownloadPhase.PAUSED,
        ).forEach { phase ->
            assertThrows("phase=$phase verified", IllegalArgumentException::class.java) {
                calculator.record(
                    observation(
                        phase = phase,
                        blockReason = when (phase) {
                            DownloadPhase.WAITING -> DownloadBlockReason.NETWORK_UNAVAILABLE
                            DownloadPhase.PAUSED -> DownloadBlockReason.USER_PAUSED
                            else -> null
                        },
                        verificationSucceeded = true,
                    ),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculator.record(
                observation(
                    phase = DownloadPhase.DOWNLOADING,
                    failureCode = DownloadFailureCode.UNKNOWN,
                ),
            )
        }
        listOf(
            DownloadPhase.PREPARING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.VERIFYING,
            DownloadPhase.READY,
            DownloadPhase.WAITING,
            DownloadPhase.PAUSED,
        ).forEach { phase ->
            assertThrows("phase=$phase failure", IllegalArgumentException::class.java) {
                calculator.record(
                    observation(
                        phase = phase,
                        blockReason = when (phase) {
                            DownloadPhase.WAITING -> DownloadBlockReason.NETWORK_UNAVAILABLE
                            DownloadPhase.PAUSED -> DownloadBlockReason.USER_PAUSED
                            else -> null
                        },
                        failureCode = DownloadFailureCode.UNKNOWN,
                        verificationSucceeded = phase == DownloadPhase.READY,
                    ),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculator.record(
                observation(
                    phase = DownloadPhase.FAILED,
                    failureCode = DownloadFailureCode.UNKNOWN,
                    verificationSucceeded = true,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculator.record(
                observation(
                    phase = DownloadPhase.WAITING,
                    blockReason = DownloadBlockReason.USER_PAUSED,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculator.record(
                observation(
                    phase = DownloadPhase.PAUSED,
                    blockReason = DownloadBlockReason.NETWORK_UNAVAILABLE,
                ),
            )
        }
    }

    @Test
    fun waitingAndPausedClearPreviouslyProvenRates() {
        val first = calculator.record(observation(downloadedBytes = 100L))
        nowMillis = 2_000L
        val proven = calculator.record(observation(downloadedBytes = 500L), first)
        assertEquals(400L, proven.bytesPerSecond)

        listOf(
            DownloadPhase.WAITING to DownloadBlockReason.NETWORK_UNAVAILABLE,
            DownloadPhase.PAUSED to DownloadBlockReason.USER_PAUSED,
        ).forEach { (phase, reason) ->
            nowMillis += 1_000L
            val interrupted = calculator.record(
                observation(
                    phase = phase,
                    downloadedBytes = 500L,
                    blockReason = reason,
                ),
                proven,
            )

            assertNull("phase=$phase speed", interrupted.bytesPerSecond)
            assertNull("phase=$phase eta", interrupted.etaSeconds)
        }
    }

    @Test
    fun telemetrySamplesCannotBeMutatedAfterCreation() {
        val telemetry = calculator.record(observation(downloadedBytes = 100L))
        val exposed = telemetry.samples

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (exposed as MutableList<DownloadSample>).add(
                DownloadSample(generation, 2_000L, 200L),
            )
        }
        assertEquals(listOf(100L), telemetry.samples.map { it.downloadedBytes })
        assertNotNull(telemetry.samples)
    }

    @Test
    fun equalTelemetryHasStructuralEqualityAndHashing() {
        val observation = observation(downloadedBytes = 100L)

        val first = calculator.record(observation)
        val second = calculator.record(observation)
        val different = calculator.record(observation(downloadedBytes = 101L))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertFalse(first == different)
    }

    @Test
    fun restoreFactoryRebuildsExactTimestampSamplesRatesAndEta() {
        val factory = DownloadTelemetryRestoreFactory(
            sampleWindowMillis = 5_000L,
            maxSampleCount = 4,
        )
        val samples = listOf(
            DownloadSample(generation, 1_000L, 100L),
            DownloadSample(generation, 3_000L, 500L),
        )

        val restored = factory.restore(
            observation = observation(downloadedBytes = 500L, totalBytes = 1_000L),
            updatedAtMillis = 3_000L,
            samples = samples,
        )

        assertEquals(3_000L, restored.updatedAtMillis)
        assertEquals(samples, restored.samples)
        assertEquals(200L, restored.bytesPerSecond)
        assertEquals(3L, restored.etaSeconds)
    }

    @Test
    fun restoreFactoryBoundsHistoryByAgeAndCount() {
        val factory = DownloadTelemetryRestoreFactory(
            sampleWindowMillis = 5_000L,
            maxSampleCount = 2,
        )
        val samples = listOf(
            DownloadSample(generation, 0L, 0L),
            DownloadSample(generation, 2_000L, 200L),
            DownloadSample(generation, 4_000L, 400L),
            DownloadSample(generation, 6_000L, 600L),
        )

        val restored = factory.restore(
            observation = observation(downloadedBytes = 600L),
            updatedAtMillis = 6_000L,
            samples = samples,
        )

        assertEquals(listOf(4_000L, 6_000L), restored.samples.map { it.timestampMillis })
        assertEquals(100L, restored.bytesPerSecond)
    }

    @Test
    fun restoreFactoryRejectsMismatchedOrImpossibleSamples() {
        val factory = DownloadTelemetryRestoreFactory()
        val otherGeneration = DownloadGeneration(8L)

        assertThrows(IllegalArgumentException::class.java) {
            factory.restore(
                observation = observation(downloadedBytes = 100L),
                updatedAtMillis = 1_000L,
                samples = listOf(DownloadSample(otherGeneration, 1_000L, 100L)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            factory.restore(
                observation = observation(
                    phase = DownloadPhase.READY,
                    downloadedBytes = 100L,
                    verificationSucceeded = true,
                ),
                updatedAtMillis = 2_000L,
                samples = listOf(DownloadSample(generation, 1_000L, 200L)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            factory.restore(
                observation = observation(downloadedBytes = 100L),
                updatedAtMillis = 1_000L,
                samples = listOf(DownloadSample(generation, 2_000L, 100L)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            factory.restore(
                observation = observation(downloadedBytes = 100L),
                updatedAtMillis = 2_000L,
                samples = listOf(
                    DownloadSample(generation, 1_000L, 100L),
                    DownloadSample(generation, 2_000L, 90L),
                ),
            )
        }
    }

    @Test
    fun onlySafeActivePhasesExposeCancel() {
        val activePhases = listOf(
            DownloadPhase.PREPARING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.VERIFYING,
        )

        activePhases.forEach { phase ->
            assertTrue(
                "phase=$phase",
                calculator.record(observation(phase = phase)).canCancel,
            )
            assertFalse(
                "phase=$phase cancellation blocked",
                calculator.record(
                    observation(phase = phase, cancellationAllowed = false),
                ).canCancel,
            )
        }
        listOf(DownloadPhase.READY, DownloadPhase.WAITING, DownloadPhase.PAUSED).forEach { phase ->
            val telemetry = calculator.record(
                observation(
                    phase = phase,
                    downloadedBytes = if (phase == DownloadPhase.READY) 1_000L else 0L,
                    verificationSucceeded = phase == DownloadPhase.READY,
                    blockReason = when (phase) {
                        DownloadPhase.WAITING -> DownloadBlockReason.WIFI_REQUIRED
                        DownloadPhase.PAUSED -> DownloadBlockReason.USER_PAUSED
                        else -> null
                    },
                ),
            )
            assertFalse("phase=$phase", telemetry.canCancel)
        }
    }

    private fun observation(
        generation: DownloadGeneration = this.generation,
        phase: DownloadPhase = DownloadPhase.DOWNLOADING,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = 1_000L,
        network: DownloadNetwork = DownloadNetwork.WIFI,
        blockReason: DownloadBlockReason? = null,
        failureCode: DownloadFailureCode? = null,
        verificationSucceeded: Boolean = false,
        cancellationAllowed: Boolean = true,
        retryAllowed: Boolean = phase == DownloadPhase.FAILED,
    ) = DownloadObservation(
        generation = generation,
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

    private val generation = DownloadGeneration(7L)
}
