package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Permanent product rule, set by Curtis on 2026-08-01: a Manuscript correction
 * never contains an em dash. Locked here so no future prompt or model swap can
 * reintroduce one.
 */
class NoEmDashRuleTest {
    private val emDash = '—'

    @Test
    fun inventedEmDashesAreRewrittenNotShipped() {
        val original = "the meeting ran long we missed the bus"
        val candidate = "The meeting ran long ${emDash} we missed the bus."

        val cleaned = AiSafetyValidator.removeInventedEmDashes(original, candidate)

        assertFalse(cleaned.contains(emDash))
        assertEquals("The meeting ran long, we missed the bus.", cleaned)
    }

    @Test
    fun joinerEmDashBecomesAHyphen() {
        val cleaned = AiSafetyValidator.removeInventedEmDashes(
            "the on site team",
            "the on${emDash}site team",
        )

        assertFalse(cleaned.contains(emDash))
        assertEquals("the on-site team", cleaned)
    }

    @Test
    fun theWritersOwnEmDashIsLeftAlone() {
        val original = "The vendor ${emDash} our oldest ${emDash} is late."
        val candidate = "The vendor ${emDash} our oldest ${emDash} is late again."

        assertEquals(
            candidate,
            AiSafetyValidator.removeInventedEmDashes(original, candidate),
        )
    }

    @Test
    fun validationStillRejectsAnEmDashThatSurvives() {
        val validation = AiSafetyValidator.validate(
            original = "we was ready",
            candidate = "We were ready ${emDash} finally.",
        )

        assertTrue(SafetyIssue.EM_DASH_PRESENT in validation.hardFailures)
    }

    @Test
    fun everyPromptStillForbidsEmDashes() {
        val sourceRoot = File(
            checkNotNull(System.getProperty("echoLinkAndroidDir")),
            "app/src/main/java/com/echolink/mobile",
        )
        listOf(
            "TonePromptLibrary.kt",
            "Qwen3GrammarRuntime.kt",
            "LiteRtGemmaBackend.kt",
        ).forEach { name ->
            val text = File(sourceRoot, name).readText().lowercase()
            assertTrue("$name must forbid em dashes", "em dash" in text)
        }
    }
}
