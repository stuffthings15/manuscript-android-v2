package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** RED contract for the pure model-manager adapter used by both download workers. */
class ExactManagerDownloadAttemptPortContractTest {
    @Test
    fun portRequiresTheExactTargetAndItsCanonicalArtifact() {
        val services = RecordingServices(attempt)
        val exact = RecordingModelOperations(gemmaTarget)

        ExactManagerDownloadAttemptPort(
            exactAttempt = attempt,
            modelOperations = exact,
            services = services,
            stopped = { false },
        )

        val wrongTarget = RecordingModelOperations(
            DownloadTargetKey(
                AiModelFamily.GEMMA,
                AiModelTier.FAST,
                BackendKind.LITERT_GPU,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ExactManagerDownloadAttemptPort(attempt, wrongTarget, services) { false }
        }

        val wrongArtifact = RecordingModelOperations(
            target = gemmaTarget,
            artifact = GemmaModelCatalog.fast.copy(revision = "different-pinned-revision"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ExactManagerDownloadAttemptPort(attempt, wrongArtifact, services) { false }
        }
    }

    @Test
    fun canonicalCandidateAndActiveButUnverifiedModelsBothRecoverAsCandidates() {
        listOf("candidate", "active-unverified").forEach { source ->
            val operations = RecordingModelOperations(gemmaTarget)
            val expected = File("$source-model.bin")
            if (source == "candidate") {
                operations.candidate = expected
            } else {
                operations.activeUnverified = expected
            }
            val port = port(operations)

            assertEquals(ExactDownloadModelAvailability.CANDIDATE, port.modelAvailability())
            assertTrue(port.qualifyCandidate() === FakeQualification)
            assertEquals(listOf(expected), operations.qualifiedModels)
        }
    }

    @Test
    fun candidateInstalledAndNewlyDownloadedModelsAlwaysReportCanonicalBytes() {
        listOf("candidate", "installed", "active-unverified").forEach { source ->
            val operations = RecordingModelOperations(gemmaTarget).apply {
                partial = 17L
                when (source) {
                    "candidate" -> candidate = File("candidate.bin")
                    "installed" -> exactInstalled = File("installed.bin")
                    else -> activeUnverified = File("active.bin")
                }
            }
            val port = port(operations)

            port.modelAvailability()
            assertEquals(operations.artifact.sizeBytes, port.partialBytes())
        }

        val downloaded = RecordingModelOperations(gemmaTarget).apply {
            partial = 23L
            downloadResult = File("downloaded-candidate.bin")
        }
        val downloadedPort = port(downloaded)
        downloadedPort.download(stopped = { false }) { _, _ -> }
        assertEquals(downloaded.artifact.sizeBytes, downloadedPort.partialBytes())
    }

    @Test
    fun qualificationForwardsTheLiveStoppedSignalToCandidateHashAndSelfTest() {
        var stopped = false
        val operations = RecordingModelOperations(gemmaTarget).apply {
            candidate = File("candidate.bin")
        }
        val port = ExactManagerDownloadAttemptPort(
            exactAttempt = attempt,
            modelOperations = operations,
            services = RecordingServices(attempt),
            stopped = { stopped },
        )
        assertEquals(ExactDownloadModelAvailability.CANDIDATE, port.modelAvailability())
        stopped = true

        assertTrue(port.qualifyCandidate() === FakeQualification)
        assertEquals(listOf(true), operations.candidateHashStoppedValues)
        assertEquals(listOf(true), operations.selfTestStoppedValues)
    }

    @Test
    fun qwenCanonicalCandidateGgufQualifiesAndActivatesWithoutSuffixBranching() {
        val qwenAttempt = attempt.copy(target = legacyTarget)
        val services = RecordingServices(qwenAttempt)
        val candidate = File("Qwen3-0.6B-Q8_0.candidate.gguf")
        val active = File("Qwen3-0.6B-Q8_0.gguf")
        val operations = RecordingModelOperations(legacyTarget).apply {
            this.candidate = candidate
            activatedModel = active
        }
        val port = ExactManagerDownloadAttemptPort(
            exactAttempt = qwenAttempt,
            modelOperations = operations,
            services = services,
            stopped = { false },
        )

        val result = ExactDownloadAttemptExecutor(port).execute(qwenAttempt)

        assertEquals(DownloadAttemptExecutionResult.SUCCESS, result)
        assertEquals(listOf(candidate), operations.qualifiedModels)
        assertEquals(1, operations.activationCalls)
        assertEquals(listOf(active), operations.readyModels)
    }

    @Test
    fun activationWrapperPublishesTheActivatedReadyModelOnlyOnce() {
        val operations = RecordingModelOperations(gemmaTarget).apply {
            candidate = File("gemma.candidate.litertlm")
            activatedModel = File("gemma.litertlm")
        }
        val port = port(operations)
        assertEquals(ExactDownloadModelAvailability.CANDIDATE, port.modelAvailability())
        val qualification = checkNotNull(port.qualifyCandidate())

        port.activateCandidate(qualification)
        port.onReady()
        port.onReady()

        assertEquals(1, operations.activationCalls)
        assertEquals(listOf(operations.activatedModel), operations.readyModels)
    }

    @Test
    fun everyServiceOperationDelegatesTheExactAttemptTargetObservationAndAction() {
        val services = RecordingServices(attempt)
        val operations = RecordingModelOperations(gemmaTarget)
        val port = ExactManagerDownloadAttemptPort(attempt, operations, services) { false }
        val observation = observation(DownloadPhase.VERIFYING, 41L)
        var actions = 0

        assertTrue(port.isCurrent(attempt))
        assertEquals(services.telemetry, port.restoreTelemetry(gemmaTarget))
        assertEquals(services.networkDecision, port.networkDecision(gemmaTarget))
        assertEquals(
            DownloadPublishResult.Persisted,
            port.publish(attempt, observation, finalByteCount = false),
        )
        assertTrue(
            port.commitReadyIfCurrent(
                attempt,
                observation(DownloadPhase.READY, GemmaModelCatalog.fast.sizeBytes),
            ) {
                actions += 1
            },
        )

        assertEquals(listOf(attempt), services.currentCalls)
        assertEquals(listOf(gemmaTarget), services.restoreCalls)
        assertEquals(listOf(gemmaTarget), services.networkCalls)
        assertEquals(listOf(Published(attempt, observation, false)), services.publishCalls)
        assertEquals(1, services.commitCalls.size)
        assertEquals(attempt, services.commitCalls.single().attempt)
        assertEquals(1, actions)
    }

    @Test
    fun serviceMethodsFailClosedForAnyAttemptOrTargetOtherThanTheBoundIdentity() {
        val services = RecordingServices(attempt)
        val port = port(RecordingModelOperations(gemmaTarget), services)
        val wrongAttempt = attempt.copy(generation = DownloadGeneration(2L))
        val wrongTarget = legacyTarget

        assertFalse(port.isCurrent(wrongAttempt))
        assertNull(port.restoreTelemetry(wrongTarget))
        assertThrows(IllegalArgumentException::class.java) {
            port.networkDecision(wrongTarget)
        }
        assertEquals(
            DownloadPublishResult.Stale,
            port.publish(wrongAttempt, observation(DownloadPhase.DOWNLOADING, 1L), false),
        )
        assertFalse(
            port.commitReadyIfCurrent(
                wrongAttempt,
                observation(DownloadPhase.READY, GemmaModelCatalog.fast.sizeBytes),
            ) {},
        )
        assertTrue(services.events.isEmpty())
    }

    @Test
    fun callbacksOnlyUpdateTheManagerWhoseExactTargetStillMatches() {
        val operations = RecordingModelOperations(gemmaTarget).apply {
            exactInstalled = File("gemma.litertlm")
            managerTargetMatches = false
        }
        val port = port(operations)
        port.modelAvailability()

        port.onWaiting()
        port.onFailed(DownloadFailureCode.DOWNLOAD_INTERRUPTED)
        port.onReady()
        assertTrue(operations.callbackEvents.isEmpty())

        operations.managerTargetMatches = true
        port.onWaiting()
        port.onFailed(DownloadFailureCode.DOWNLOAD_INTERRUPTED)
        port.onReady()
        port.onReady()
        assertEquals(
            listOf(
                "waiting",
                "failed:${DownloadFailureCode.DOWNLOAD_INTERRUPTED}",
                "ready:gemma.litertlm",
            ),
            operations.callbackEvents,
        )
    }

    @Test
    fun concreteGemmaOperationsUseExactInstalledLookupAndNeverRuntimeFallback() {
        assertTrue(adapterFile.isFile)
        val gemma = classBody(adapterFile.readText(), "class GemmaDownloadModelOperations")

        assertTrue(gemma.contains("manager.exactInstalledModel(selection)"))
        assertFalse(gemma.contains("manager.installedModel(selection)"))
        assertFalse(gemma.contains("installedLegacyFallbackModel"))
        assertTrue(gemma.contains("manager.candidateModel(selection)"))
        assertTrue(gemma.contains("manager.unverifiedActiveModel(selection)"))
        assertTrue(gemma.contains("manager.qualifyCandidate("))
        assertTrue(gemma.contains("stopped"))
    }

    @Test
    fun concreteQwenOperationsUseCanonicalCandidateAndActiveRecoveryWithoutSuffixLogic() {
        assertTrue(adapterFile.isFile)
        val qwen = classBody(adapterFile.readText(), "class QwenDownloadModelOperations")

        assertTrue(qwen.contains("manager.installedModel(tier)"))
        assertTrue(qwen.contains("manager.candidateModel(tier)"))
        assertTrue(qwen.contains("manager.downloadedModel(tier)"))
        assertTrue(qwen.contains("manager.qualifyCandidate("))
        assertTrue(qwen.contains("manager.activateQualifiedCandidate("))
        assertTrue(qwen.contains("stopped"))
        assertFalse(qwen.contains("endsWith("))
        assertFalse(qwen.contains(".candidate\""))
    }

    @Test
    fun workersAreThinTypedExecutorHostsWithNoLegacyRetryOrInputPath() {
        assertWorkerContract(
            source = gemmaWorkerSource,
            decoder = "DownloadWorkerAttemptDecoder.decodeGemma(inputData, id)",
            manager = "AiModelManager.get(applicationContext)",
            operations = "GemmaDownloadModelOperations(",
        )
        assertWorkerContract(
            source = qwenWorkerSource,
            decoder = "DownloadWorkerAttemptDecoder.decodeLegacy(inputData, id)",
            manager = "Qwen3ModelManager.get(applicationContext)",
            operations = "QwenDownloadModelOperations(",
        )
    }

    private fun assertWorkerContract(
        source: String,
        decoder: String,
        manager: String,
        operations: String,
    ) {
        val constructor = source.substringBefore("override suspend fun doWork()")
        val body = source.substringAfter("override suspend fun doWork()")
        val decodeIndex = body.indexOf(decoder)
        val servicesIndex = body.indexOf("AndroidDownloadServices.get(applicationContext)")
        val managerIndex = body.indexOf(manager)
        val operationsIndex = body.indexOf(operations)
        val portIndex = body.indexOf("ExactManagerDownloadAttemptPort(")
        val executorCall = "ExactDownloadAttemptExecutor(port).execute(attempt) { isStopped }"
        val executorIndex = body.indexOf(executorCall)

        assertFalse(constructor.contains("Manager.get("))
        assertTrue(decodeIndex >= 0)
        assertTrue(servicesIndex > decodeIndex)
        assertTrue(managerIndex > decodeIndex)
        assertTrue(operationsIndex > managerIndex)
        assertTrue(portIndex > operationsIndex)
        assertTrue(executorIndex > portIndex)
        assertFalse(body.contains("ExactDownloadAttemptExecutor(port).execute(attempt)\n"))
        assertTrue(body.contains("DownloadAttemptExecutionResult.SUCCESS -> Result.success()"))
        assertTrue(body.contains("DownloadAttemptExecutionResult.RETRY -> Result.retry()"))
        assertTrue(body.contains("DownloadAttemptExecutionResult.FAILURE -> Result.failure()"))
        assertTrue(source.contains("DownloadCancelReceiver.pendingIntent(applicationContext, attempt)"))
        assertTrue(source.contains("createForegroundInfo(attempt,"))

        listOf(
            "inputData.getString(",
            "KEY_ARTIFACT_TIER",
            "KEY_BACKEND",
            "KEY_TIER",
            "selectionFromInput",
            "runCatching",
            "clearDownloadRegistration",
            "runAttemptCount",
            "MAX_DOWNLOAD_ATTEMPTS",
            "MAX_SELF_TEST_ATTEMPTS",
            "endsWith(\".candidate\")",
        ).forEach { forbidden ->
            assertFalse(forbidden, source.contains(forbidden))
        }
    }

    private fun port(
        operations: RecordingModelOperations,
        services: RecordingServices = RecordingServices(attempt),
    ): ExactManagerDownloadAttemptPort<File> = ExactManagerDownloadAttemptPort(
        exactAttempt = attempt,
        modelOperations = operations,
        services = services,
        stopped = { false },
    )

    private fun observation(phase: DownloadPhase, downloaded: Long): DownloadObservation =
        DownloadObservation(
            generation = attempt.generation,
            phase = phase,
            downloadedBytes = downloaded,
            totalBytes = GemmaModelCatalog.fast.sizeBytes,
            network = DownloadNetwork.WIFI,
            verificationSucceeded = phase == DownloadPhase.READY,
            cancellationAllowed = phase in setOf(
                DownloadPhase.PREPARING,
                DownloadPhase.DOWNLOADING,
                DownloadPhase.VERIFYING,
                DownloadPhase.WAITING,
            ),
            retryAllowed = phase == DownloadPhase.FAILED,
        )

    private fun classBody(source: String, declaration: String): String {
        val declarationIndex = source.indexOf(declaration)
        assertTrue("Missing declaration: $declaration", declarationIndex >= 0)
        val open = source.indexOf('{', declarationIndex)
        assertTrue("Missing body: $declaration", open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        error("Unclosed declaration: $declaration")
    }

    private object FakeQualification : ExactDownloadQualification

    private class RecordingModelOperations(
        override val target: DownloadTargetKey,
        override val artifact: AiModelArtifact = DownloadTargetCatalog.artifactFor(target),
    ) : ExactModelDownloadOperations<File> {
        var exactInstalled: File? = null
        var candidate: File? = null
        var activeUnverified: File? = null
        var partial = 0L
        var downloadResult = File("downloaded.bin")
        var activatedModel = File("active.bin")
        var managerTargetMatches = true
        val qualifiedModels = mutableListOf<File>()
        val candidateHashStoppedValues = mutableListOf<Boolean>()
        val selfTestStoppedValues = mutableListOf<Boolean>()
        val readyModels = mutableListOf<File>()
        val callbackEvents = mutableListOf<String>()
        var activationCalls = 0

        override fun exactInstalledModel(): File? = exactInstalled

        override fun candidateModel(): File? = candidate

        override fun activeUnverifiedModel(): File? = activeUnverified

        override fun partialBytes(): Long = partial

        override fun download(
            stopped: () -> Boolean,
            progress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        ): File {
            progress(artifact.sizeBytes, artifact.sizeBytes)
            return downloadResult
        }

        override fun qualify(
            model: File,
            stopped: () -> Boolean,
        ): ExactDownloadQualification? {
            qualifiedModels += model
            candidateHashStoppedValues += stopped()
            selfTestStoppedValues += stopped()
            return FakeQualification
        }

        override fun activate(qualification: ExactDownloadQualification): File {
            assertTrue(qualification === FakeQualification)
            activationCalls += 1
            return activatedModel
        }

        override fun managerTargetMatches(): Boolean = managerTargetMatches

        override fun onWaiting() {
            callbackEvents += "waiting"
        }

        override fun onFailed(failureCode: DownloadFailureCode) {
            callbackEvents += "failed:$failureCode"
        }

        override fun onReady(model: File) {
            readyModels += model
            callbackEvents += "ready:${model.name}"
        }
    }

    private class RecordingServices(
        private val exactAttempt: DownloadAttempt,
    ) : ExactDownloadServiceOperations {
        var telemetry = telemetry(DownloadPhase.PREPARING, 0L)
        val networkDecision = DownloadNetworkDecision(
            DownloadNetwork.WIFI,
            DownloadNetworkPolicyEvaluation.Allowed,
        )
        val currentCalls = mutableListOf<DownloadAttempt>()
        val restoreCalls = mutableListOf<DownloadTargetKey>()
        val networkCalls = mutableListOf<DownloadTargetKey>()
        val publishCalls = mutableListOf<Published>()
        val commitCalls = mutableListOf<Committed>()
        val events = mutableListOf<String>()

        override fun isCurrent(attempt: DownloadAttempt): Boolean {
            currentCalls += attempt
            events += "current"
            return attempt == exactAttempt && telemetry.phase !in TERMINAL_PHASES
        }

        override fun restoreTelemetry(target: DownloadTargetKey): DownloadTelemetry? {
            restoreCalls += target
            events += "restore"
            return telemetry.takeIf { target == exactAttempt.target }
        }

        override fun networkDecision(target: DownloadTargetKey): DownloadNetworkDecision {
            networkCalls += target
            events += "network"
            return networkDecision
        }

        override fun publish(
            attempt: DownloadAttempt,
            observation: DownloadObservation,
            finalByteCount: Boolean,
        ): DownloadPublishResult {
            publishCalls += Published(attempt, observation, finalByteCount)
            events += "publish"
            if (attempt != exactAttempt) return DownloadPublishResult.Stale
            telemetry = telemetry(observation.phase, observation.downloadedBytes)
            return DownloadPublishResult.Persisted
        }

        override fun commitReadyIfCurrent(
            attempt: DownloadAttempt,
            readyObservation: DownloadObservation,
            action: () -> Unit,
        ): Boolean {
            commitCalls += Committed(attempt, readyObservation)
            events += "commit"
            if (attempt != exactAttempt || telemetry.phase != DownloadPhase.VERIFYING) return false
            action()
            telemetry = telemetry(DownloadPhase.READY, readyObservation.downloadedBytes)
            return true
        }

        private fun telemetry(phase: DownloadPhase, downloaded: Long): DownloadTelemetry =
            DownloadTelemetryRestoreFactory().restore(
                observation = DownloadObservation(
                    generation = exactAttempt.generation,
                    phase = phase,
                    downloadedBytes = downloaded,
                    totalBytes = DownloadTargetCatalog.artifactFor(exactAttempt.target).sizeBytes,
                    network = DownloadNetwork.WIFI,
                    verificationSucceeded = phase == DownloadPhase.READY,
                    cancellationAllowed = phase in ACTIVE_PHASES,
                    retryAllowed = phase == DownloadPhase.FAILED,
                ),
                updatedAtMillis = 1L,
                samples = emptyList(),
            )

        private companion object {
            val ACTIVE_PHASES = setOf(
                DownloadPhase.PREPARING,
                DownloadPhase.DOWNLOADING,
                DownloadPhase.VERIFYING,
                DownloadPhase.WAITING,
            )
            val TERMINAL_PHASES = setOf(
                DownloadPhase.READY,
                DownloadPhase.PAUSED,
                DownloadPhase.FAILED,
            )
        }
    }

    private data class Published(
        val attempt: DownloadAttempt,
        val observation: DownloadObservation,
        val finalByteCount: Boolean,
    )

    private data class Committed(
        val attempt: DownloadAttempt,
        val observation: DownloadObservation,
    )

    private val root = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val sourceRoot = File(root, "app/src/main/java/com/echolink/mobile")
    private val adapterFile = File(sourceRoot, "ManagerDownloadAttemptPorts.kt")
    private val gemmaWorkerSource = File(sourceRoot, "AiModelDownloadWorker.kt").readText()
    private val qwenWorkerSource = File(sourceRoot, "Qwen3ModelDownloadWorker.kt").readText()

    private companion object {
        val gemmaTarget = DownloadTargetKey(
            AiModelFamily.GEMMA,
            AiModelTier.FAST,
            BackendKind.LITERT_CPU,
        )
        val legacyTarget = DownloadTargetKey(
            AiModelFamily.LEGACY_QWEN,
            AiModelTier.LEGACY_COMPACT,
            BackendKind.LEGACY_LLAMA_CPU,
        )
        val attempt = DownloadAttempt(
            target = gemmaTarget,
            generation = DownloadGeneration(1L),
            workIdentity = DownloadWorkIdentity(31L, 32L),
        )
    }
}
