package com.echolink.mobile

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkManagerDownloadAttemptSchedulerTest {
    @Test
    fun everyGemmaAttemptUsesConnectedAsACoarseConstraintSoMeteredWifiCanRun() {
        val gateway = RecordingWorkManagerGateway()
        val attempt = attempt(
            target = DownloadTargetKey(
                AiModelFamily.GEMMA,
                AiModelTier.FAST,
                BackendKind.LITERT_GPU,
            ),
            id = UUID.fromString("2b897e0a-91ef-4c1e-a978-cae83ec8c570"),
        )
        val scheduler = scheduler(gateway)

        scheduler.ensureScheduled(attempt)

        val call = gateway.enqueueCalls.single()
        assertEquals(
            "manuscript.download.attempt.v1.2b897e0a-91ef-4c1e-a978-cae83ec8c570",
            call.uniqueName,
        )
        assertEquals(ExistingWorkPolicy.KEEP, call.existingWorkPolicy)
        assertRequest(
            request = call.request,
            attempt = attempt,
            workerClassName = AiModelDownloadWorker::class.java.name,
            networkType = NetworkType.CONNECTED,
        )
    }

    @Test
    fun legacyAttemptBuildsExactTypedConnectedRequest() {
        val gateway = RecordingWorkManagerGateway()
        val attempt = attempt(
            target = DownloadTargetKey(
                AiModelFamily.LEGACY_QWEN,
                AiModelTier.LEGACY_BALANCED,
                BackendKind.LEGACY_LLAMA_CPU,
            ),
            id = UUID.fromString("b26302ed-b8d9-4b06-ac49-c05bb1c69cb0"),
        )
        val scheduler = scheduler(gateway)

        scheduler.ensureScheduled(attempt)

        val call = gateway.enqueueCalls.single()
        assertEquals(
            "manuscript.download.attempt.v1.b26302ed-b8d9-4b06-ac49-c05bb1c69cb0",
            call.uniqueName,
        )
        assertEquals(ExistingWorkPolicy.KEEP, call.existingWorkPolicy)
        assertRequest(
            request = call.request,
            attempt = attempt,
            workerClassName = Qwen3ModelDownloadWorker::class.java.name,
            networkType = NetworkType.CONNECTED,
        )
    }

    @Test
    fun completeLocalGemmaAndLegacyWorkCanVerifyOfflineWithoutStorageConstraint() {
        listOf(
            DownloadTargetKey(
                AiModelFamily.GEMMA,
                AiModelTier.THOROUGH,
                BackendKind.LITERT_CPU,
            ) to AiModelDownloadWorker::class.java.name,
            DownloadTargetKey(
                AiModelFamily.LEGACY_QWEN,
                AiModelTier.LEGACY_ADVANCED,
                BackendKind.LEGACY_LLAMA_CPU,
            ) to Qwen3ModelDownloadWorker::class.java.name,
        ).forEachIndexed { index, (target, workerClassName) ->
            val gateway = RecordingWorkManagerGateway()
            val requestedTargets = mutableListOf<DownloadTargetKey>()
            val scheduler = scheduler(gateway) { requested ->
                requestedTargets += requested
                true
            }
            val attempt = attempt(target, UUID(900L + index, 1_000L + index))

            scheduler.ensureScheduled(attempt)

            assertEquals(listOf(target), requestedTargets)
            assertRequest(
                request = gateway.enqueueCalls.single().request,
                attempt = attempt,
                workerClassName = workerClassName,
                networkType = NetworkType.NOT_REQUIRED,
                requiresStorageNotLow = false,
            )
        }
    }

    @Test
    fun localAvailabilityForOneTargetCannotRemoveConstraintsFromAnotherTarget() {
        val localTarget = DownloadTargetKey(
            AiModelFamily.GEMMA,
            AiModelTier.FAST,
            BackendKind.LITERT_CPU,
        )
        val remoteTarget = DownloadTargetKey(
            AiModelFamily.GEMMA,
            AiModelTier.FAST,
            BackendKind.LITERT_GPU,
        )
        val gateway = RecordingWorkManagerGateway()
        val scheduler = scheduler(gateway) { it == localTarget }
        val localAttempt = attempt(localTarget, UUID(1_100L, 1_101L))
        val remoteAttempt = attempt(remoteTarget, UUID(1_200L, 1_201L))

        scheduler.ensureScheduled(localAttempt)
        scheduler.ensureScheduled(remoteAttempt)

        val localRequest = gateway.enqueueCalls[0].request
        val remoteRequest = gateway.enqueueCalls[1].request
        assertEquals(NetworkType.NOT_REQUIRED, localRequest.workSpec.constraints.requiredNetworkType)
        assertFalse(localRequest.workSpec.constraints.requiresStorageNotLow())
        assertEquals(NetworkType.CONNECTED, remoteRequest.workSpec.constraints.requiredNetworkType)
        assertTrue(remoteRequest.workSpec.constraints.requiresStorageNotLow())
    }

    @Test
    fun cancelTargetsOnlyTheExactWorkUuid() {
        val gateway = RecordingWorkManagerGateway()
        val scheduler = scheduler(gateway)
        val identity = DownloadWorkIdentity(
            mostSignificantBits = 0x1122334455667788L,
            leastSignificantBits = 0x0102030405060708L,
        )

        scheduler.cancel(identity)

        assertEquals(listOf(identity.toUuid()), gateway.cancelledWorkIds)
        assertTrue(gateway.cancelledUniqueWorkNames.isEmpty())
        assertTrue(gateway.enqueueCalls.isEmpty())
    }

    @Test
    fun repeatedEnsureKeepsTheExactUuidNameAndSixKeyPayload() {
        val gateway = RecordingWorkManagerGateway()
        val scheduler = scheduler(gateway)
        val attempt = attempt(
            target = DownloadTargetKey(
                AiModelFamily.GEMMA,
                AiModelTier.THOROUGH,
                BackendKind.LITERT_CPU,
            ),
            id = UUID.fromString("6200a25e-0c75-46ee-8d5b-683402eb5f0d"),
        )

        scheduler.ensureScheduled(attempt)
        scheduler.ensureScheduled(attempt)

        assertEquals(2, gateway.enqueueCalls.size)
        val first = gateway.enqueueCalls[0]
        val second = gateway.enqueueCalls[1]
        assertEquals(first.uniqueName, second.uniqueName)
        assertEquals(ExistingWorkPolicy.KEEP, first.existingWorkPolicy)
        assertEquals(ExistingWorkPolicy.KEEP, second.existingWorkPolicy)
        assertEquals(attempt.workIdentity.toUuid(), first.request.id)
        assertEquals(attempt.workIdentity.toUuid(), second.request.id)
        assertEquals(first.request.workSpec.input, second.request.workSpec.input)
        assertEquals(6, second.request.workSpec.input.keyValueMap.size)
    }

    @Test
    fun explicitLegacyMigrationCancelsEveryKnownNameOnlyOnce() {
        val gateway = RecordingWorkManagerGateway()
        val scheduler = scheduler(gateway)

        scheduler.migrateLegacyUniqueWorkOnce()
        scheduler.migrateLegacyUniqueWorkOnce()

        assertEquals(
            listOf(
                "manuscript-ai-model-download-v3",
                "manuscript-ai-model-download",
                "manuscript-qwen3-model-download",
            ),
            gateway.cancelledUniqueWorkNames,
        )
        assertTrue(gateway.cancelledWorkIds.isEmpty())
        assertTrue(gateway.enqueueCalls.isEmpty())
    }

    @Test
    fun concurrentLegacyMigrationStillCancelsEachKnownNameExactlyOnce() {
        val gateway = RecordingWorkManagerGateway()
        val scheduler = scheduler(gateway)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val futures = List(24) {
            pool.submit {
                start.await()
                scheduler.migrateLegacyUniqueWorkOnce()
            }
        }

        start.countDown()
        futures.forEach { it.get(10L, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10L, TimeUnit.SECONDS))

        assertEquals(
            mapOf(
                "manuscript-ai-model-download-v3" to 1,
                "manuscript-ai-model-download" to 1,
                "manuscript-qwen3-model-download" to 1,
            ),
            gateway.cancelledUniqueWorkNames.groupingBy { it }.eachCount(),
        )
    }

    private fun scheduler(
        gateway: RecordingWorkManagerGateway,
        localWorkAvailable: (DownloadTargetKey) -> Boolean = { false },
    ): WorkManagerDownloadAttemptScheduler = WorkManagerDownloadAttemptScheduler(
        gateway = gateway,
        localWorkAvailable = localWorkAvailable,
    )

    private fun attempt(
        target: DownloadTargetKey,
        id: UUID,
    ): DownloadAttempt = DownloadAttempt(
        target = target,
        generation = DownloadGeneration(17L),
        workIdentity = DownloadWorkIdentity.from(id),
    )

    private fun assertRequest(
        request: OneTimeWorkRequest,
        attempt: DownloadAttempt,
        workerClassName: String,
        networkType: NetworkType,
        requiresStorageNotLow: Boolean = true,
    ) {
        assertEquals(attempt.workIdentity.toUuid(), request.id)
        assertEquals(workerClassName, request.workSpec.workerClassName)
        assertEquals(
            setOf(
                "manuscript.download.family.v1",
                "manuscript.download.tier.v1",
                "manuscript.download.backend.v1",
                "manuscript.download.generation.v1",
                "manuscript.download.work_uuid_most.v1",
                "manuscript.download.work_uuid_least.v1",
            ),
            request.workSpec.input.keyValueMap.keys,
        )
        assertEquals(attempt, DownloadWorkContract.decode(request.workSpec.input, request.id))
        assertEquals(networkType, request.workSpec.constraints.requiredNetworkType)
        assertEquals(requiresStorageNotLow, request.workSpec.constraints.requiresStorageNotLow())
        assertEquals(BackoffPolicy.LINEAR, request.workSpec.backoffPolicy)
        assertEquals(10_000L, request.workSpec.backoffDelayDuration)
    }

    private class RecordingWorkManagerGateway : DownloadWorkManagerGateway {
        val enqueueCalls = mutableListOf<EnqueueCall>()
        val cancelledWorkIds = mutableListOf<UUID>()
        val cancelledUniqueWorkNames = mutableListOf<String>()

        override fun enqueueUniqueWork(
            uniqueName: String,
            existingWorkPolicy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            enqueueCalls += EnqueueCall(uniqueName, existingWorkPolicy, request)
        }

        override fun cancelWorkById(id: UUID) {
            cancelledWorkIds += id
        }

        override fun cancelUniqueWork(uniqueName: String) {
            cancelledUniqueWorkNames += uniqueName
        }
    }

    private data class EnqueueCall(
        val uniqueName: String,
        val existingWorkPolicy: ExistingWorkPolicy,
        val request: OneTimeWorkRequest,
    )
}
