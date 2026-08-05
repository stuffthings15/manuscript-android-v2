package com.echolink.mobile

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadLifecycleCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var nowMillis = 10_000L
    private val target = DownloadTargetKey(
        AiModelFamily.GEMMA,
        AiModelTier.FAST,
        BackendKind.LITERT_CPU,
    )
    private val otherTarget = DownloadTargetKey(
        AiModelFamily.LEGACY_QWEN,
        AiModelTier.LEGACY_COMPACT,
        BackendKind.LEGACY_LLAMA_CPU,
    )
    private val firstWork = work("d7f4b140-78d1-46d2-8674-00f8dc31a9ea")
    private val secondWork = work("ac0958ea-bef0-43ef-a104-285fcf0983af")
    private val thirdWork = work("e9cc0be7-8239-410e-b656-7345414ea039")

    @Test
    fun firstEnsureReservesTypedAttemptAndSchedulesItOnce() {
        val harness = harness("first-ensure")

        val attempt = harness.coordinator.ensure(target)

        assertEquals(target, attempt.target)
        assertEquals(DownloadGeneration(1L), attempt.generation)
        assertEquals(firstWork, attempt.workIdentity)
        assertEquals(listOf(attempt), harness.scheduler.scheduled)
        assertEquals(attempt, harness.coordinator.ensure(target))
        assertEquals(1, harness.scheduler.scheduled.size)
        assertEquals(2, harness.scheduler.ensureCalls)
        assertEquals(DownloadPhase.PREPARING, harness.store.read(target)?.telemetry?.phase)
    }

    @Test
    fun ensureRestoresEveryActiveAndWaitingAttemptWithoutSchedulingDuplicateWork() {
        listOf(
            DownloadPhase.PREPARING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.VERIFYING,
            DownloadPhase.WAITING,
        ).forEach { phase ->
            val directory = temporaryFolder.newFolder("existing-$phase")
            val phaseStore = store(directory)
            val existing = seedRecord(
                store = phaseStore,
                target = target,
                workIdentity = firstWork,
                phase = phase,
            )
            val scheduler = RecordingScheduler()
            val coordinator = coordinator(
                store = store(directory),
                scheduler = scheduler,
                identities = ArrayDeque(listOf(secondWork)),
                partials = MutablePartialSnapshotProvider(),
            )

            val expected = DownloadAttempt.from(existing)
            assertEquals(expected, coordinator.ensure(target))
            assertEquals(expected, coordinator.ensure(target))
            assertEquals(listOf(expected), scheduler.scheduled)
            assertEquals(2, scheduler.ensureCalls)
        }
    }

    @Test
    fun publishAcceptsOnlyTheExactCurrentTargetGenerationAndWorkIdentity() {
        val harness = harness("exact-publish")
        val current = harness.coordinator.ensure(target)
        nowMillis += 1_000L
        val downloading = observation(
            generation = current.generation,
            phase = DownloadPhase.DOWNLOADING,
            downloadedBytes = 200L,
        )

        assertFalse(
            harness.coordinator.publish(
                current.copy(generation = DownloadGeneration(2L)),
                downloading.copy(generation = DownloadGeneration(2L)),
            ),
        )
        assertFalse(
            harness.coordinator.publish(
                current.copy(workIdentity = secondWork),
                downloading,
            ),
        )
        assertFalse(
            harness.coordinator.publish(
                current.copy(target = otherTarget),
                downloading,
            ),
        )
        assertFalse(
            harness.coordinator.publish(
                current,
                downloading.copy(generation = DownloadGeneration(2L)),
            ),
        )

        assertTrue(harness.coordinator.publish(current, downloading))
        assertEquals(200L, harness.store.read(target)?.telemetry?.downloadedBytes)
    }

    @Test
    fun isCurrentRequiresExactLiveTargetGenerationAndWorkIdentity() {
        val harness = harness("is-current")
        val current = harness.coordinator.ensure(target)

        assertTrue(harness.coordinator.isCurrent(current))
        assertFalse(
            harness.coordinator.isCurrent(
                current.copy(generation = DownloadGeneration(2L)),
            ),
        )
        assertFalse(
            harness.coordinator.isCurrent(current.copy(workIdentity = secondWork)),
        )
        assertFalse(
            harness.coordinator.isCurrent(current.copy(target = otherTarget)),
        )

        nowMillis += 1L
        assertTrue(harness.coordinator.cancel(current))
        assertFalse(harness.coordinator.isCurrent(current))
    }

    @Test
    fun coordinatorExposesNoNonIdempotentCommitMethod() {
        assertFalse(
            DownloadLifecycleCoordinator::class.java.declaredMethods.any {
                it.name == "commitIfCurrent"
            },
        )
    }

    @Test
    fun commitReadyRequiresExactVerifyingAttemptAndValidReadyObservation() {
        val harness = harness("commit-ready-contract")
        val attempt = harness.coordinator.ensure(target)
        val ready = observation(
            generation = attempt.generation,
            phase = DownloadPhase.READY,
            downloadedBytes = 1_000L,
        )
        var actionRuns = 0

        assertThrows(IllegalArgumentException::class.java) {
            harness.coordinator.commitReadyIfCurrent(
                attempt,
                ready.copy(generation = DownloadGeneration(2L)),
            ) {
                actionRuns += 1
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            harness.coordinator.commitReadyIfCurrent(
                attempt,
                observation(
                    generation = attempt.generation,
                    phase = DownloadPhase.VERIFYING,
                    downloadedBytes = 1_000L,
                ),
            ) {
                actionRuns += 1
            }
        }
        assertFalse(
            harness.coordinator.commitReadyIfCurrent(attempt, ready) {
                actionRuns += 1
            },
        )

        assertEquals(0, actionRuns)
        assertEquals(DownloadPhase.PREPARING, harness.store.read(target)?.telemetry?.phase)
    }

    @Test
    fun commitReadyRunsActionAndPersistsReadyBeforeCancelCanEnter() {
        val harness = harness("commit-ready-cancel-race")
        val attempt = harness.coordinator.ensure(target)
        nowMillis += 1L
        assertTrue(
            harness.coordinator.publish(
                attempt,
                observation(
                    generation = attempt.generation,
                    phase = DownloadPhase.VERIFYING,
                    downloadedBytes = 1_000L,
                ),
            ),
        )
        nowMillis += 1L
        val ready = observation(
            generation = attempt.generation,
            phase = DownloadPhase.READY,
            downloadedBytes = 1_000L,
        )
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val cancelCallStarted = CountDownLatch(1)
        var actionRuns = 0
        val pool = Executors.newFixedThreadPool(2)
        val commit = pool.submit<Boolean> {
            harness.coordinator.commitReadyIfCurrent(attempt, ready) {
                actionRuns += 1
                harness.readyReconciler.activeAndSelfTested = true
                actionEntered.countDown()
                check(releaseAction.await(10L, TimeUnit.SECONDS))
            }
        }
        assertTrue(actionEntered.await(10L, TimeUnit.SECONDS))
        val cancel = pool.submit<Boolean> {
            cancelCallStarted.countDown()
            harness.coordinator.cancel(attempt)
        }
        assertTrue(cancelCallStarted.await(10L, TimeUnit.SECONDS))
        assertFalse(cancel.isDone)

        releaseAction.countDown()
        assertTrue(commit.get(10L, TimeUnit.SECONDS))
        assertFalse(cancel.get(10L, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(pool.awaitTermination(10L, TimeUnit.SECONDS))

        assertEquals(1, actionRuns)
        assertTrue(harness.scheduler.cancelled.isEmpty())
        val stored = harness.store.read(target)?.telemetry
        assertEquals(DownloadPhase.READY, stored?.phase)
        assertTrue(stored?.verificationSucceeded == true)
    }

    @Test
    fun cancelThatOwnsTheLockPreventsLaterReadyCommitAction() {
        val harness = harness("cancel-before-commit")
        val attempt = harness.coordinator.ensure(target)
        nowMillis += 1L
        assertTrue(
            harness.coordinator.publish(
                attempt,
                observation(
                    generation = attempt.generation,
                    phase = DownloadPhase.VERIFYING,
                    downloadedBytes = 1_000L,
                ),
            ),
        )
        val ready = observation(
            generation = attempt.generation,
            phase = DownloadPhase.READY,
            downloadedBytes = 1_000L,
        )
        val cancelInsideLock = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val commitCallStarted = CountDownLatch(1)
        var actionRuns = 0
        harness.scheduler.beforeCancel = {
            cancelInsideLock.countDown()
            check(releaseCancel.await(10L, TimeUnit.SECONDS))
        }
        val pool = Executors.newFixedThreadPool(2)
        val cancel = pool.submit<Boolean> { harness.coordinator.cancel(attempt) }
        assertTrue(cancelInsideLock.await(10L, TimeUnit.SECONDS))
        val commit = pool.submit<Boolean> {
            commitCallStarted.countDown()
            harness.coordinator.commitReadyIfCurrent(attempt, ready) {
                actionRuns += 1
            }
        }
        assertTrue(commitCallStarted.await(10L, TimeUnit.SECONDS))
        assertFalse(commit.isDone)

        releaseCancel.countDown()
        assertTrue(cancel.get(10L, TimeUnit.SECONDS))
        assertFalse(commit.get(10L, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(pool.awaitTermination(10L, TimeUnit.SECONDS))

        assertEquals(0, actionRuns)
        assertEquals(DownloadPhase.PAUSED, harness.store.read(target)?.telemetry?.phase)
    }

    @Test
    fun reentrantCancelInsideReadyActionSealsPausedAndRejectsReadyOverwrite() {
        val harness = harness("reentrant-cancel-ready")
        val attempt = harness.coordinator.ensure(target)
        nowMillis += 1L
        assertTrue(
            harness.coordinator.publish(
                attempt,
                observation(
                    generation = attempt.generation,
                    phase = DownloadPhase.VERIFYING,
                    downloadedBytes = 1_000L,
                ),
            ),
        )
        nowMillis += 1L
        var actionRuns = 0

        assertThrows(IllegalStateException::class.java) {
            harness.coordinator.commitReadyIfCurrent(
                attempt,
                observation(
                    generation = attempt.generation,
                    phase = DownloadPhase.READY,
                    downloadedBytes = 1_000L,
                ),
            ) {
                actionRuns += 1
                assertTrue(harness.coordinator.cancel(attempt))
            }
        }

        assertEquals(1, actionRuns)
        assertEquals(listOf(firstWork), harness.scheduler.cancelled)
        val paused = harness.store.read(target)?.telemetry
        assertEquals(DownloadPhase.PAUSED, paused?.phase)
        assertEquals(DownloadBlockReason.USER_PAUSED, paused?.blockReason)
    }

    @Test
    fun retryThatOwnsTheLockPreventsPredecessorReadyCommitAction() {
        val harness = harness("retry-before-commit")
        val original = harness.coordinator.ensure(target)
        nowMillis += 1L
        assertTrue(
            harness.coordinator.publish(
                original,
                observation(
                    generation = original.generation,
                    phase = DownloadPhase.VERIFYING,
                    downloadedBytes = 1_000L,
                ),
            ),
        )
        val ready = observation(
            generation = original.generation,
            phase = DownloadPhase.READY,
            downloadedBytes = 1_000L,
        )
        nowMillis += 1L
        assertTrue(harness.coordinator.cancel(original))
        val retryInsideLock = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val commitCallStarted = CountDownLatch(1)
        var actionRuns = 0
        harness.scheduler.beforeEnsure = { attempt ->
            if (attempt.generation == DownloadGeneration(2L)) {
                retryInsideLock.countDown()
                check(releaseRetry.await(10L, TimeUnit.SECONDS))
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        val retry = pool.submit<DownloadAttempt?> {
            harness.coordinator.retry(original)
        }
        assertTrue(retryInsideLock.await(10L, TimeUnit.SECONDS))
        val commit = pool.submit<Boolean> {
            commitCallStarted.countDown()
            harness.coordinator.commitReadyIfCurrent(original, ready) {
                actionRuns += 1
            }
        }
        assertTrue(commitCallStarted.await(10L, TimeUnit.SECONDS))
        assertFalse(commit.isDone)

        releaseRetry.countDown()
        val retried = retry.get(10L, TimeUnit.SECONDS)
        assertFalse(commit.get(10L, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(pool.awaitTermination(10L, TimeUnit.SECONDS))

        assertEquals(0, actionRuns)
        assertEquals(DownloadGeneration(2L), retried?.generation)
        assertTrue(retried?.let(harness.coordinator::isCurrent) == true)
    }

    @Test
    fun commitReadyActionFailureLeavesVerifyingTelemetryUnchanged() {
        val harness = harness("commit-ready-action-failure")
        val attempt = harness.coordinator.ensure(target)
        nowMillis += 1L
        assertTrue(
            harness.coordinator.publish(
                attempt,
                observation(
                    generation = attempt.generation,
                    phase = DownloadPhase.VERIFYING,
                    downloadedBytes = 1_000L,
                ),
            ),
        )
        val before = harness.store.read(target)?.telemetry
        nowMillis += 1L
        val failure = IOException("marker commit failed")

        val thrown = assertThrows(IOException::class.java) {
            harness.coordinator.commitReadyIfCurrent(
                attempt,
                observation(
                    generation = attempt.generation,
                    phase = DownloadPhase.READY,
                    downloadedBytes = 1_000L,
                ),
            ) {
                throw failure
            }
        }

        assertTrue(thrown === failure)
        assertEquals(before, harness.store.read(target)?.telemetry)
    }

    @Test
    fun commitReadyPersistenceFailureRecoversDurablyWithoutRepeatingAction() {
        val directory = temporaryFolder.newFolder("commit-ready-persistence-failure")
        val readyReconciler = MutableReadyReconciler()
        val durableStore = store(directory, readyReconciler)
        val scheduler = RecordingScheduler()
        val partials = MutablePartialSnapshotProvider().apply {
            snapshots[target] = DownloadPartialSnapshot(0L, 1_000L, DownloadNetwork.WIFI)
        }
        val initial = coordinator(
            store = durableStore,
            scheduler = scheduler,
            identities = ArrayDeque(listOf(firstWork)),
            partials = partials,
        )
        val attempt = initial.ensure(target)
        nowMillis += 1L
        assertTrue(
            initial.publish(
                attempt,
                observation(
                    generation = attempt.generation,
                    phase = DownloadPhase.VERIFYING,
                    downloadedBytes = 1_000L,
                ),
            ),
        )
        val failingStore = DownloadTelemetryStore(
            directory = directory,
            clock = { nowMillis },
            readyReconciler = readyReconciler,
            committer = AtomicSnapshotCommitter { _, _ ->
                throw IOException("ready commit failed")
            },
        )
        val coordinator = coordinator(
            store = failingStore,
            scheduler = scheduler,
            identities = ArrayDeque(listOf(secondWork)),
            partials = partials,
        )
        var actionRuns = 0
        nowMillis += 1L

        assertThrows(IOException::class.java) {
            coordinator.commitReadyIfCurrent(
                attempt,
                observation(
                    generation = attempt.generation,
                    phase = DownloadPhase.READY,
                    downloadedBytes = 1_000L,
                ),
            ) {
                actionRuns += 1
                readyReconciler.activeAndSelfTested = true
            }
        }

        assertEquals(1, actionRuns)
        val unreconciled = store(
            directory,
            DownloadReadyReconciler { _, _ -> false },
        ).read(target)
        assertEquals(DownloadPhase.VERIFYING, unreconciled?.telemetry?.phase)

        val recoveredStore = store(directory, readyReconciler)
        val recovered = recoveredStore.read(target)
        assertEquals(DownloadPhase.READY, recovered?.telemetry?.phase)
        assertTrue(recovered?.telemetry?.verificationSucceeded == true)
        val recoveredCoordinator = coordinator(
            store = recoveredStore,
            scheduler = scheduler,
            identities = ArrayDeque(listOf(secondWork)),
            partials = partials,
        )
        assertFalse(
            recoveredCoordinator.commitReadyIfCurrent(
                attempt,
                observation(
                    generation = attempt.generation,
                    phase = DownloadPhase.READY,
                    downloadedBytes = 1_000L,
                ),
            ) {
                actionRuns += 1
            },
        )
        assertEquals(1, actionRuns)
    }

    @Test
    fun cancelPersistsUserPausedBeforeCancellingExactUuidAndSealsAttempt() {
        val harness = harness("cancel")
        val attempt = harness.coordinator.ensure(target)
        nowMillis += 1L
        harness.scheduler.beforeCancel = { cancelled ->
            assertEquals(firstWork.toUuid(), cancelled.toUuid())
            assertEquals(
                DownloadPhase.PAUSED,
                harness.store.read(target)?.telemetry?.phase,
            )
            assertEquals(
                DownloadBlockReason.USER_PAUSED,
                harness.store.read(target)?.telemetry?.blockReason,
            )
        }

        assertTrue(harness.coordinator.cancel(attempt))

        assertEquals(listOf(firstWork), harness.scheduler.cancelled)
        nowMillis += 1L
        assertFalse(
            harness.coordinator.publish(
                attempt,
                observation(
                    generation = attempt.generation,
                    phase = DownloadPhase.DOWNLOADING,
                    downloadedBytes = 500L,
                ),
            ),
        )
        assertFalse(harness.coordinator.cancel(attempt))
        assertEquals(DownloadPhase.PAUSED, harness.store.read(target)?.telemetry?.phase)
    }

    @Test
    fun cancellationSchedulerFailureStillLeavesDurablePausedState() {
        val harness = harness("cancel-failure")
        val attempt = harness.coordinator.ensure(target)
        harness.scheduler.cancelFailure = IllegalStateException("cancel failed")
        nowMillis += 1L

        assertThrows(IllegalStateException::class.java) {
            harness.coordinator.cancel(attempt)
        }

        val restored = store(harness.directory).read(target)
        assertEquals(DownloadPhase.PAUSED, restored?.telemetry?.phase)
        assertEquals(DownloadBlockReason.USER_PAUSED, restored?.telemetry?.blockReason)
        assertEquals(listOf(firstWork), harness.scheduler.cancelled)
    }

    @Test
    fun retryRequiresExactPausedAttemptAndUsesOriginalTargetWithFreshPartialState() {
        val harness = harness("retry-paused")
        val other = seedRecord(
            store = harness.store,
            target = otherTarget,
            workIdentity = thirdWork,
            phase = DownloadPhase.WAITING,
        )
        val original = harness.coordinator.ensure(target)

        assertNull(harness.coordinator.retry(original))
        nowMillis += 1L
        assertTrue(harness.coordinator.cancel(original))
        harness.partials.requestedTargets.clear()
        harness.partials.snapshots[target] = DownloadPartialSnapshot(
            downloadedBytes = 777L,
            totalBytes = 1_000L,
            network = DownloadNetwork.MOBILE,
        )
        nowMillis += 1L

        val retried = harness.coordinator.retry(original)

        assertEquals(target, retried?.target)
        assertEquals(DownloadGeneration(2L), retried?.generation)
        assertEquals(secondWork, retried?.workIdentity)
        val restored = harness.store.read(target)
        assertEquals(DownloadPhase.PREPARING, restored?.telemetry?.phase)
        assertEquals(777L, restored?.telemetry?.downloadedBytes)
        assertEquals(1_000L, restored?.telemetry?.totalBytes)
        assertEquals(DownloadNetwork.MOBILE, restored?.telemetry?.network)
        assertTrue(restored?.telemetry?.samples?.isEmpty() == true)
        assertNull(restored?.telemetry?.failure)
        assertEquals(other, harness.store.read(otherTarget))
        assertEquals(listOf(target), harness.partials.requestedTargets)
        assertEquals(listOf(firstWork, firstWork), harness.scheduler.cancelled)
        assertNull(harness.coordinator.retry(original))
    }

    @Test
    fun retryAcceptsExactWifiWaitingAttemptAndStartsFreshWorkImmediately() {
        val harness = harness("retry-wifi-waiting")
        val original = harness.coordinator.ensure(target)
        nowMillis += 1L
        assertTrue(
            harness.coordinator.publish(
                original,
                DownloadObservation(
                    generation = original.generation,
                    phase = DownloadPhase.WAITING,
                    downloadedBytes = 400L,
                    totalBytes = 1_000L,
                    network = DownloadNetwork.MOBILE,
                    blockReason = DownloadBlockReason.WIFI_REQUIRED,
                    cancellationAllowed = true,
                    retryAllowed = false,
                ),
            ),
        )
        harness.partials.snapshots[target] = DownloadPartialSnapshot(
            downloadedBytes = 400L,
            totalBytes = 1_000L,
            network = DownloadNetwork.MOBILE,
        )
        nowMillis += 1L

        assertEquals(original, harness.coordinator.terminalAttempt(target))
        val retried = harness.coordinator.retry(original)

        assertEquals(DownloadGeneration(2L), retried?.generation)
        assertEquals(secondWork, retried?.workIdentity)
        assertEquals(listOf(firstWork), harness.scheduler.cancelled)
        assertEquals(listOf(original, retried), harness.scheduler.scheduled)
        val restored = requireNotNull(harness.store.read(target))
        assertEquals(DownloadPhase.PREPARING, restored.telemetry.phase)
        assertEquals(400L, restored.telemetry.downloadedBytes)
        assertEquals(DownloadNetwork.MOBILE, restored.telemetry.network)
    }

    @Test
    fun scheduleFailurePersistsRetryableFailureAndLateCallbackCannotEscapeIt() {
        val harness = harness("schedule-failure")
        harness.partials.snapshots[target] =
            DownloadPartialSnapshot(100L, 1_000L, DownloadNetwork.WIFI)
        harness.scheduler.scheduleFailure = IllegalStateException("schedule failed")

        assertThrows(IllegalStateException::class.java) {
            harness.coordinator.ensure(target)
        }

        val failed = store(harness.directory).read(target)
        assertEquals(DownloadPhase.FAILED, failed?.telemetry?.phase)
        assertEquals(
            SafeDownloadFailure.DOWNLOAD_INTERRUPTED,
            failed?.telemetry?.failure,
        )
        assertTrue(failed?.telemetry?.canRetry == true)
        assertEquals(100L, failed?.telemetry?.downloadedBytes)
        val failedAttempt = DownloadAttempt(target, DownloadGeneration(1L), firstWork)
        nowMillis += 1L
        assertFalse(
            harness.coordinator.publish(
                failedAttempt,
                observation(
                    generation = failedAttempt.generation,
                    phase = DownloadPhase.DOWNLOADING,
                    downloadedBytes = 100L,
                ),
            ),
        )

        harness.scheduler.scheduleFailure = null
        harness.partials.snapshots[target] = DownloadPartialSnapshot(100L, 1_000L, DownloadNetwork.WIFI)
        nowMillis += 1L
        val retried = harness.coordinator.retry(failedAttempt)
        assertEquals(DownloadGeneration(2L), retried?.generation)
        assertEquals(secondWork, retried?.workIdentity)
        val preparing = harness.store.read(target)?.telemetry
        assertEquals(DownloadPhase.PREPARING, preparing?.phase)
        assertEquals(100L, preparing?.downloadedBytes)
        assertEquals(1_000L, preparing?.totalBytes)
        assertNull(preparing?.failure)
        assertTrue(preparing?.samples?.isEmpty() == true)
    }

    @Test
    fun retryScheduleFailureFailsTheNewGenerationWithoutRevivingOldAttempt() {
        val harness = harness("retry-schedule-failure")
        val original = harness.coordinator.ensure(target)
        nowMillis += 1L
        assertTrue(harness.coordinator.cancel(original))
        harness.scheduler.scheduleFailure = IllegalArgumentException("enqueue failed")
        nowMillis += 1L

        assertThrows(IllegalArgumentException::class.java) {
            harness.coordinator.retry(original)
        }

        val failed = harness.store.read(target)
        assertEquals(DownloadGeneration(2L), failed?.telemetry?.generation)
        assertEquals(secondWork, failed?.workIdentity)
        assertEquals(DownloadPhase.FAILED, failed?.telemetry?.phase)
        assertFalse(
            harness.coordinator.publish(
                original,
                observation(
                    generation = original.generation,
                    phase = DownloadPhase.DOWNLOADING,
                    downloadedBytes = 900L,
                ),
            ),
        )
    }

    @Test
    fun retryRequiresAFreshWorkIdentityBeforeReservingNewGeneration() {
        val directory = temporaryFolder.newFolder("retry-work-identity")
        val store = store(directory)
        val scheduler = RecordingScheduler()
        val partials = MutablePartialSnapshotProvider().apply {
            snapshots[target] = DownloadPartialSnapshot(0L, 1_000L, DownloadNetwork.WIFI)
        }
        val coordinator = coordinator(
            store = store,
            scheduler = scheduler,
            identities = ArrayDeque(listOf(firstWork, firstWork)),
            partials = partials,
        )
        val original = coordinator.ensure(target)
        nowMillis += 1L
        assertTrue(coordinator.cancel(original))
        nowMillis += 1L

        assertThrows(IllegalStateException::class.java) {
            coordinator.retry(original)
        }

        assertEquals(DownloadGeneration(1L), store.read(target)?.telemetry?.generation)
        assertEquals(DownloadPhase.PAUSED, store.read(target)?.telemetry?.phase)
        assertEquals(1, scheduler.scheduled.size)
    }

    @Test
    fun retryRecancelsPredecessorAndCancellationFailureKeepsOldTerminalRecord() {
        val harness = harness("retry-cancel-failure")
        val original = harness.coordinator.ensure(target)
        nowMillis += 1L
        assertTrue(harness.coordinator.cancel(original))
        harness.scheduler.cancelFailure = IllegalStateException("predecessor still live")
        nowMillis += 1L

        assertThrows(IllegalStateException::class.java) {
            harness.coordinator.retry(original)
        }

        val restored = harness.store.read(target)
        assertEquals(original.generation, restored?.telemetry?.generation)
        assertEquals(original.workIdentity, restored?.workIdentity)
        assertEquals(DownloadPhase.PAUSED, restored?.telemetry?.phase)
        assertEquals(listOf(firstWork, firstWork), harness.scheduler.cancelled)
        assertEquals(1, harness.scheduler.scheduled.size)
    }

    @Test
    fun concurrentEnsureCallsUseOneDurableAttemptAndOneScheduledWorkItem() {
        val directory = temporaryFolder.newFolder("concurrent-ensure")
        val scheduler = RecordingScheduler()
        val partials = MutablePartialSnapshotProvider().apply {
            snapshots[target] = DownloadPartialSnapshot(0L, 1_000L, DownloadNetwork.WIFI)
        }
        val first = coordinator(
            store(directory),
            scheduler,
            ArrayDeque(listOf(firstWork)),
            partials,
        )
        val second = coordinator(
            store(directory),
            scheduler,
            ArrayDeque(listOf(secondWork)),
            partials,
        )
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val futures = listOf(first, second).map { coordinator ->
            pool.submit<DownloadAttempt> {
                start.await()
                coordinator.ensure(target)
            }
        }

        start.countDown()
        val attempts = futures.map { it.get(10L, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10L, TimeUnit.SECONDS))

        val durableAttempt = attempts.distinct().single()
        assertEquals(DownloadGeneration(1L), durableAttempt.generation)
        assertEquals(listOf(durableAttempt), scheduler.scheduled)
        assertEquals(2, scheduler.ensureCalls)
    }

    @Test
    fun concurrentPublishAndCancelAlwaysLeaveTheAttemptPaused() {
        val harness = harness("publish-cancel")
        val attempt = harness.coordinator.ensure(target)
        nowMillis += 1_000L
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val publish = pool.submit<Boolean> {
            start.await()
            harness.coordinator.publish(
                attempt,
                observation(attempt.generation, DownloadPhase.DOWNLOADING, 400L),
            )
        }
        val cancel = pool.submit<Boolean> {
            start.await()
            harness.coordinator.cancel(attempt)
        }

        start.countDown()
        publish.get(10L, TimeUnit.SECONDS)
        assertTrue(cancel.get(10L, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(pool.awaitTermination(10L, TimeUnit.SECONDS))

        assertEquals(DownloadPhase.PAUSED, harness.store.read(target)?.telemetry?.phase)
        assertEquals(DownloadBlockReason.USER_PAUSED, harness.store.read(target)?.telemetry?.blockReason)
    }

    @Test
    fun concurrentRetryCallsReserveAndScheduleOnlyOneNewGeneration() {
        val harness = harness("concurrent-retry")
        val original = harness.coordinator.ensure(target)
        nowMillis += 1L
        assertTrue(harness.coordinator.cancel(original))
        nowMillis += 1L
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val futures = List(2) {
            pool.submit<DownloadAttempt?> {
                start.await()
                harness.coordinator.retry(original)
            }
        }

        start.countDown()
        val results = futures.map { it.get(10L, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10L, TimeUnit.SECONDS))

        val retried = results.filterNotNull().single()
        assertEquals(DownloadGeneration(2L), retried.generation)
        assertEquals(retried, DownloadAttempt.from(harness.store.read(target)!!))
        assertEquals(listOf(original, retried), harness.scheduler.scheduled)
    }

    @Test
    fun cancelPersistenceFailureNeverInvokesTheSchedulerCancellation() {
        val harness = harness("cancel-persistence-failure")
        val attempt = harness.coordinator.ensure(target)
        val failingStore = DownloadTelemetryStore(
            directory = harness.directory,
            clock = { nowMillis },
            readyReconciler = DownloadReadyReconciler { _, _ -> true },
            committer = AtomicSnapshotCommitter { _, _ ->
                throw IOException("commit failed")
            },
        )
        val coordinator = coordinator(
            store = failingStore,
            scheduler = harness.scheduler,
            identities = ArrayDeque(listOf(secondWork)),
            partials = harness.partials,
        )
        nowMillis += 1L

        assertThrows(IOException::class.java) {
            coordinator.cancel(attempt)
        }

        assertTrue(harness.scheduler.cancelled.isEmpty())
        assertEquals(DownloadPhase.PREPARING, harness.store.read(target)?.telemetry?.phase)
    }

    @Test
    fun schedulerCancellationExceptionIsRethrownAfterPersistingSafeFailure() {
        val harness = harness("scheduler-cancellation")
        harness.scheduler.scheduleFailure = CancellationException("scheduler stopped")

        assertThrows(CancellationException::class.java) {
            harness.coordinator.ensure(target)
        }

        val restored = harness.store.read(target)?.telemetry
        assertEquals(DownloadPhase.FAILED, restored?.phase)
        assertEquals(SafeDownloadFailure.DOWNLOAD_INTERRUPTED, restored?.failure)
        assertTrue(restored?.canRetry == true)
    }

    @Test
    fun telemetryRestoresAcrossFreshCoordinatorWithoutScheduling() {
        val harness = harness("restore")
        val attempt = harness.coordinator.ensure(target)
        nowMillis += 2_000L
        assertTrue(
            harness.coordinator.publish(
                attempt,
                observation(
                    generation = attempt.generation,
                    phase = DownloadPhase.DOWNLOADING,
                    downloadedBytes = 350L,
                ),
            ),
        )
        val expected = harness.store.read(target)?.telemetry
        val freshScheduler = RecordingScheduler()
        val fresh = coordinator(
            store = store(harness.directory),
            scheduler = freshScheduler,
            identities = ArrayDeque(listOf(thirdWork)),
            partials = MutablePartialSnapshotProvider(),
        )

        assertEquals(expected, fresh.restoreTelemetry(target))
        assertEquals(attempt, fresh.ensure(target))
        assertEquals(listOf(attempt), freshScheduler.scheduled)
        assertEquals(1, freshScheduler.ensureCalls)
    }

    @Test
    fun uiSnapshotReadsOneDurableRecordAndBindsItsExactActionableAttemptAtomically() {
        val actionablePhases = setOf(
            DownloadPhase.PREPARING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.VERIFYING,
            DownloadPhase.WAITING,
            DownloadPhase.PAUSED,
            DownloadPhase.FAILED,
        )
        DownloadPhase.entries.forEachIndexed { index, phase ->
            val directory = temporaryFolder.newFolder("ui-snapshot-$phase")
            val readyReconciler = MutableReadyReconciler()
            val seedStore = store(directory, readyReconciler)
            val identity = work(
                "00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}",
            )
            val expected = if (phase == DownloadPhase.READY) {
                val verifying = seedRecord(
                    store = seedStore,
                    target = target,
                    workIdentity = identity,
                    phase = DownloadPhase.VERIFYING,
                )
                nowMillis += 1L
                val seedingCoordinator = coordinator(
                    store = seedStore,
                    scheduler = RecordingScheduler(),
                    identities = ArrayDeque(listOf(secondWork)),
                    partials = MutablePartialSnapshotProvider(),
                )
                check(
                    seedingCoordinator.commitReadyIfCurrent(
                        DownloadAttempt.from(verifying),
                        observation(
                            generation = verifying.telemetry.generation,
                            phase = DownloadPhase.READY,
                            downloadedBytes = 1_000L,
                        ),
                    ) {},
                ) { "Could not seed a valid VERIFYING to READY transition." }
                readyReconciler.activeAndSelfTested = true
                requireNotNull(seedStore.read(target))
            } else {
                seedRecord(
                    store = seedStore,
                    target = target,
                    workIdentity = identity,
                    phase = phase,
                )
            }
            val exactRecordFile = DownloadTelemetryStore.recordFile(directory, target).canonicalFile
            var exactRecordReads = 0
            val countingStore = DownloadTelemetryStore(
                directory = directory,
                clock = { nowMillis },
                readyReconciler = readyReconciler,
                snapshotReader = SnapshotReader { file ->
                    if (file.canonicalFile == exactRecordFile) exactRecordReads += 1
                    Files.readAllBytes(file.toPath())
                },
            )
            val coordinator = coordinator(
                store = countingStore,
                scheduler = RecordingScheduler(),
                identities = ArrayDeque(listOf(secondWork)),
                partials = MutablePartialSnapshotProvider(),
            )

            val snapshot = requireNotNull(coordinator.uiSnapshot(target))

            assertEquals("$phase must read one durable record", 1, exactRecordReads)
            assertEquals(target, snapshot.target)
            assertEquals(expected.telemetry, snapshot.telemetry)
            assertEquals(
                DownloadAttempt.from(expected).takeIf { phase in actionablePhases },
                snapshot.actionableAttempt,
            )
        }
    }

    @Test
    fun failCurrentSealsOnlyTheExactTargetGenerationAndWorkIdentity() {
        val harness = harness("fail-current-exact-identity")
        val current = harness.coordinator.ensure(target)
        nowMillis += 1_000L
        assertTrue(
            harness.coordinator.publish(
                current,
                observation(
                    generation = current.generation,
                    phase = DownloadPhase.DOWNLOADING,
                    downloadedBytes = 375L,
                ),
            ),
        )

        assertFalse(
            harness.coordinator.failCurrent(
                current.copy(target = otherTarget),
                DownloadFailureCode.STORAGE_UNAVAILABLE,
            ),
        )
        assertFalse(
            harness.coordinator.failCurrent(
                current.copy(generation = DownloadGeneration(current.generation.value + 1L)),
                DownloadFailureCode.STORAGE_UNAVAILABLE,
            ),
        )
        assertFalse(
            harness.coordinator.failCurrent(
                current.copy(workIdentity = secondWork),
                DownloadFailureCode.STORAGE_UNAVAILABLE,
            ),
        )
        assertEquals(DownloadPhase.DOWNLOADING, harness.store.read(target)?.telemetry?.phase)

        assertTrue(
            harness.coordinator.failCurrent(
                current,
                DownloadFailureCode.STORAGE_UNAVAILABLE,
            ),
        )
        val failed = requireNotNull(harness.store.read(target))
        assertEquals(current.target, failed.target)
        assertEquals(current.generation, failed.telemetry.generation)
        assertEquals(current.workIdentity, failed.workIdentity)
        assertEquals(DownloadPhase.FAILED, failed.telemetry.phase)
        assertEquals(375L, failed.telemetry.downloadedBytes)
        assertEquals(SafeDownloadFailure.STORAGE_UNAVAILABLE, failed.telemetry.failure)
        assertTrue(failed.telemetry.canRetry)
        assertFalse(failed.telemetry.canCancel)

        assertTrue(
            harness.coordinator.failCurrent(
                current,
                DownloadFailureCode.UNKNOWN,
            ),
        )
        assertEquals(failed, harness.store.read(target))
    }

    private fun harness(name: String): Harness {
        val directory = temporaryFolder.newFolder(name)
        val readyReconciler = MutableReadyReconciler()
        val store = store(directory, readyReconciler)
        val scheduler = RecordingScheduler()
        val partials = MutablePartialSnapshotProvider().apply {
            snapshots[target] = DownloadPartialSnapshot(0L, 1_000L, DownloadNetwork.WIFI)
            snapshots[otherTarget] = DownloadPartialSnapshot(0L, 2_000L, DownloadNetwork.WIFI)
        }
        return Harness(
            directory = directory,
            store = store,
            scheduler = scheduler,
            partials = partials,
            readyReconciler = readyReconciler,
            coordinator = coordinator(
                store,
                scheduler,
                ArrayDeque(listOf(firstWork, secondWork, thirdWork)),
                partials,
            ),
        )
    }

    private fun coordinator(
        store: DownloadTelemetryStore,
        scheduler: RecordingScheduler,
        identities: ArrayDeque<DownloadWorkIdentity>,
        partials: MutablePartialSnapshotProvider,
    ): DownloadLifecycleCoordinator = DownloadLifecycleCoordinator(
        store = store,
        scheduler = scheduler,
        workIdentitySource = DownloadWorkIdentitySource { identities.removeFirst() },
        partialSnapshotProvider = partials,
        telemetryCalculator = DownloadTelemetryCalculator(clock = { nowMillis }),
    )

    private fun store(
        directory: File,
        readyReconciler: DownloadReadyReconciler = DownloadReadyReconciler { _, _ -> false },
    ): DownloadTelemetryStore = DownloadTelemetryStore(
        directory = directory,
        clock = { nowMillis },
        readyReconciler = readyReconciler,
    )

    private fun seedRecord(
        store: DownloadTelemetryStore,
        target: DownloadTargetKey,
        workIdentity: DownloadWorkIdentity,
        phase: DownloadPhase,
    ): DurableDownloadRecord {
        val downloadedBytes = when (phase) {
            DownloadPhase.PREPARING -> 0L
            DownloadPhase.DOWNLOADING -> 400L
            DownloadPhase.VERIFYING, DownloadPhase.READY -> 1_000L
            DownloadPhase.WAITING, DownloadPhase.PAUSED, DownloadPhase.FAILED -> 600L
        }
        val reserved = store.reserveNextGeneration(
            target = target,
            workIdentity = workIdentity,
            network = DownloadNetwork.WIFI,
            downloadedBytes = downloadedBytes,
            totalBytes = 1_000L,
        )
        if (phase == DownloadPhase.PREPARING) return reserved
        val observation = observation(
            generation = reserved.telemetry.generation,
            phase = phase,
            downloadedBytes = downloadedBytes,
            totalBytes = 1_000L,
        )
        val next = DurableDownloadRecord(
            target = target,
            workIdentity = workIdentity,
            telemetry = DownloadTelemetryRestoreFactory().restore(
                observation = observation,
                updatedAtMillis = nowMillis,
                samples = if (phase == DownloadPhase.DOWNLOADING) {
                    listOf(
                        DownloadSample(
                            reserved.telemetry.generation,
                            nowMillis,
                            downloadedBytes,
                        ),
                    )
                } else {
                    emptyList()
                },
            ),
        )
        check(store.write(next)) { "Could not seed a focused coordinator test record." }
        return next
    }

    private fun observation(
        generation: DownloadGeneration,
        phase: DownloadPhase,
        downloadedBytes: Long,
        totalBytes: Long? = 1_000L,
    ): DownloadObservation = DownloadObservation(
        generation = generation,
        phase = phase,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        network = DownloadNetwork.WIFI,
        blockReason = when (phase) {
            DownloadPhase.WAITING -> DownloadBlockReason.NETWORK_UNAVAILABLE
            DownloadPhase.PAUSED -> DownloadBlockReason.USER_PAUSED
            else -> null
        },
        failureCode = if (phase == DownloadPhase.FAILED) {
            DownloadFailureCode.DOWNLOAD_INTERRUPTED
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

    private fun work(value: String): DownloadWorkIdentity =
        DownloadWorkIdentity.from(UUID.fromString(value))

    private data class Harness(
        val directory: File,
        val store: DownloadTelemetryStore,
        val scheduler: RecordingScheduler,
        val partials: MutablePartialSnapshotProvider,
        val readyReconciler: MutableReadyReconciler,
        val coordinator: DownloadLifecycleCoordinator,
    )

    private class MutableReadyReconciler : DownloadReadyReconciler {
        @Volatile
        var activeAndSelfTested: Boolean = false

        override fun isActiveAndSelfTested(
            target: DownloadTargetKey,
            generation: DownloadGeneration,
        ): Boolean = activeAndSelfTested
    }

    private class MutablePartialSnapshotProvider : DownloadPartialSnapshotProvider {
        val snapshots = mutableMapOf<DownloadTargetKey, DownloadPartialSnapshot>()
        val requestedTargets = mutableListOf<DownloadTargetKey>()

        override fun snapshot(target: DownloadTargetKey): DownloadPartialSnapshot {
            requestedTargets += target
            return snapshots.getValue(target)
        }
    }

    private class RecordingScheduler : DownloadAttemptScheduler {
        val scheduled = mutableListOf<DownloadAttempt>()
        val cancelled = mutableListOf<DownloadWorkIdentity>()
        var ensureCalls = 0
        var scheduleFailure: RuntimeException? = null
        var cancelFailure: RuntimeException? = null
        var beforeEnsure: ((DownloadAttempt) -> Unit)? = null
        var beforeCancel: ((DownloadWorkIdentity) -> Unit)? = null

        @Synchronized
        override fun ensureScheduled(attempt: DownloadAttempt) {
            ensureCalls += 1
            beforeEnsure?.invoke(attempt)
            if (attempt !in scheduled) scheduled += attempt
            scheduleFailure?.let { throw it }
        }

        @Synchronized
        override fun cancel(workIdentity: DownloadWorkIdentity) {
            cancelled += workIdentity
            beforeCancel?.invoke(workIdentity)
            cancelFailure?.let { throw it }
        }
    }
}
