package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RED contract for manager integration with the typed download lifecycle. */
class ModelManagerDownloadSchedulingContractTest {
    @Test
    fun initialEnsureDelegatesOnlyTheExactCanonicalTargetAndNeverRetries() {
        val services = RecordingCommandServices()
        val core = ModelDownloadCommandCore(services)
        val target = DownloadTargetCatalog.targetFor(
            AiRuntimeSelection(GemmaModelCatalog.fast, BackendKind.LITERT_GPU),
        )
        val existingTerminal = attempt(target, generation = 4L, uuidBit = 44L)
        services.terminalAttempts[target] = existingTerminal
        services.ensureResults[target] = existingTerminal

        assertEquals(existingTerminal, core.ensure(target))
        assertEquals(listOf(target), services.ensureCalls)
        assertTrue(services.terminalRestoreCalls.isEmpty())
        assertTrue(services.retryCalls.isEmpty())
        assertTrue(services.activeRestoreCalls.isEmpty())
        assertTrue(services.cancelCalls.isEmpty())
    }

    @Test
    fun explicitRetryRestoresAndRetriesOnlyTheExactTerminalAttempt() {
        val services = RecordingCommandServices()
        val core = ModelDownloadCommandCore(services)
        val target = gemmaFastCpu
        val terminal = attempt(target, generation = 8L, uuidBit = 80L)
        val retried = attempt(target, generation = 9L, uuidBit = 90L)
        services.terminalAttempts[target] = terminal
        services.retryResults[terminal] = retried

        assertEquals(retried, core.retryTerminal(target))
        assertEquals(listOf(target), services.terminalRestoreCalls)
        assertEquals(listOf(terminal), services.retryCalls)
        assertTrue(services.ensureCalls.isEmpty())
        assertTrue(services.activeRestoreCalls.isEmpty())
        assertTrue(services.cancelCalls.isEmpty())
    }

    @Test
    fun explicitRetryWithNoTerminalAttemptDoesNothingAndNeverEnsures() {
        val services = RecordingCommandServices()
        val core = ModelDownloadCommandCore(services)

        assertNull(core.retryTerminal(gemmaFastCpu))
        assertEquals(listOf(gemmaFastCpu), services.terminalRestoreCalls)
        assertTrue(services.retryCalls.isEmpty())
        assertTrue(services.ensureCalls.isEmpty())
    }

    @Test
    fun removalCancelsEveryDistinctActiveAttemptBeforeReturningPermissionToDelete() {
        val services = RecordingCommandServices()
        val core = ModelDownloadCommandCore(services)
        val gemma = attempt(gemmaFastCpu, generation = 2L, uuidBit = 20L)
        val legacy = attempt(legacyBasic, generation = 5L, uuidBit = 50L)
        services.activeAttempts[gemma.target] = gemma
        services.activeAttempts[legacy.target] = legacy

        val mayDelete = core.cancelActiveBeforeRemoval(
            listOf(gemma.target, legacy.target, gemma.target),
        )

        assertTrue(mayDelete)
        assertEquals(listOf(gemma.target, legacy.target), services.activeRestoreCalls)
        assertEquals(listOf(gemma, legacy), services.cancelCalls)
        assertEquals(
            listOf(
                "restore-active:${gemma.target}",
                "cancel:$gemma",
                "restore-active:${legacy.target}",
                "cancel:$legacy",
            ),
            services.events,
        )
        assertTrue(services.ensureCalls.isEmpty())
        assertTrue(services.terminalRestoreCalls.isEmpty())
        assertTrue(services.retryCalls.isEmpty())
    }

    @Test
    fun failedCancellationDeniesDeletionAndNeverCreatesAReplacementAttempt() {
        val services = RecordingCommandServices()
        val core = ModelDownloadCommandCore(services)
        val first = attempt(gemmaFastCpu, generation = 3L, uuidBit = 30L)
        val second = attempt(gemmaThoroughGpu, generation = 6L, uuidBit = 60L)
        services.activeAttempts[first.target] = first
        services.activeAttempts[second.target] = second
        services.cancelResults[first] = false

        assertFalse(core.cancelActiveBeforeRemoval(listOf(first.target, second.target)))
        assertEquals(listOf(first.target), services.activeRestoreCalls)
        assertEquals(listOf(first), services.cancelCalls)
        assertTrue(services.ensureCalls.isEmpty())
        assertTrue(services.retryCalls.isEmpty())
    }

    @Test
    fun catalogEnumeratesEveryCanonicalTargetForSafeFamilyRemoval() {
        assertEquals(gemmaTargets, DownloadTargetCatalog.targetsFor(AiModelFamily.GEMMA).toSet())
        assertEquals(
            legacyTargets,
            DownloadTargetCatalog.targetsFor(AiModelFamily.LEGACY_QWEN).toSet(),
        )
        assertEquals(
            DownloadTargetCatalog.targetsFor(AiModelFamily.GEMMA).size,
            DownloadTargetCatalog.targetsFor(AiModelFamily.GEMMA).distinct().size,
        )
        assertEquals(
            DownloadTargetCatalog.targetsFor(AiModelFamily.LEGACY_QWEN).size,
            DownloadTargetCatalog.targetsFor(AiModelFamily.LEGACY_QWEN).distinct().size,
        )
    }

    @Test
    fun bothManagersUseTheSharedCommandCoreAndNoLegacyWorkRegistration() {
        listOf(gemmaManagerSource, legacyManagerSource).forEach { source ->
            assertTrue(source.contains("ModelDownloadCommandCore("))
            assertTrue(source.contains("AndroidDownloadServices.get(appContext)"))
            listOf(
                "WorkManager",
                "OneTimeWorkRequestBuilder",
                "ExistingWorkPolicy",
                "enqueueUniqueWork",
                "cancelUniqueWork",
                "workDataOf",
                "BackoffPolicy",
                "Constraints.Builder",
                "KEY_ENQUEUED_",
                "MODEL_DOWNLOAD_WORK",
                "MODEL_DOWNLOAD_TAG",
                "clearDownloadRegistration",
            ).forEach { forbidden ->
                assertFalse(forbidden, source.contains(forbidden))
            }
        }
        assertFalse(gemmaManagerSource.contains("AiModelDownloadWorker.KEY_ARTIFACT_TIER"))
        assertFalse(gemmaManagerSource.contains("AiModelDownloadWorker.KEY_BACKEND"))
        assertFalse(legacyManagerSource.contains("Qwen3ModelDownloadWorker.KEY_TIER"))
    }

    @Test
    fun gemmaInitialSchedulingUsesTheCanonicalSelectionTargetAndOnlyEnsure() {
        val enqueue = functionBody(gemmaManagerSource, "private fun enqueueDownload(")

        assertTrue(enqueue.contains("DownloadTargetCatalog.targetFor(selection)"))
        assertTrue(enqueue.contains("downloadCommands.ensure(target)"))
        assertFalse(enqueue.contains("retryTerminal("))
        assertFalse(enqueue.contains("OneTimeWorkRequest"))
        assertFalse(enqueue.contains("WorkManager"))
    }

    @Test
    fun legacyInitialSchedulingBuildsOneCanonicalLegacyTargetAndOnlyEnsures() {
        val enqueue = functionBody(legacyManagerSource, "private fun enqueueDownload(")

        assertTrue(enqueue.contains("LegacyQwenModelCatalog.forTier(tier)"))
        assertTrue(enqueue.contains("BackendKind.LEGACY_LLAMA_CPU"))
        assertTrue(enqueue.contains("DownloadTargetCatalog.targetFor(selection)"))
        assertTrue(enqueue.contains("downloadCommands.ensure(target)"))
        assertFalse(enqueue.contains("retryTerminal("))
        assertFalse(enqueue.contains("OneTimeWorkRequest"))
        assertFalse(enqueue.contains("WorkManager"))
    }

    @Test
    fun ordinaryEnsureApisCannotAutoRetryPausedOrFailedAttempts() {
        listOf(
            gemmaManagerSource to "fun ensureModelAsync()",
            legacyManagerSource to "fun ensureModelAsync(tier: Qwen3Tier = selectedTier())",
        ).forEach { (source, signature) ->
            val ensure = functionBody(source, signature)

            assertFalse(ensure.contains("retryModelDownload("))
            assertFalse(ensure.contains("retryTerminal("))
            assertFalse(ensure.contains("services.retry("))
        }
    }

    @Test
    fun bothManagersExposeOneExplicitRetryApiUsingTheCanonicalExactTarget() {
        val gemmaRetry = functionBody(gemmaManagerSource, "fun retryModelDownload()")
        val legacyRetry = functionBody(legacyManagerSource, "fun retryModelDownload()")

        assertTrue(gemmaRetry.contains("selectedRuntime()"))
        assertTrue(gemmaRetry.contains("DownloadTargetCatalog.targetFor(selection)"))
        assertTrue(gemmaRetry.contains("downloadCommands.retryTerminal(target)"))
        assertFalse(gemmaRetry.contains("downloadCommands.ensure("))

        assertTrue(legacyRetry.contains("selectedTier()"))
        assertTrue(legacyRetry.contains("LegacyQwenModelCatalog.forTier(tier)"))
        assertTrue(legacyRetry.contains("BackendKind.LEGACY_LLAMA_CPU"))
        assertTrue(legacyRetry.contains("DownloadTargetCatalog.targetFor(selection)"))
        assertTrue(legacyRetry.contains("downloadCommands.retryTerminal(target)"))
        assertFalse(legacyRetry.contains("downloadCommands.ensure("))
    }

    @Test
    fun mainActivityRetryStartupActionUsesExplicitRetryNotOrdinaryEnsure() {
        val retryBranch = mainActivitySource
            .substringAfter("ManuscriptStartupAction.RETRY_MODEL -> {")
            .substringBefore("ManuscriptStartupAction.WAIT_FOR_MODEL ->")

        assertTrue(retryBranch.contains("performDownloadAction(DownloadAction.RETRY)"))
        assertFalse(retryBranch.contains("manager.ensureModelAsync()"))
    }

    @Test
    fun everyManagerWeightRemovalCancelsItsWholeFamilyBeforeDeletingAnything() {
        val gemmaRemove = functionBody(gemmaManagerSource, "fun removeAllModels()")
        val gemmaPrune = functionBody(gemmaManagerSource, "fun pruneUnselectedGemmaWeights()")
        val legacyRemove = functionBody(legacyManagerSource, "fun removeAllModels()")

        assertCancellationBeforeDeletion(
            gemmaRemove,
            "DownloadTargetCatalog.targetsFor(AiModelFamily.GEMMA)",
        )
        assertCancellationBeforeDeletion(
            gemmaPrune,
            "DownloadTargetCatalog.targetsFor(AiModelFamily.GEMMA)",
        )
        assertCancellationBeforeDeletion(
            legacyRemove,
            "DownloadTargetCatalog.targetsFor(AiModelFamily.LEGACY_QWEN)",
        )
    }

    private fun assertCancellationBeforeDeletion(body: String, targetExpression: String) {
        val cancelIndex = body.indexOf("downloadCommands.cancelActiveBeforeRemoval(")
        val deleteIndex = body.indexOf(".delete()")

        assertTrue(body.contains(targetExpression))
        assertTrue(body.contains("if (!downloadCommands.cancelActiveBeforeRemoval("))
        assertTrue(cancelIndex >= 0)
        assertTrue(deleteIndex > cancelIndex)
        assertFalse(body.contains("downloadCommands.ensure("))
        assertFalse(body.contains("ensureModelAsync("))
        assertFalse(body.contains("WorkManager"))
    }

    private fun functionBody(source: String, signature: String): String {
        val signatureIndex = source.indexOf(signature)
        assertTrue("Missing function: $signature", signatureIndex >= 0)
        val open = source.indexOf('{', signatureIndex)
        assertTrue("Missing function body: $signature", open >= 0)
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
        error("Unclosed function body: $signature")
    }

    private fun attempt(
        target: DownloadTargetKey,
        generation: Long,
        uuidBit: Long,
    ): DownloadAttempt = DownloadAttempt(
        target = target,
        generation = DownloadGeneration(generation),
        workIdentity = DownloadWorkIdentity(uuidBit, uuidBit + 1L),
    )

    private class RecordingCommandServices : ModelDownloadCommandServices {
        val ensureResults = mutableMapOf<DownloadTargetKey, DownloadAttempt>()
        val terminalAttempts = mutableMapOf<DownloadTargetKey, DownloadAttempt>()
        val activeAttempts = mutableMapOf<DownloadTargetKey, DownloadAttempt>()
        val retryResults = mutableMapOf<DownloadAttempt, DownloadAttempt?>()
        val cancelResults = mutableMapOf<DownloadAttempt, Boolean>()
        val ensureCalls = mutableListOf<DownloadTargetKey>()
        val terminalRestoreCalls = mutableListOf<DownloadTargetKey>()
        val activeRestoreCalls = mutableListOf<DownloadTargetKey>()
        val retryCalls = mutableListOf<DownloadAttempt>()
        val cancelCalls = mutableListOf<DownloadAttempt>()
        val events = mutableListOf<String>()

        override fun ensure(target: DownloadTargetKey): DownloadAttempt {
            ensureCalls += target
            events += "ensure:$target"
            return checkNotNull(ensureResults[target])
        }

        override fun terminalAttempt(target: DownloadTargetKey): DownloadAttempt? {
            terminalRestoreCalls += target
            events += "restore-terminal:$target"
            return terminalAttempts[target]
        }

        override fun activeAttempt(target: DownloadTargetKey): DownloadAttempt? {
            activeRestoreCalls += target
            events += "restore-active:$target"
            return activeAttempts[target]
        }

        override fun retry(attempt: DownloadAttempt): DownloadAttempt? {
            retryCalls += attempt
            events += "retry:$attempt"
            return retryResults[attempt]
        }

        override fun cancel(attempt: DownloadAttempt): Boolean {
            cancelCalls += attempt
            events += "cancel:$attempt"
            return cancelResults[attempt] ?: true
        }
    }

    private val root = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val sourceRoot = File(root, "app/src/main/java/com/echolink/mobile")
    private val gemmaManagerSource = File(sourceRoot, "AiModelManager.kt").readText()
    private val legacyManagerSource = File(sourceRoot, "Qwen3ModelManager.kt").readText()
    private val mainActivitySource = File(sourceRoot, "MainActivity.kt").readText()

    private val gemmaFastCpu = DownloadTargetKey(
        AiModelFamily.GEMMA,
        AiModelTier.FAST,
        BackendKind.LITERT_CPU,
    )
    private val gemmaThoroughGpu = DownloadTargetKey(
        AiModelFamily.GEMMA,
        AiModelTier.THOROUGH,
        BackendKind.LITERT_GPU,
    )
    private val legacyBasic = DownloadTargetKey(
        AiModelFamily.LEGACY_QWEN,
        AiModelTier.LEGACY_COMPACT,
        BackendKind.LEGACY_LLAMA_CPU,
    )
    private val gemmaTargets = setOf(
        gemmaFastCpu,
        DownloadTargetKey(AiModelFamily.GEMMA, AiModelTier.FAST, BackendKind.LITERT_GPU),
        DownloadTargetKey(AiModelFamily.GEMMA, AiModelTier.THOROUGH, BackendKind.LITERT_CPU),
        gemmaThoroughGpu,
    )
    private val legacyTargets = setOf(
        legacyBasic,
        DownloadTargetKey(
            AiModelFamily.LEGACY_QWEN,
            AiModelTier.LEGACY_BALANCED,
            BackendKind.LEGACY_LLAMA_CPU,
        ),
        DownloadTargetKey(
            AiModelFamily.LEGACY_QWEN,
            AiModelTier.LEGACY_ADVANCED,
            BackendKind.LEGACY_LLAMA_CPU,
        ),
    )
}
