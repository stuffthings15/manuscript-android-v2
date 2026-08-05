package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class EssayThreeQualityRegressionTest {
    @Test
    fun timeFramedPossibleReactionRepeatsTheOriginalModal() {
        val cases = listOf(
            "One afternoon a vehicle might stop outside and the animal reacts in its own way." to
                "One afternoon a vehicle might stop outside and the animal might react in its own way.",
            "One morning, a visitor could arrive nearby and the dog responds with a bark." to
                "One morning, a visitor could arrive nearby and the dog could respond with a bark.",
        )

        cases.forEach { (source, expected) ->
            assertEquals(expected, GrammarSafetyNet.repair(source))
        }
    }

    @Test
    fun independentPresentDutyDoesNotBecomeConditional() {
        val cases = listOf(
            "The office might be there, and the guard watches the door.",
            "One afternoon a visitor might wait nearby and the receptionist responds to emails.",
            "One evening a delivery could arrive nearby and the clerk responds with a report.",
        )

        cases.forEach { source ->
            assertEquals(source, GrammarSafetyNet.repair(source))
        }
    }

    @Test
    fun timeFramedSameSubjectReactionRepeatsTheOriginalModal() {
        val cases = listOf(
            "One afternoon a visitor might wait nearby and the visitor responds when called." to
                "One afternoon a visitor might wait nearby and the visitor might respond when called.",
            "One night the sensor could detect movement and the sensor reacts by sounding an alarm." to
                "One night the sensor could detect movement and the sensor could react by sounding an alarm.",
        )

        cases.forEach { (source, expected) ->
            assertEquals(expected, GrammarSafetyNet.repair(source))
        }
    }

    @Test
    fun previouslyUnfamiliarWordsUsePastPerfectOnlyInLearningContext() {
        val cases = listOf(
            "They learn words they would never use before." to
                "They learn words they had never used before.",
            "Students learned new words they would never use before." to
                "Students learned new words they had never used before.",
        )

        cases.forEach { (source, expected) ->
            assertEquals(expected, GrammarSafetyNet.repair(source))
        }
    }

    @Test
    fun policyAndConditionalUsesOfWouldStayModal() {
        val cases = listOf(
            "These are words they would never use before children.",
            "If selected, they would learn words they would never use before children.",
        )

        cases.forEach { source ->
            assertEquals(source, GrammarSafetyNet.repair(source))
        }
    }

    @Test
    fun dependentWhyComplementLosesOnlyItsUnnecessaryComma() {
        val source =
            "The report is about two workers near a station, and why keeping a shared " +
                "calendar could help."
        val expected =
            "The report is about two workers near a station and why keeping a shared " +
                "calendar could help."

        assertEquals(expected, GrammarSafetyNet.repair(source))
    }

    @Test
    fun independentQuestionClauseKeepsItsCoordinationComma() {
        val cases = listOf(
            "The report is about station safety, and whether officials agree is unclear.",
            "The article is about daily care, and how people respond remains unknown.",
        )

        cases.forEach { source ->
            assertEquals(source, GrammarSafetyNet.repair(source))
        }
    }

    @Test
    fun indoorStudyArrangementRetainsDescriptionRoleAndLocation() {
        val cases = listOf(
            "The article explains why keeping one as a calm indoor animal while studying could work." to
                "The article explains why keeping one as a calm animal indoors while studying could work.",
            "They discuss keeping it as a quiet indoor pet while working from home." to
                "They discuss keeping it as a quiet pet indoors while working from home.",
        )

        cases.forEach { (source, expected) ->
            assertEquals(expected, GrammarSafetyNet.repair(source))
        }
    }

    @Test
    fun recurringCareRoutineKeepsTenseNegationAndModality() {
        val cases = listOf(
            "Feeding and cleaning make a repeating schedule." to
                "Feeding and cleaning create a regular routine.",
            "Daily care makes a repeating schedule." to
                "Daily care creates a regular routine.",
            "Those tasks made a repeating schedule." to
                "Those tasks created a regular routine.",
            "Those tasks do not make a repeating schedule." to
                "Those tasks do not create a regular routine.",
            "Those tasks might make a repeating schedule." to
                "Those tasks might create a regular routine.",
        )

        cases.forEach { (source, expected) ->
            assertEquals(expected, GrammarSafetyNet.repair(source))
        }
    }

    @Test
    fun sentenceInitialEvenThoughDropsOnlyRedundantBut() {
        val cases = listOf(
            "Even though the routine changes, but the animal remains calm." to
                "Even though the routine changes, the animal remains calm.",
            "Morgan stayed. Even though it rained, but Morgan left later." to
                "Morgan stayed. Even though it rained, Morgan left later.",
        )

        cases.forEach { (source, expected) ->
            assertEquals(expected, GrammarSafetyNet.repair(source))
        }
    }

    @Test
    fun validMidSentenceEvenThoughCoordinationStaysUnchanged() {
        val source = "She stayed even though it rained, but she left later."

        assertEquals(source, GrammarSafetyNet.repair(source))
    }

    @Test
    fun forgottenKeysRepairKeepsEveryUnaffectedFactAndPetPresence() {
        val source =
            "At 7 in Lisbon, Morgan forgets their keys when the morning gets busy. " +
                "This shows responsibility and also shows the pet is there, " +
                "which reassures two roommates."
        val expected =
            "At 7 in Lisbon, Morgan forgets their keys when the morning gets busy. " +
                "The pet is there, which reassures two roommates."

        assertEquals(expected, GrammarSafetyNet.repair(source))
    }

    @Test
    fun unrelatedPresenceAndResponsibilityClaimsStayUnchanged() {
        val cases = listOf(
            "This shows responsibility and also shows that the nurse is there, which reassures the patient.",
            "This shows responsibility and also shows the pet is there, which reassures Morgan.",
        )

        cases.forEach { source ->
            assertEquals(source, GrammarSafetyNet.repair(source))
        }
    }

    @Test
    fun completedAwkwardUncertaintyPhraseRetainsExactlyModalityAndNegation() {
        val source = "Morgan in Lisbon says 2 imported plans might not work exactly."
        val expected =
            "Morgan in Lisbon says 2 imported plans might not work in exactly the same way."

        assertEquals(expected, GrammarSafetyNet.repair(source))
    }

    @Test
    fun supportedOwnerContinuationBecomesANewSentenceWithoutFactLoss() {
        val source = "A copied plan might not work exactly the owner should check local guidance."
        val expected =
            "A copied plan might not work in exactly the same way. " +
                "The owner should check local guidance."

        assertEquals(expected, GrammarSafetyNet.repair(source))
    }

    @Test
    fun validExactlyComplementsStayUnchanged() {
        val cases = listOf(
            "The device might not work exactly as designed.",
            "The method might not work exactly twice.",
        )

        cases.forEach { source ->
            assertEquals(source, GrammarSafetyNet.repair(source))
        }
    }

    @Test
    fun conclusionCombinesRepetitionWithoutDeletingEitherClaim() {
        val source =
            "This involves many requirements. Most worthwhile things have requirements."
        val expected =
            "This involves many requirements, as worthwhile things often do."

        assertEquals(expected, GrammarSafetyNet.repair(source))
    }
}
