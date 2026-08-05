package com.echolink.mobile

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Qwen3ModelManagerSafetySourceTest {
    private val source = File(
        checkNotNull(System.getProperty("echoLinkAndroidDir")),
        "app/src/main/java/com/echolink/mobile/Qwen3ModelManager.kt",
    ).readText()

    @Test
    fun sharedInstallerOwnsStagingResumeMathAndPartialByteReporting() {
        val requiredStorage = source.substringAfter("fun requiredStorageBytes(")
            .substringBefore("fun downloadNetworkPolicy(")
        val download = source.substringAfter("internal fun downloadTier(")
            .substringBefore("internal fun onDownloadWaiting(")

        assertTrue(source.contains("private val installer = AiModelInstaller(modelDirectory)"))
        assertTrue(requiredStorage.contains("installer.remainingBytes("))
        assertTrue(download.contains("LegacyQwenModelCatalog.forTier(tier)"))
        assertTrue(download.contains("installer.remainingBytes(artifact)"))
        assertTrue(download.contains("installer.stage(artifact, stopped)"))
        assertTrue(download.contains("internal fun partialBytes(tier: Qwen3Tier)"))
        assertTrue(download.contains("installer.partialBytes(artifact)"))
        assertFalse(source.contains("private fun installModel("))
        assertFalse(source.contains("import java.io.FileOutputStream"))
        assertFalse(source.contains("import java.net.HttpURLConnection"))
        assertFalse(source.contains("import java.net.URL"))
        assertFalse(source.contains("import java.security.MessageDigest"))
    }

    @Test
    fun candidateLookupAndActivationUseTheGuardedCanonicalInstallerPaths() {
        val candidate = source.substringAfter("internal fun candidateModel(")
            .substringBefore("fun ensureModelAsync(")
        val qualification = source.substringAfter("internal fun qualifyCandidate(")
            .substringBefore("internal fun qualifyActiveModel(")
        val activation = source.substringAfter("internal fun activateQualifiedCandidate(")
            .substringBefore("private fun enqueueDownload(")
        val proof = source.substringAfter("internal fun markSelfTested(")
            .substringBefore("internal fun qualifyCandidate(")

        assertTrue(candidate.contains("LegacyQwenModelCatalog.forTier(tier)"))
        assertTrue(candidate.contains("installer.candidateFile(artifact)"))
        assertTrue(candidate.contains("it.length() == artifact.sizeBytes"))
        assertFalse(candidate.contains("candidateShaKey"))
        assertTrue(qualification.contains("installer.qualifyCandidate("))
        assertTrue(qualification.contains("stopped = stopped"))
        assertTrue(activation.contains("LegacyQwenModelCatalog.forTier(tier)"))
        assertTrue(activation.contains("installer.activateQualifiedCandidate(qualification)"))
        assertTrue(activation.contains("check(activation.activated)"))
        assertTrue(activation.contains("markSelfTested(tier, activation.activeFile)"))
        assertTrue(proof.contains("installer.activeProof(artifact)"))
        assertTrue(proof.contains("putString(verifiedShaKey(tier), tier.sha256)"))
        assertTrue(proof.contains("check(editor.commit())"))
        assertFalse(activation.contains("renameTo("))
        assertFalse(activation.contains("previousFile"))
    }

    @Test
    fun failureStateUsesOnlyTheSharedFixedSafeMessage() {
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
            .substringBefore("internal fun onDownloadReady(")
        assertTrue(handler.contains("safeAiModelDownloadFailure(failure).userMessage"))
        assertFalse(handler.contains("Log."))
        assertFalse(handler.contains("failure.message"))
        assertFalse(source.contains("import android.util.Log"))
    }

    @Test
    fun automaticReadyPathNeverDeletesAnotherModelButExplicitCleanupStillDoes() {
        val ready = source.substringAfter("internal fun onDownloadReady(")
            .substringBefore("internal fun markSelfTested(")
        val cleanup = source.substringAfter("fun removeAllModels()")
            .substringBefore("fun recordInference(")

        assertFalse(ready.contains(".delete()"))
        assertFalse(ready.contains("LEGACY_BALANCED_FILE"))
        assertTrue(cleanup.contains("file.delete()"))
    }

    @Test
    fun installedLookupStillRequiresVerifiedAndSelfTestedExactTier() {
        val installed = source.substringAfter("fun installedModel(tier: Qwen3Tier)")
            .substringBefore("internal fun candidateModel(")

        assertTrue(installed.contains("if (!isSelfTested(tier)) return null"))
        assertTrue(installed.contains("it.length() == tier.sizeBytes"))
        assertTrue(installed.contains("if (verifiedSha(tier) != tier.sha256) return null"))
        assertTrue(installed.contains("if (!activeProofMatches(tier)) return null"))
    }

    @Test
    fun selfTestQualificationIsSeparateFromShortGuardedActivation() {
        val qualification = source.substringAfter("internal fun qualifyCandidate(")
            .substringBefore("internal fun activateQualifiedCandidate(")
        val activation = source.substringAfter("internal fun activateQualifiedCandidate(")
            .substringBefore("internal fun activateCandidate(")

        assertTrue(qualification.contains("installer.qualifyCandidate("))
        assertTrue(qualification.contains("Qwen3ModelSelfTester.verify("))
        assertTrue(qualification.contains("installer.discardRejectedModel("))
        assertFalse(qualification.contains("markSelfTested("))
        assertTrue(activation.contains("installer.activateQualifiedCandidate(qualification)"))
        assertTrue(activation.contains("markSelfTested(tier, activation.activeFile)"))
        assertFalse(activation.contains("Qwen3ModelSelfTester.verify("))
    }
}
