package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserVisibleAiTerminologyTest {
    private val forbidden = Regex("(?i)\\b(?:qwen\\d*|models?)\\b")

    @Test
    fun appCopyCallsTheWritingEngineAiWithoutExposingImplementationNames() {
        val stringsXml = source("app/src/main/res/values/strings.xml")
        val visibleValues = Regex(
            "<string\\b[^>]*>(.*?)</string>",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).findAll(stringsXml).joinToString("\n") { it.groupValues[1] }

        assertTrue(visibleValues.contains("AI"))
        assertFalse("Visible Android copy exposed a model name: $visibleValues", forbidden.containsMatchIn(visibleValues))
        assertFalse(forbidden.containsMatchIn(requireNotNull(LocalModelRequiredException().message)))
        assertFalse(forbidden.containsMatchIn(requireNotNull(LocalRewriteRejectedException().message)))

        val tierSource = source("app/src/main/java/com/echolink/mobile/Qwen3ModelManager.kt")
            .substringAfter("enum class Qwen3Tier(")
            .substringBefore("object Qwen3CapabilitySelector")
        val tierLabels = Regex("displayName\\s*=\\s*\"([^\"]+)\"")
            .findAll(tierSource)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(setOf("AI"), tierLabels)
    }

    @Test
    fun versionTwoDistributionCopyKeepsPlayDisabled() {
        val distributionCopy = source("play/README.md")

        assertTrue(distributionCopy.contains("USB-only"))
        assertTrue(distributionCopy.contains("Do not"))
        assertFalse(distributionCopy.contains("internaltest/"))
        assertFalse(
            "Version 2 distribution copy exposed a model name: $distributionCopy",
            forbidden.containsMatchIn(distributionCopy),
        )
    }

    private fun source(relativePath: String): String {
        val androidDir = System.getProperty("echoLinkAndroidDir")
            ?: error("echoLinkAndroidDir is unavailable")
        return File(androidDir, relativePath).readText()
    }
}
