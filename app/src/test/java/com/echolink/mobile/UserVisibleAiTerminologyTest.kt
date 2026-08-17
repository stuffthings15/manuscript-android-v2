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
    fun versionTwoDistributionCopyMatchesItsOwnPlayApp() {
        val distributionCopy = source("play/README.md")

        // This asserted that the copy kept Play DISABLED and said "USB-only".
        // Curtis decided on 2026-08-16 that Version 2 ships as its own Play
        // app, so that assertion described a rule that no longer exists. The
        // reason the rule existed does still exist, and is what is checked
        // now: V2 must not collide with Version 1's listing.
        assertTrue(distributionCopy.contains("com.remote.Manuscript.v2"))

        // Version 1's tester opt-in URL must never be reused. A new app issues
        // its own, and pasting the old one sends testers to the wrong product.
        assertFalse(distributionCopy.contains("internaltest/"))

        assertFalse(
            "Version 2 distribution copy exposed a model name: $distributionCopy",
            forbidden.containsMatchIn(distributionCopy),
        )
    }

    @Test
    fun theUploadChecklistDoesNotReuseVersionOnesTesterLink() {
        // Same rule, the other file. The checklist is the document somebody
        // follows while clicking through Play Console, so it is the likeliest
        // place for a copied link to do damage.
        val readiness = source("play/UPLOAD_READINESS.md")

        assertTrue(readiness.contains("com.remote.Manuscript.v2"))
        assertFalse(readiness.contains("internaltest/"))
        assertFalse(
            "Upload checklist exposed a model name: $readiness",
            forbidden.containsMatchIn(readiness),
        )
    }

    private fun source(relativePath: String): String {
        val androidDir = System.getProperty("echoLinkAndroidDir")
            ?: error("echoLinkAndroidDir is unavailable")
        return File(androidDir, relativePath).readText()
    }
}
