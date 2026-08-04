package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A device whose hardware policy selects a legacy Qwen tier must still be
 * able to install AI from the one shared consent dialog. The two managers
 * keep separate preference files, so the consent answer has to be mirrored;
 * without it the install button silently does nothing.
 */
class LegacyInstallHandoffSourceTest {
    private val source = File(
        checkNotNull(System.getProperty("echoLinkAndroidDir")),
        "app/src/main/java/com/echolink/mobile/AiModelManager.kt",
    ).readText()

    @Test
    fun consentIsMirroredToTheLegacyManager() {
        assertTrue(source.contains("private fun mirrorConsentToLegacyManager"))
        assertTrue(source.contains("legacy.grantDownloadConsent("))
        assertTrue(
            source.contains("Qwen3DownloadNetworkPolicy.ALLOW_MOBILE_DATA"),
        )
    }

    @Test
    fun legacySelectionMirrorsExistingConsentBeforeDelegating() {
        val branch = source.substringAfter(
            "if (selection.artifact.family == AiModelFamily.LEGACY_QWEN) {",
        ).substringBefore("Qwen3ModelManager.get(appContext).ensureModelAsync()")
        assertTrue(branch.contains("mirrorConsentToLegacyManager(downloadNetworkPolicy())"))
        assertTrue(branch.contains("pruneUnselectedGemmaWeights()"))
    }

    @Test
    fun unselectedGemmaWeightsArePruned() {
        assertTrue(source.contains("fun pruneUnselectedGemmaWeights()"))
        // Never delete weights that are still the selected runtime.
        val prune = source.substringAfter("fun pruneUnselectedGemmaWeights() {")
            .substringBefore("fun removeAllModels")
        assertTrue(
            prune.contains("if (selectedRuntime().artifact.family == AiModelFamily.GEMMA) return"),
        )
    }
}
