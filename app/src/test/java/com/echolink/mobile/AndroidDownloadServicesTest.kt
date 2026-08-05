package com.echolink.mobile

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDownloadServicesTest {
    @Test
    fun processSingletonNormalizesContextAndCreatesOnlyOneInstance() {
        val createdWith = mutableListOf<String>()
        val singleton = ProcessSingleton<String, Any>(
            applicationContext = { value -> value.substringBefore('/') },
            create = { normalized ->
                createdWith += normalized
                Any()
            },
        )

        val first = singleton.get("application/first")
        val second = singleton.get("application/second")

        assertSame(first, second)
        assertEquals(listOf("application"), createdWith)
    }

    @Test
    fun familyPolicyUsesTheExactManagerPreferenceForEachTarget() {
        var gemmaReads = 0
        var legacyReads = 0
        val policy = ExactFamilyDownloadTargetPolicy(
            gemmaPolicy = {
                gemmaReads += 1
                DownloadSchedulingNetworkPolicy.ALLOW_MOBILE_DATA
            },
            legacyPolicy = {
                legacyReads += 1
                DownloadSchedulingNetworkPolicy.WIFI_ONLY
            },
        )

        assertEquals(
            DownloadSchedulingNetworkPolicy.ALLOW_MOBILE_DATA,
            policy.policyFor(gemmaTarget),
        )
        assertEquals(
            DownloadSchedulingNetworkPolicy.WIFI_ONLY,
            policy.policyFor(legacyTarget),
        )
        assertEquals(1, gemmaReads)
        assertEquals(1, legacyReads)
    }

    @Test
    fun exactFamilyRoutersUseCanonicalGemmaSelectionAndExactLegacyTier() {
        var partialGemma: AiRuntimeSelection? = null
        var partialLegacy: Qwen3Tier? = null
        var readyGemma: AiRuntimeSelection? = null
        var readyLegacy: Qwen3Tier? = null
        val partialBytes = ExactDownloadPartialBytes(
            gemmaPartialBytes = { selection ->
                partialGemma = selection
                111L
            },
            legacyPartialBytes = { tier ->
                partialLegacy = tier
                222L
            },
        )
        val ready = ExactDownloadReadyReconciler(
            gemmaReady = { selection ->
                readyGemma = selection
                true
            },
            legacyReady = { tier ->
                readyLegacy = tier
                true
            },
        )

        assertEquals(111L, partialBytes.bytesFor(gemmaTarget))
        assertEquals(222L, partialBytes.bytesFor(legacyTarget))
        assertTrue(ready.isActiveAndSelfTested(gemmaTarget, DownloadGeneration(9L)))
        assertTrue(ready.isActiveAndSelfTested(legacyTarget, DownloadGeneration(9L)))
        assertEquals(DownloadTargetCatalog.selectionFor(gemmaTarget), partialGemma)
        assertEquals(Qwen3Tier.BALANCED, partialLegacy)
        assertEquals(DownloadTargetCatalog.selectionFor(gemmaTarget), readyGemma)
        assertEquals(Qwen3Tier.BALANCED, readyLegacy)
    }

    @Test
    fun partialSnapshotUsesCanonicalTotalAndOneCurrentNetworkSample() {
        val network = RecordingNetworkReader(DownloadNetwork.ETHERNET)
        val requested = mutableListOf<DownloadTargetKey>()
        val provider = CanonicalDownloadPartialSnapshotProvider(
            networkReader = network,
            partialBytes = DownloadTargetPartialBytes { target ->
                requested += target
                123L
            },
        )

        val snapshot = provider.snapshot(gemmaTarget)

        assertEquals(listOf(gemmaTarget), requested)
        assertEquals(123L, snapshot.downloadedBytes)
        assertEquals(GemmaModelCatalog.fast.sizeBytes, snapshot.totalBytes)
        assertEquals(DownloadNetwork.ETHERNET, snapshot.network)
        assertEquals(1, network.reads)
    }

    @Test
    fun networkDecisionBindsPolicyAndEvaluationToOneReaderSample() = withHarness { harness ->
        harness.network.network = DownloadNetwork.MOBILE

        val decision = harness.core.networkDecision(gemmaTarget)

        assertEquals(DownloadNetwork.MOBILE, decision.currentNetwork)
        assertEquals(
            DownloadNetworkPolicyEvaluation.Waiting(DownloadBlockReason.WIFI_REQUIRED),
            decision.evaluation,
        )
        assertEquals(1, harness.network.reads)
    }

    @Test
    fun publishFencesStaleAttemptAndPersistsOnlyBoundedEmissions() = withHarness { harness ->
        val attempt = harness.core.ensure(gemmaTarget)
        val stale = attempt.copy(
            workIdentity = DownloadWorkIdentity.from(
                UUID.fromString("baa28358-63a5-4506-a501-fbab7c72a9e0"),
            ),
        )
        harness.clock.now = 100L
        val first = downloading(attempt, downloadedBytes = 100L)

        assertEquals(
            DownloadPublishResult.Stale,
            harness.core.publish(stale, first, finalByteCount = false),
        )
        assertEquals(0, harness.publisher.trackedAttemptCount)
        assertEquals(DownloadPhase.PREPARING, harness.store.read(gemmaTarget)?.telemetry?.phase)

        assertEquals(
            DownloadPublishResult.Persisted,
            harness.core.publish(attempt, first, finalByteCount = false),
        )
        assertEquals(100L, harness.store.read(gemmaTarget)?.telemetry?.downloadedBytes)

        harness.clock.now = 200L
        assertEquals(
            DownloadPublishResult.Throttled,
            harness.core.publish(
                attempt,
                downloading(attempt, downloadedBytes = 101L),
                finalByteCount = false,
            ),
        )
        assertEquals(100L, harness.store.read(gemmaTarget)?.telemetry?.downloadedBytes)

        harness.clock.now = 300L
        assertEquals(
            DownloadPublishResult.Persisted,
            harness.core.publish(
                attempt,
                downloading(attempt, downloadedBytes = 102L),
                finalByteCount = true,
            ),
        )
        assertEquals(102L, harness.store.read(gemmaTarget)?.telemetry?.downloadedBytes)
    }

    @Test
    fun rejectedPersistenceResetsTrackingSoCorrectedObservationIsNotSuppressed() =
        withHarness { harness ->
            val attempt = harness.core.ensure(gemmaTarget)
            harness.clock.now = 100L
            val wrongTotal = DownloadObservation(
                generation = attempt.generation,
                phase = DownloadPhase.DOWNLOADING,
                downloadedBytes = 100L,
                totalBytes = GemmaModelCatalog.fast.sizeBytes + 1L,
                network = DownloadNetwork.WIFI,
            )

            assertEquals(
                DownloadPublishResult.Rejected,
                harness.core.publish(attempt, wrongTotal, finalByteCount = false),
            )
            assertEquals(0, harness.publisher.trackedAttemptCount)
            assertEquals(
                DownloadPhase.PREPARING,
                harness.store.read(gemmaTarget)?.telemetry?.phase,
            )

            assertEquals(
                DownloadPublishResult.Persisted,
                harness.core.publish(
                    attempt,
                    downloading(attempt, downloadedBytes = 100L),
                    finalByteCount = false,
                ),
            )
            assertEquals(100L, harness.store.read(gemmaTarget)?.telemetry?.downloadedBytes)
        }

    @Test
    fun persistenceExceptionResetsTrackingAndRethrowsOriginalFailure() =
        withHarness { harness ->
            val attempt = harness.core.ensure(gemmaTarget)
            val failure = IOException("snapshot unavailable")
            harness.committer.nextFailure = failure
            harness.clock.now = 100L

            val thrown = assertThrows(IOException::class.java) {
                harness.core.publish(
                    attempt,
                    downloading(attempt, downloadedBytes = 100L),
                    finalByteCount = false,
                )
            }

            assertSame(failure, thrown)
            assertEquals(0, harness.publisher.trackedAttemptCount)
            assertEquals(
                DownloadPhase.PREPARING,
                harness.store.read(gemmaTarget)?.telemetry?.phase,
            )
            assertEquals(
                DownloadPublishResult.Persisted,
                harness.core.publish(
                    attempt,
                    downloading(attempt, downloadedBytes = 100L),
                    finalByteCount = false,
                ),
            )
        }

    @Test
    fun lifecycleFacadeKeepsExactIdentityThroughCancelRetryAndReadyCommit() =
        withHarness { harness ->
            val first = harness.core.ensure(gemmaTarget)
            assertTrue(harness.core.isCurrent(first))
            assertEquals(
                DownloadPhase.PREPARING,
                harness.core.restoreTelemetry(gemmaTarget)?.phase,
            )

            harness.clock.now += 1L
            assertTrue(harness.core.cancel(first))
            assertFalse(harness.core.isCurrent(first))

            harness.clock.now += 1L
            val retried = checkNotNull(harness.core.retry(first))
            assertEquals(DownloadGeneration(2L), retried.generation)
            assertTrue(harness.core.isCurrent(retried))

            harness.clock.now += 1L
            assertEquals(
                DownloadPublishResult.Persisted,
                harness.core.publish(
                    retried,
                    verifying(retried),
                    finalByteCount = true,
                ),
            )
            var activations = 0
            harness.clock.now += 1L
            assertTrue(
                harness.core.commitReadyIfCurrent(retried, ready(retried)) {
                    harness.readyReconciler.ready = true
                    activations += 1
                },
            )
            assertFalse(
                harness.core.commitReadyIfCurrent(retried, ready(retried)) {
                    activations += 1
                },
            )

            assertEquals(1, activations)
            assertEquals(DownloadPhase.READY, harness.core.restoreTelemetry(gemmaTarget)?.phase)
            assertEquals(listOf(first.workIdentity), harness.scheduler.cancelled.take(1))
            assertNull(harness.core.retry(retried))
        }

    @Test
    fun fallbackRetryUsesARealFreshAttemptForAHiddenTerminalRecord() =
        withHarness { harness ->
            val first = harness.core.ensure(gemmaTarget)
            assertTrue(harness.core.failCurrent(first, DownloadFailureCode.UNKNOWN))

            val retried = requireNotNull(harness.core.retryOrEnsure(gemmaTarget))

            assertEquals(DownloadGeneration(first.generation.value + 1L), retried.generation)
            assertFalse(first.workIdentity == retried.workIdentity)
            assertTrue(harness.core.isCurrent(retried))
        }

    @Test
    fun fallbackRetryCreatesTheFirstAttemptWhenNoDurableRecordExists() =
        withHarness { harness ->
            val attempt = requireNotNull(harness.core.retryOrEnsure(gemmaTarget))

            assertEquals(DownloadGeneration(1L), attempt.generation)
            assertTrue(harness.core.isCurrent(attempt))
        }

    private fun withHarness(block: (Harness) -> Unit) {
        val directory = Files.createTempDirectory("manuscript-v2-download-services-").toFile()
        try {
            val clock = MutableClock(10L)
            val network = RecordingNetworkReader(DownloadNetwork.WIFI)
            val readyReconciler = MutableReadyReconciler()
            val committer = ToggleAtomicSnapshotCommitter()
            val store = DownloadTelemetryStore(
                directory = File(directory, "download_telemetry_v1"),
                clock = clock::read,
                readyReconciler = readyReconciler,
                committer = committer,
            )
            val scheduler = RecordingScheduler()
            val identities = ArrayDeque(
                listOf(
                    work("7064805e-e73f-4d37-ae86-b4553420fd27"),
                    work("21fed817-2696-4e80-b246-1592809754e7"),
                ),
            )
            val coordinator = DownloadLifecycleCoordinator(
                store = store,
                scheduler = scheduler,
                workIdentitySource = DownloadWorkIdentitySource { identities.removeFirst() },
                partialSnapshotProvider = CanonicalDownloadPartialSnapshotProvider(
                    networkReader = network,
                    partialBytes = DownloadTargetPartialBytes { 0L },
                ),
                telemetryCalculator = DownloadTelemetryCalculator(clock = clock::read),
            )
            val publisher = DownloadProgressPublisher(
                minimumByteDelta = 100L,
                minimumTimeIntervalMillis = 1_000L,
            )
            val core = AndroidDownloadServicesCore(
                coordinator = coordinator,
                progressPublisher = publisher,
                networkReader = network,
                targetPolicy = ExactFamilyDownloadTargetPolicy(
                    gemmaPolicy = { DownloadSchedulingNetworkPolicy.WIFI_ONLY },
                    legacyPolicy = { DownloadSchedulingNetworkPolicy.ALLOW_MOBILE_DATA },
                ),
                clock = clock::read,
            )
            block(
                Harness(
                    clock,
                    network,
                    readyReconciler,
                    store,
                    scheduler,
                    publisher,
                    committer,
                    core,
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun downloading(
        attempt: DownloadAttempt,
        downloadedBytes: Long,
    ): DownloadObservation = DownloadObservation(
        generation = attempt.generation,
        phase = DownloadPhase.DOWNLOADING,
        downloadedBytes = downloadedBytes,
        totalBytes = GemmaModelCatalog.fast.sizeBytes,
        network = DownloadNetwork.WIFI,
    )

    private fun verifying(attempt: DownloadAttempt): DownloadObservation = DownloadObservation(
        generation = attempt.generation,
        phase = DownloadPhase.VERIFYING,
        downloadedBytes = GemmaModelCatalog.fast.sizeBytes,
        totalBytes = GemmaModelCatalog.fast.sizeBytes,
        network = DownloadNetwork.WIFI,
    )

    private fun ready(attempt: DownloadAttempt): DownloadObservation = DownloadObservation(
        generation = attempt.generation,
        phase = DownloadPhase.READY,
        downloadedBytes = GemmaModelCatalog.fast.sizeBytes,
        totalBytes = GemmaModelCatalog.fast.sizeBytes,
        network = DownloadNetwork.WIFI,
        verificationSucceeded = true,
    )

    private fun work(value: String): DownloadWorkIdentity =
        DownloadWorkIdentity.from(UUID.fromString(value))

    private data class Harness(
        val clock: MutableClock,
        val network: RecordingNetworkReader,
        val readyReconciler: MutableReadyReconciler,
        val store: DownloadTelemetryStore,
        val scheduler: RecordingScheduler,
        val publisher: DownloadProgressPublisher,
        val committer: ToggleAtomicSnapshotCommitter,
        val core: AndroidDownloadServicesCore,
    )

    private class ToggleAtomicSnapshotCommitter : AtomicSnapshotCommitter {
        var nextFailure: IOException? = null

        override fun commit(temporary: Path, destination: Path) {
            nextFailure?.let { failure ->
                nextFailure = null
                throw failure
            }
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private class MutableClock(var now: Long) {
        fun read(): Long = now
    }

    private class MutableReadyReconciler(
        var ready: Boolean = false,
    ) : DownloadReadyReconciler {
        override fun isActiveAndSelfTested(
            target: DownloadTargetKey,
            generation: DownloadGeneration,
        ): Boolean = ready
    }

    private class RecordingNetworkReader(var network: DownloadNetwork) : DownloadNetworkReader {
        var reads = 0

        override fun current(): DownloadNetwork {
            reads += 1
            return network
        }
    }

    private class RecordingScheduler : DownloadAttemptScheduler {
        val scheduled = mutableListOf<DownloadAttempt>()
        val cancelled = mutableListOf<DownloadWorkIdentity>()

        override fun ensureScheduled(attempt: DownloadAttempt) {
            scheduled += attempt
        }

        override fun cancel(workIdentity: DownloadWorkIdentity) {
            cancelled += workIdentity
        }
    }

    private companion object {
        val gemmaTarget = DownloadTargetKey(
            AiModelFamily.GEMMA,
            AiModelTier.FAST,
            BackendKind.LITERT_CPU,
        )
        val legacyTarget = DownloadTargetKey(
            AiModelFamily.LEGACY_QWEN,
            AiModelTier.LEGACY_BALANCED,
            BackendKind.LEGACY_LLAMA_CPU,
        )
    }
}
