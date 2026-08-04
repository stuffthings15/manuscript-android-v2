package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManuscriptDeterministicToneTest {
    @Test
    fun keepsEveryMasterPlanToneChoiceStable() {
        assertEquals(23, WritingTone.entries.size)
        assertEquals(23, WritingTone.entries.map { it.label }.toSet().size)
    }

    @Test
    fun eachStyleHasMeaningfulDeterministicBehaviorForSuitableText() {
        val suitableText = linkedMapOf(
            WritingTone.IMPROVE to
                "In order to make a decision, we need to provide an explanation.",
            WritingTone.REPHRASE to
                "I would like to meet because we need to decide.",
            WritingTone.SHORTEN to
                "It is important to note that at this point in time we need to act in order to finish.",
            WritingTone.FRIENDLY to
                "We cannot finish because we do not have approval.",
            WritingTone.FORMAL to
                "We can't finish because we don't have approval.",
            WritingTone.PROFESSIONAL to
                "I think we should fix the issue as soon as possible.",
            WritingTone.DIPLOMATIC to
                "You are wrong, and you must change the plan.",
            WritingTone.EMPATHETIC to
                "I know this is frustrating. That is hard.",
            WritingTone.COOPERATIVE to
                "We need to review the plan together.",
            WritingTone.ASSERTIVE to
                "I think maybe we should act now.",
            WritingTone.CAVEMAN to
                "The detailed report is very useful for the entire project team.",
        )

        suitableText.forEach { (tone, source) ->
            val first = LocalWritingAssistant.suggest(source, tone)
            val second = LocalWritingAssistant.suggest(source, tone)
            val proofread = LocalWritingAssistant.suggest(source, WritingTone.PROOFREAD)

            assertEquals("$tone must be deterministic", first, second)
            assertNotEquals("$tone must do more than proofreading suitable text", proofread, first)
            assertTrue("$tone returned blank text", first.isNotBlank())
        }
    }

    @Test
    fun normalTextVisiblyRewritesWhenAStyleToneIsSelected() {
        val source = "The report is ready tomorrow."
        val proofread = LocalWritingAssistant.suggest(source, WritingTone.PROOFREAD)
        val framedTones = listOf(
            WritingTone.IMPROVE,
            WritingTone.REPHRASE,
            WritingTone.FRIENDLY,
            WritingTone.FORMAL,
            WritingTone.PROFESSIONAL,
            WritingTone.DIPLOMATIC,
            WritingTone.EMPATHETIC,
            WritingTone.COOPERATIVE,
            WritingTone.ASSERTIVE,
        )

        framedTones.forEach { tone ->
            assertNotEquals("$tone must visibly rewrite normal text", proofread,
                LocalWritingAssistant.suggest(source, tone))
        }
    }

    @Test
    fun genericShortenContractsPoliteRequests() {
        val source = "Could you please send the report when you have a chance?"
        val shortened = LocalWritingAssistant.suggest(source, WritingTone.SHORTEN)

        assertEquals("Please send the report when possible.", shortened)
        assertTrue(shortened.length < source.length)
    }

    @Test
    fun politeQuestionClauseIsNotSplitAtHave() {
        val source = "Could you please send the report when you have a chance?"

        assertEquals(source, LocalWritingAssistant.suggest(source, WritingTone.PROOFREAD))
    }

    @Test
    fun shortenProducesFewerWordsWithoutDroppingTheCoreAction() {
        val source = "It is important to note that at this point in time we need to act in order to finish."
        val proofread = LocalWritingAssistant.suggest(source, WritingTone.PROOFREAD)
        val shortened = LocalWritingAssistant.suggest(source, WritingTone.SHORTEN)

        assertTrue(shortened.split(Regex("\\s+")).size < proofread.split(Regex("\\s+")).size)
        assertTrue(shortened.contains("act", ignoreCase = true))
        assertTrue(shortened.contains("finish", ignoreCase = true))
    }

    @Test
    fun everyToneRunsAfterSpellingAndGrammarCleanup() {
        WritingTone.entries.forEach { tone ->
            val result = LocalWritingAssistant.suggest(
                "i dont recieve teh report because this are seperate problem",
                tone,
            )

            assertFalse("$tone left a spelling error", result.contains("recieve", ignoreCase = true))
            assertFalse("$tone left a spelling error", result.contains("teh", ignoreCase = true))
            assertFalse("$tone left a spelling error", result.contains("seperate", ignoreCase = true))
        }
    }
}
