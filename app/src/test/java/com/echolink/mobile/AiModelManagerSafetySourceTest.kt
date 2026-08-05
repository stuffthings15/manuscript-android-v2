package com.echolink.mobile

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelManagerSafetySourceTest {
    private val source = File(
        checkNotNull(System.getProperty("echoLinkAndroidDir")),
        "app/src/main/java/com/echolink/mobile/AiModelManager.kt",
    ).readText()

    @Test
    fun exactLookupUsesOnlyTheRequestedLegacyTierWhileRuntimeLookupKeepsFallback() {
        val exactLookup = source.substringAfter("fun exactInstalledModel(")
            .substringBefore("fun installedModel(")
        val runtimeLookup = source.substringAfter("fun installedModel(")
            .substringBefore("fun installedFile()")

        assertTrue(exactLookup.contains("manager.installedModel(tier)"))
        assertFalse(exactLookup.contains("manager.installedModel()"))
        assertFalse(exactLookup.contains("installedLegacyFallbackModel()"))
        assertTrue(runtimeLookup.contains("exactInstalledModel(selection)"))
        assertTrue(runtimeLookup.contains("installedLegacyFallbackModel()"))
    }

    @Test
    fun automaticEnsureNeverPrunesWeightsButExplicitCleanupRemainsAvailable() {
        val ensure = source.substringAfter("fun ensureModelAsync() {")
            .substringBefore("fun verifyInstalledModelAsync")

        assertFalse(ensure.contains("pruneUnselectedGemmaWeights()"))
        assertTrue(source.contains("fun pruneUnselectedGemmaWeights()"))
    }

    @Test
    fun stagingUsesResumablePartialBytesAndExposesTheSameOffset() {
        val stage = source.substringAfter("internal fun stageDownload(")
            .substringBefore("internal fun candidateModel(")

        assertTrue(stage.contains("installer.remainingBytes(artifact)"))
        assertFalse(stage.contains("candidateFile(artifact).length()"))
        assertTrue(stage.contains("internal fun partialBytes("))
        assertTrue(stage.contains("installer.partialBytes(artifact)"))
    }

    @Test
    fun failureMappingReturnsOnlyFixedSafeMessages() {
        val privateText = "private path and server details"
        val cases = listOf(
            AiModelIntegrityException(privateText) to
                SafeDownloadFailure.INTEGRITY_CHECK_FAILED,
            InterruptedIOException(privateText) to
                SafeDownloadFailure.DOWNLOAD_INTERRUPTED,
            IOException(privateText) to SafeDownloadFailure.UNKNOWN,
            IllegalStateException(privateText) to SafeDownloadFailure.UNKNOWN,
        )

        cases.forEach { (failure, expected) ->
            val mapped = safeAiModelDownloadFailure(failure)

            assertEquals(expected, mapped)
            assertFalse(mapped.userMessage.contains(privateText))
        }
        val handler = source.substringAfter("internal fun onDownloadFailed(")
            .substringBefore("internal fun qualifyAndActivate(")
        assertTrue(handler.contains("safeAiModelDownloadFailure(failure).userMessage"))
        assertFalse(handler.contains("failure.message"))
    }

    @Test
    fun selfTestQualificationIsSeparateFromShortGuardedActivation() {
        val qualification = source.substringAfter("internal fun qualifyCandidate(")
            .substringBefore("internal fun activateQualifiedCandidate(")
        val activation = source.substringAfter("internal fun activateQualifiedCandidate(")
            .substringBefore("internal fun qualifyAndActivate(")
        val finish = source.substringAfter("private fun finishActivation(")
            .substringBefore("private fun qualifyGpuIfSupported(")
        val proof = source.substringAfter("private fun markSelfTested(")
            .substringBefore("private fun clearSelfTested(")
        val exactLookup = source.substringAfter("fun exactInstalledModel(")
            .substringBefore("fun installedModel(")

        assertTrue(qualification.contains("installer.qualifyCandidate("))
        assertTrue(qualification.contains("GemmaModelSelfTester.verify("))
        assertTrue(qualification.contains("installer.discardRejectedModel("))
        assertFalse(qualification.contains("markSelfTested("))
        assertTrue(activation.contains("installer.activateQualifiedCandidate(qualification)"))
        assertTrue(activation.contains("finishActivation(selection, activation.activeFile)"))
        assertTrue(finish.contains("markSelfTested(selection.artifact, activeFile)"))
        assertTrue(proof.contains("installer.activeProof(artifact)"))
        assertTrue(proof.contains("check(editor.commit())"))
        assertTrue(exactLookup.contains("activeProofMatches(selection.artifact)"))
        assertTrue(exactLookup.contains("isBackendQualified(selection)"))
        assertFalse(activation.contains("GemmaModelSelfTester.verify("))
    }

    @Test
    fun qualificationProofAndRejectionAreExactToArtifactAndBackend() {
        val qualification = source.substringAfter("private fun persistBackendQualification(")
            .substringBefore("private fun persistBackendRejection(")
        val rejection = source.substringAfter("private fun persistBackendRejection(")
            .substringBefore("private fun transitionToFallbackAfterRejection(")

        listOf(qualification, rejection).forEach { record ->
            assertTrue(record.contains("selection.artifact"))
            assertTrue(record.contains("selection.backend"))
            assertTrue(record.contains("selection.artifact.sha256"))
            assertTrue(record.contains(".commit()"))
        }
        assertTrue(qualification.contains("qualificationKey("))
        assertTrue(qualification.contains("rejectionKey("))
        assertTrue(rejection.contains("rejectionKey("))
        assertTrue(rejection.contains("qualificationKey("))
    }

    @Test
    fun everyGemmaScheduleLocksTheExactSelectionBeforeStartingWork() {
        val enqueue = source.substringAfter("private fun enqueueDownload(")
            .substringBefore("private fun restoredRuntimeSelection(")
        val lockIndex = enqueue.indexOf("lockRuntimeSelection(selection)")
        val ensureIndex = enqueue.indexOf("downloadCommands.ensure(target)")
        val retry = source.substringAfter("fun retryModelDownload()")
            .substringBefore("fun verifyInstalledModelAsync")

        assertTrue(lockIndex >= 0)
        assertTrue(ensureIndex > lockIndex)
        assertTrue(retry.contains("lockRuntimeSelection(selection)"))
        assertTrue(source.contains("KEY_RUNTIME_LOCK_SHA256"))
        assertTrue(source.contains("KEY_RUNTIME_LOCK_BACKEND"))
    }
}
