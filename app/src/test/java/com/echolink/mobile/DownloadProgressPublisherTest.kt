package com.echolink.mobile

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressPublisherTest {
    @Test
    fun firstCallbackPublishesActualPartialOffsetAsFreshBaseline() {
        val publisher = publisher()
        val attempt = attempt(generation = 7L, work = DownloadWorkIdentity(11L, 12L))

        val publication = publisher.offer(
            attempt = attempt,
            observation = observation(attempt, downloadedBytes = 700L, totalBytes = 1_000L),
            timestampMillis = 1_000L,
        )

        assertNotNull(publication)
        assertEquals(attempt, publication?.attempt)
        assertEquals(700L, publication?.telemetry?.downloadedBytes)
        assertEquals(attempt.generation, publication?.telemetry?.generation)
        assertEquals(
            listOf(DownloadSample(attempt.generation, 1_000L, 700L)),
            publication?.telemetry?.samples,
        )
        assertNull(publication?.telemetry?.bytesPerSecond)
        assertNull(publication?.telemetry?.etaSeconds)
        assertFalse(publication?.isFinalByteCount == true)
    }

    @Test
    fun firstPhaseCallbackAlsoKeepsTheActualOffsetAsOneFreshSample() {
        val publisher = publisher()
        val attempt = attempt()

        val publication = publisher.offer(
            attempt = attempt,
            observation = observation(
                attempt = attempt,
                phase = DownloadPhase.PREPARING,
                downloadedBytes = 777L,
                totalBytes = 1_000L,
            ),
            timestampMillis = 2_000L,
        )

        assertEquals(DownloadPhase.PREPARING, publication?.telemetry?.phase)
        assertEquals(
            listOf(DownloadSample(attempt.generation, 2_000L, 777L)),
            publication?.telemetry?.samples,
        )
    }

    @Test
    fun byteThresholdPublishesTwiceInsideTheSameIntegerPercent() {
        val publisher = publisher(minimumByteDelta = 100L, minimumTimeIntervalMillis = 10_000L)
        val attempt = attempt()
        val first = publisher.offer(
            attempt,
            observation(attempt, downloadedBytes = 100L, totalBytes = 100_000L),
            timestampMillis = 1_000L,
        )

        assertNull(
            publisher.offer(
                attempt,
                observation(attempt, downloadedBytes = 150L, totalBytes = 100_000L),
                timestampMillis = 1_100L,
            ),
        )
        val second = publisher.offer(
            attempt,
            observation(attempt, downloadedBytes = 200L, totalBytes = 100_000L),
            timestampMillis = 1_200L,
        )

        assertEquals(0, first?.telemetry?.percent)
        assertEquals(0, second?.telemetry?.percent)
        assertEquals(listOf(100L, 200L), second?.telemetry?.samples?.map { it.downloadedBytes })
    }

    @Test
    fun timeThresholdPublishesInsideTheSameIntegerPercent() {
        val publisher = publisher(minimumByteDelta = 10_000L, minimumTimeIntervalMillis = 500L)
        val attempt = attempt()
        val first = publisher.offer(
            attempt,
            observation(attempt, downloadedBytes = 100L, totalBytes = 100_000L),
            timestampMillis = 1_000L,
        )

        assertNull(
            publisher.offer(
                attempt,
                observation(attempt, downloadedBytes = 101L, totalBytes = 100_000L),
                timestampMillis = 1_499L,
            ),
        )
        val second = publisher.offer(
            attempt,
            observation(attempt, downloadedBytes = 102L, totalBytes = 100_000L),
            timestampMillis = 1_500L,
        )

        assertEquals(0, first?.telemetry?.percent)
        assertEquals(0, second?.telemetry?.percent)
        assertEquals(102L, second?.telemetry?.downloadedBytes)
    }

    @Test
    fun tinyRapidChangesStaySuppressedAndNeverEnterThePublishedSamples() {
        val publisher = publisher(minimumByteDelta = 100L, minimumTimeIntervalMillis = 1_000L)
        val attempt = attempt()
        publisher.offer(
            attempt,
            observation(attempt, downloadedBytes = 100L),
            timestampMillis = 1_000L,
        )

        assertNull(publisher.offer(attempt, observation(attempt, 101L), 1_100L))
        assertNull(publisher.offer(attempt, observation(attempt, 102L), 1_200L))
        val publication = publisher.offer(attempt, observation(attempt, 200L), 1_300L)

        assertEquals(
            listOf(100L, 200L),
            publication?.telemetry?.samples?.map { it.downloadedBytes },
        )
    }

    @Test
    fun phaseChangesKnownCompletionAndExplicitFinalEventsAreForced() {
        val publisher = publisher(
            minimumByteDelta = 10_000L,
            minimumTimeIntervalMillis = 10_000L,
        )
        val attempt = attempt()
        publisher.offer(
            attempt,
            observation(attempt, downloadedBytes = 900L, totalBytes = 1_000L),
            timestampMillis = 1_000L,
        )

        val complete = publisher.offer(
            attempt,
            observation(attempt, downloadedBytes = 1_000L, totalBytes = 1_000L),
            timestampMillis = 1_001L,
        )
        val verifying = publisher.offer(
            attempt,
            observation(
                attempt = attempt,
                phase = DownloadPhase.VERIFYING,
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
            ),
            timestampMillis = 1_001L,
        )
        val explicitFinal = publisher.offer(
            attempt,
            observation(
                attempt = attempt,
                phase = DownloadPhase.VERIFYING,
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
            ),
            timestampMillis = 1_001L,
            isFinalByteCount = true,
        )

        assertTrue(complete?.isFinalByteCount == true)
        assertEquals(DownloadPhase.VERIFYING, verifying?.telemetry?.phase)
        assertNull(explicitFinal)
    }

    @Test
    fun duplicateCompletionBurstPublishesFinalOnlyOncePerPhaseAndAttempt() {
        val publisher = publisher(
            minimumByteDelta = 1L,
            minimumTimeIntervalMillis = 1L,
        )
        val attempt = attempt()

        val first = publisher.offer(
            attempt,
            observation(attempt, downloadedBytes = 1_000L, totalBytes = 1_000L),
            timestampMillis = 1_000L,
            isFinalByteCount = true,
        )
        val duplicates = (1..10_000).map { offset ->
            publisher.offer(
                attempt,
                observation(attempt, downloadedBytes = 1_000L, totalBytes = 1_000L),
                timestampMillis = 1_000L + offset,
                isFinalByteCount = true,
            )
        }
        val verifying = publisher.offer(
            attempt,
            observation(
                attempt,
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
                phase = DownloadPhase.VERIFYING,
            ),
            timestampMillis = 12_000L,
            isFinalByteCount = true,
        )

        assertTrue(first?.isFinalByteCount == true)
        assertTrue(duplicates.all { it == null })
        assertNotNull(verifying)
        assertFalse(verifying?.isFinalByteCount == true)
    }

    @Test
    fun targetWorkAndGenerationKeepIndependentFreshBaselines() {
        val publisher = publisher(minimumByteDelta = 1_000L, minimumTimeIntervalMillis = 10_000L)
        val first = attempt(generation = 1L, work = DownloadWorkIdentity(1L, 1L))
        val otherWork = first.copy(workIdentity = DownloadWorkIdentity(2L, 2L))
        val otherGeneration = first.copy(generation = DownloadGeneration(2L))
        val otherTarget = first.copy(
            target = DownloadTargetKey(
                AiModelFamily.LEGACY_QWEN,
                AiModelTier.LEGACY_COMPACT,
                BackendKind.LEGACY_LLAMA_CPU,
            ),
        )

        val firstPublication = publisher.offer(first, observation(first, 100L), 1_000L)
        assertNull(publisher.offer(first, observation(first, 101L), 1_001L))
        val publications = listOf(
            publisher.offer(otherWork, observation(otherWork, 200L), 1_002L),
            publisher.offer(otherGeneration, observation(otherGeneration, 300L), 1_003L),
            publisher.offer(otherTarget, observation(otherTarget, 400L), 1_004L),
        )

        assertEquals(first, firstPublication?.attempt)
        publications.forEach { publication ->
            assertNotNull(publication)
            assertEquals(1, publication?.telemetry?.samples?.size)
            assertEquals(publication?.attempt?.generation, publication?.telemetry?.generation)
        }
        assertEquals(otherWork, publications[0]?.attempt)
        assertEquals(otherGeneration, publications[1]?.attempt)
        assertEquals(otherTarget, publications[2]?.attempt)
    }

    @Test
    fun byteAndTimeRollbackAreIgnoredWithoutPoisoningLaterProgress() {
        val publisher = publisher(minimumByteDelta = 100L, minimumTimeIntervalMillis = 10_000L)
        val attempt = attempt()
        publisher.offer(attempt, observation(attempt, 100L), 1_000L)

        assertNull(publisher.offer(attempt, observation(attempt, 99L), 1_100L))
        assertNull(publisher.offer(attempt, observation(attempt, 200L), 900L))
        val publication = publisher.offer(attempt, observation(attempt, 200L), 1_200L)

        assertEquals(200L, publication?.telemetry?.downloadedBytes)
        assertEquals(listOf(100L, 200L), publication?.telemetry?.samples?.map { it.downloadedBytes })
    }

    @Test
    fun attemptGenerationMustMatchObservationGeneration() {
        val publisher = publisher()
        val attempt = attempt(generation = 2L)

        assertThrows(IllegalArgumentException::class.java) {
            publisher.offer(
                attempt,
                observation(attempt.copy(generation = DownloadGeneration(3L)), 100L),
                1_000L,
            )
        }
    }

    @Test
    fun trackedAttemptStateIsBoundedAndEvictedAttemptsRestartWithRealBaseline() {
        val publisher = publisher(
            minimumByteDelta = 100L,
            minimumTimeIntervalMillis = 10_000L,
            maxTrackedAttempts = 2,
        )
        val first = attempt(work = DownloadWorkIdentity(1L, 1L))
        val second = attempt(work = DownloadWorkIdentity(2L, 2L))
        val third = attempt(work = DownloadWorkIdentity(3L, 3L))
        publisher.offer(first, observation(first, 0L), 1_000L)
        publisher.offer(second, observation(second, 0L), 1_001L)
        publisher.offer(third, observation(third, 0L), 1_002L)

        assertEquals(2, publisher.trackedAttemptCount)
        val restarted = publisher.offer(first, observation(first, 1L), 1_003L)

        assertNotNull(restarted)
        assertEquals(listOf(1L), restarted?.telemetry?.samples?.map { it.downloadedBytes })
        assertEquals(2, publisher.trackedAttemptCount)
    }

    @Test
    fun thresholdsAndCapacityRejectUnboundedConfigurations() {
        assertThrows(IllegalArgumentException::class.java) {
            publisher(minimumByteDelta = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            publisher(minimumTimeIntervalMillis = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            publisher(maxTrackedAttempts = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            publisher(maxTrackedAttempts = DownloadProgressPublisher.DEFAULT_MAX_TRACKED_ATTEMPTS + 1)
        }
    }

    @Test
    fun publisherContractCannotCarryUserTextPathsUrlsOrExceptions() {
        val dataTypes = listOf(
            DownloadProgressPublication::class.java,
            DownloadAttempt::class.java,
        )
        val forbidden = setOf(
            String::class.java,
            CharSequence::class.java,
            Throwable::class.java,
            java.io.File::class.java,
            java.nio.file.Path::class.java,
            java.net.URI::class.java,
            java.net.URL::class.java,
        )

        dataTypes.forEach { type ->
            type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
                assertFalse(
                    "${type.simpleName}.${field.name}",
                    forbidden.any { it.isAssignableFrom(field.type) },
                )
            }
            type.declaredConstructors.forEach { constructor ->
                constructor.parameterTypes.forEach { parameter ->
                    assertFalse(
                        "${type.simpleName} constructor",
                        forbidden.any { it.isAssignableFrom(parameter) },
                    )
                }
            }
        }
        DownloadProgressPublisher::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .flatMap { it.parameterTypes.toList() + it.returnType }
            .forEach { exposed ->
                assertFalse(
                    "publisher method ${exposed.name}",
                    forbidden.any { it.isAssignableFrom(exposed) },
                )
            }
    }

    private fun publisher(
        minimumByteDelta: Long = 100L,
        minimumTimeIntervalMillis: Long = 1_000L,
        maxTrackedAttempts: Int = 8,
    ) = DownloadProgressPublisher(
        minimumByteDelta = minimumByteDelta,
        minimumTimeIntervalMillis = minimumTimeIntervalMillis,
        maxTrackedAttempts = maxTrackedAttempts,
    )

    private fun attempt(
        target: DownloadTargetKey = this.target,
        generation: Long = 1L,
        work: DownloadWorkIdentity = DownloadWorkIdentity(7L, 8L),
    ) = DownloadAttempt(target, DownloadGeneration(generation), work)

    private fun observation(
        attempt: DownloadAttempt,
        downloadedBytes: Long,
        totalBytes: Long? = 1_000L,
        phase: DownloadPhase = DownloadPhase.DOWNLOADING,
    ) = DownloadObservation(
        generation = attempt.generation,
        phase = phase,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        network = DownloadNetwork.WIFI,
        cancellationAllowed = phase in setOf(
            DownloadPhase.PREPARING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.VERIFYING,
        ),
    )

    private val target = DownloadTargetKey(
        AiModelFamily.GEMMA,
        AiModelTier.FAST,
        BackendKind.LITERT_CPU,
    )
}
