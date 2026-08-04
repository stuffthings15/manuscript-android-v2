package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the 2026-08-01 acceptance-gate repair: correct spelling, contraction,
 * punctuation, and number-style repairs must pass the safety validator, while
 * genuine meaning changes stay blocked. Mirrors the Windows polish gate fix.
 */
class CorrectionGateReliabilityTest {

    private fun accepts(original: String, candidate: String): Boolean =
        AiSafetyValidator.validate(
            original = original,
            candidate = candidate,
            toneTransformation = false,
            requiresVisibleChange = false,
        ).safe

    @Test
    fun contractionRepairsAreNotMeaningChanges() {
        assertTrue(
            accepts(
                "i cant beleive the report was submited late agian.",
                "I can't believe the report was submitted late again.",
            ),
        )
        assertTrue(
            accepts(
                "we dont no why the server keeps crashin every night.",
                "We don't know why the server keeps crashing every night.",
            ),
        )
        assertTrue(
            accepts(
                "youre going to need too check youre email before the meeting.",
                "You're going to need to check your email before the meeting.",
            ),
        )
        assertTrue(
            accepts(
                "All the manager's agreed the report's format was'nt clear.",
                "All the managers agreed the report's format wasn't clear.",
            ),
        )
        assertTrue(
            accepts(
                "You must of forgot to save the file before you closed it.",
                "You must have forgotten to save the file before you closed it.",
            ),
        )
    }

    @Test
    fun doubleNegativeRepairIsAllowed() {
        assertTrue(
            accepts(
                "He dont judge nobody who arrives late to the session.",
                "He doesn't judge anyone who arrives late to the session.",
            ),
        )
    }

    @Test
    fun negativePronounSpellingRepairIsAllowed() {
        assertTrue(
            accepts(
                "no body told me the deadline had moved to friday.",
                "Nobody told me the deadline had moved to Friday.",
            ),
        )
    }

    @Test
    fun writtenAndNumericNumbersCompareEqual() {
        assertTrue(
            accepts(
                "there is 3 errors left in the draft.",
                "There are three errors left in the draft.",
            ),
        )
    }

    @Test
    fun redundantModalCleanupIsAdvisoryNotFatal() {
        assertTrue(
            accepts(
                "You can be able to submit the form after you can finish the review.",
                "You can submit the form after you finish the review.",
            ),
        )
    }

    @Test
    fun realMeaningChangesStayBlocked() {
        assertFalse(
            "negation flip must stay blocked",
            accepts(
                "The vendor did not approve the budget on Monday.",
                "The vendor approved the budget on Monday.",
            ),
        )
        assertFalse(
            "numeric value change must stay blocked",
            accepts(
                "We shipped 42 units to the warehouse last night.",
                "We shipped 24 units to the warehouse last night.",
            ),
        )
        assertFalse(
            "clock-time change must stay blocked",
            accepts(
                "The meeting starts at 3pm in the main hall.",
                "The meeting starts at 5pm in the main hall.",
            ),
        )
        assertFalse(
            "dropped obligation must stay blocked",
            accepts(
                "Every technician must sign the safety sheet before entry.",
                "Every technician signs the safety sheet before entry.",
            ),
        )
        assertFalse(
            "actor swap must stay blocked",
            accepts(
                "Sarah approved the release after the review.",
                "Mark approved the release after the review.",
            ),
        )
        assertFalse(
            "dropped clause must stay blocked in proofread",
            accepts(
                "The report is complete, but the appendix is still missing pages.",
                "The report is complete.",
            ),
        )
        assertFalse(
            "permission flip must stay blocked",
            accepts(
                "Contractors may not enter the server room without an escort.",
                "Contractors may enter the server room without an escort.",
            ),
        )
    }

    @Test
    fun negativePronounToNoOneRepairIsAllowed() {
        assertTrue(
            accepts(
                "Because nobody could confirm the duplicate charge they agreed to wait.",
                "Because no one could confirm the duplicate charge, they agreed to wait.",
            ),
        )
    }

    @Test
    fun missingInfinitiveObligationRepairIsAllowed() {
        assertTrue(
            accepts(
                "Every volunteer must pass a background check, emergency exits needs remain clear.",
                "Every volunteer must pass a background check, and emergency exits need to remain clear.",
            ),
        )
    }

    @Test
    fun possessiveRepairIsNotAnEntitySwap() {
        assertTrue(
            accepts(
                "Next the board discussed Saturdays author event with Tomas.",
                "Next, the board discussed Saturday's author event with Tomas.",
            ),
        )
    }

    @Test
    fun meridiemStyleRepairIsNotANumberChange() {
        assertTrue(
            accepts(
                "the meeting were suppose to start at 9am on monday.",
                "The meeting was supposed to start at 9 a.m. on Monday.",
            ),
        )
        assertFalse(
            "changed meridiem hour must stay blocked",
            accepts(
                "The call starts at 9am tomorrow.",
                "The call starts at 8 a.m. tomorrow.",
            ),
        )
    }

    @Test
    fun hyphenationRepairIsNotAnEntitySwap() {
        assertTrue(
            accepts(
                "project northstar: Five Page Field Readyness Brief",
                "Project Northstar: Five-Page Field Readiness Brief",
            ),
        )
    }

    @Test
    fun capitalizedSpellingRepairIsNotAnEntitySwap() {
        assertTrue(
            accepts(
                "The Readyness review happens in Febuary at the Harborview center.",
                "The Readiness review happens in February at the Harborview center.",
            ),
        )
    }

    @Test
    fun shortNameSwapsAreStillBlocked() {
        assertFalse(
            accepts(
                "Sam approved the purchase order yesterday.",
                "Max approved the purchase order yesterday.",
            ),
        )
    }

    @Test
    fun capitalizationRepairIsNotAnEntitySwap() {
        assertTrue(
            accepts(
                "on monday i met sarah in san diego to discuss the budget.",
                "On Monday I met Sarah in San Diego to discuss the budget.",
            ),
        )
    }

    @Test
    fun properNounReduplicationIsNotARepeatedWordError() {
        val text = "We flew to Bora Bora and then to Walla Walla for the conference."
        assertTrue(GrammarQualityGate.errorScore(text) == 0)
    }

    @Test
    fun lowercaseRepeatedWordsAreStillErrors() {
        assertTrue(
            GrammarQualityGate.errorScore(
                "We reviewed the the report before lunch.",
            ) > 0,
        )
    }
}
