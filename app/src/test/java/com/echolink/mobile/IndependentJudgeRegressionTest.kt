package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generic regressions distilled from two independent quality judges. */
class IndependentJudgeRegressionTest {
    private val knownDictionary: (String) -> Boolean = { word ->
        word.lowercase() in setOf(
            "after", "badge", "carefully", "checked", "desk", "entrance", "every",
            "garden", "guard", "guarding", "invoice", "lunch", "on", "the", "there",
            "theirs", "was", "west",
        )
    }

    @Test
    fun proofreadRejectsRealWordActionSwapEvenWhenOtherWordsMove() {
        val source = "The guard was guarding the west entrance and checked every badge."
        val candidate =
            "After lunch, the guard was garden the west entrance and carefully checked every badge."

        assertFalse(
            ProofreadPreservationPolicy.preservesWording(source, candidate, knownDictionary),
        )
    }

    @Test
    fun proofreadRejectsDeicticToPossessiveMeaningSwap() {
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "The invoice is there on the desk.",
                "The invoice is theirs on the desk.",
                knownDictionary,
            ),
        )
    }

    @Test
    fun proofreadAllowsSafeOneWordToTwoWordSpacingRepair() {
        val known = setOf("day", "every", "everyday", "i", "walk")
        assertTrue(
            ProofreadPreservationPolicy.preservesWording(
                "I walk everyday.",
                "I walk every day.",
                { it.lowercase() in known },
            ),
        )
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "Everyday routines change; every day routines improve.",
                "Ordinary daily routines change; every day routines improve.",
                { true },
            ),
        )
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "Sometimes I walk.",
                "Some times I walk.",
                { true },
            ),
        )
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "I walked into town.",
                "I walked in to town.",
                { true },
            ),
        )
    }

    @Test
    fun proofreadPreservationRejectsContentSubstitutionsInsideBroaderEdits() {
        val words = setOf(
            "before", "cabinet", "carefully", "central", "courier", "crowded", "each",
            "every", "food", "fuel", "industrial", "inside", "morning", "night", "patiently",
            "plaza", "quiet", "shift", "stores", "technician", "the", "waits",
        )
        val knows: (String) -> Boolean = { it.lowercase() in words }
        val materialSwap = ProofreadPreservationPolicy.preservesWording(
            "The technician stores food in the cabinet before each night shift.",
            "Before each night shift, the technician carefully stores fuel inside the cabinet.",
            knows,
        )
        val descriptorSwap = ProofreadPreservationPolicy.preservesWording(
            "The courier waits beside the quiet industrial plaza every morning.",
            "Every morning, the courier patiently waits beside the crowded central plaza.",
            knows,
        )

        assertFalse(materialSwap)
        assertFalse(descriptorSwap)
    }

    @Test
    fun proofreadPreservationAllowsContextuallyRequiredKnownWordRepair() {
        val knows: (String) -> Boolean = {
            it.lowercase() in setOf("fewer", "information", "less", "provided", "was")
        }

        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "Fewer information was provided.",
                "Less information was provided.",
                knows,
            ),
        )
        val furnitureWords: (String) -> Boolean = {
            it.lowercase() in setOf("fewer", "furniture", "less", "needed", "was")
        }
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "Fewer furniture was needed.",
                "Less furniture was needed.",
                furnitureWords,
            ),
        )
    }

    @Test
    fun proofreadPreservationRejectsDictionaryBackedProperNounCaseLoss() {
        val knownCommonWords = setOf("handles", "office", "request", "residents")
        val preserves = ProofreadPreservationPolicy.preservesWording(
            "The office in Meridian handles the request.",
            "The office in meridian handles the request.",
            { word -> word.lowercase() in knownCommonWords },
        )

        assertFalse(preserves)
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "I met Rose yesterday.",
                "I met rose yesterday.",
                { true },
            ),
        )
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "I emailed Rose.",
                "I emailed rose.",
                { true },
            ),
        )
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "I spoke with Rose yesterday.",
                "I spoke with rose yesterday.",
                { true },
            ),
        )
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "The office in Bath handles requests.",
                "The office in bath handles requests.",
                { true },
            ),
        )
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "The office in Bath was closed.",
                "The office in bath was closed.",
                { true },
            ),
        )
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "The office in Bath opened yesterday.",
                "The office in bath opened yesterday.",
                { true },
            ),
        )
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "The office in Bath stood empty.",
                "The office in bath stood empty.",
                { true },
            ),
        )
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "The offices in Bath open daily.",
                "The offices in bath open daily.",
                { true },
            ),
        )
        listOf("companies", "agencies", "branches").forEach { host ->
            assertFalse(
                ProofreadPreservationPolicy.preservesWording(
                    "The $host in Bath open daily.",
                    "The $host in bath open daily.",
                    { true },
                ),
            )
        }
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "Rose handles the request.",
                "rose handles the request.",
                { true },
            ),
        )
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "I emailed Rose about the rose garden.",
                "I emailed rose about the Rose garden.",
                { true },
            ),
        )
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "Bath is quiet.",
                "bath is quiet.",
                { true },
            ),
        )
        listOf("Rose went home." to "rose went home.", "Rose wrote yesterday." to "rose wrote yesterday.")
            .forEach { (source, candidate) ->
                assertFalse(
                    ProofreadPreservationPolicy.preservesWording(source, candidate, { true }),
                )
            }
        assertTrue(
            ProofreadPreservationPolicy.preservesWording(
                "The proposal met Final review requirements.",
                "The proposal met final review requirements.",
                { true },
            ),
        )
    }

    @Test
    fun safetyValidatorAllowsInternalCommonWordDecapitalization() {
        assertTrue(
            AiSafetyValidator.validate(
                "The report Has issues.",
                "The report has issues.",
            ).safe,
        )
        assertTrue(
            AiSafetyValidator.validate(
                "The notice was sent by Email today.",
                "The notice was sent by email today.",
            ).safe,
        )
        listOf(
            "The alarm sounded at Noon." to "The alarm sounded at noon.",
            "The mistake was called Serious." to "The mistake was called serious.",
            "The message came from Home." to "The message came from home.",
            "The report remains in Draft." to "The report remains in draft.",
            "The requirements were met Today." to "The requirements were met today.",
            "The field was named Incorrectly." to "The field was named incorrectly.",
            "The work is near Completion." to "The work is near completion.",
            "The status changed to Final." to "The status changed to final.",
        ).forEach { (source, candidate) ->
            assertTrue("[$source] should allow [$candidate]", AiSafetyValidator.validate(source, candidate).safe)
        }
        assertTrue(
            AiSafetyValidator.validate(
                "Email appears in the heading, and Email appears in the label.",
                "email appears in the heading, and email appears in the label.",
            ).safe,
        )
    }

    @Test
    fun negativePronounNormalizationDoesNotChangeNumericNoOnePhrase() {
        assertTrue(
            AiSafetyValidator.validate(
                "No one reason explains the delay.",
                "No one reason explains the delay.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "No one reason explains the delay.",
                "Nobody reason explains the delay.",
            ).safe,
        )
        assertTrue(
            AiSafetyValidator.validate(
                "No one attended the review.",
                "Nobody attended the review.",
            ).safe,
        )
        assertTrue(
            AiSafetyValidator.validate(
                "No one knows the answer.",
                "Nobody knows the answer.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "No one red light was visible.",
                "Nobody red light was visible.",
            ).safe,
        )
        assertTrue(
            AiSafetyValidator.validate(
                "No one reviewed the file.",
                "Nobody reviewed the file.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "No one went home.",
                "Nobody went home.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "No one tired employee answered.",
                "Nobody tired employee answered.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "No one left shoe was found.",
                "Nobody left shoe was found.",
            ).safe,
        )
        assertTrue(
            AiSafetyValidator.validate(
                "No one sleeps.",
                "Nobody sleeps.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "No one business in town was open.",
                "Nobody business in town was open.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "No one serious issue was reported.",
                "Nobody serious issue was reported.",
            ).safe,
        )
        assertTrue(
            AiSafetyValidator.validate(
                "No one understands.",
                "Nobody understands.",
            ).safe,
        )
    }

    @Test
    fun safetyValidatorAllowsMeaningPreservingCountabilityRepairEndToEnd() {
        val furnitureValidation = AiSafetyValidator.validate(
            "Fewer furniture was needed.",
            "Less furniture was needed.",
        )
        assertFalse(furnitureValidation.safe)
        assertTrue(
            AiSafetyValidator.validate(
                "Fewer furniture was needed.",
                "Less furniture was needed.",
                trustedDeterministicTarget = "Less furniture was needed.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "Fewer chairs arrived.",
                "Less chairs arrived.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "Fewer children arrived.",
                "Less children arrived.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "Fewer research papers were filed.",
                "Less research papers were filed.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "Fewer species were observed.",
                "Less species were observed.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "Fewer physics was required.",
                "Less physics was required.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "Less chairs were available.",
                "Fewer chairs were available.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "Less physics was required.",
                "Fewer physics was required.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "Less serious work was required.",
                "Fewer serious work was required.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "Fewer physics courses were required.",
                "Less physics courses were required.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "Fewer highly detailed scientific research papers were filed.",
                "Less highly detailed scientific research papers were filed.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "The amount of physics was too many.",
                "The number of physics was too many.",
            ).safe,
        )
        assertFalse(
            AiSafetyValidator.validate(
                "The amount of analysis was sufficient.",
                "The number of analysis was sufficient.",
            ).safe,
        )
    }

    @Test
    fun grammarGateKeepsValidNoOneAndRepairsOnlyCertainCountabilityErrors() {
        assertEquals("No one sleeps.", GrammarSafetyNet.repair("No one sleeps."))
        assertEquals("No one understands.", GrammarSafetyNet.repair("No one understands."))
        assertEquals(
            "Less information was provided.",
            GrammarSafetyNet.repair("Fewer information was provided."),
        )
        assertEquals(
            "Fewer physics courses were required.",
            GrammarSafetyNet.repair("Fewer physics courses were required."),
        )
        assertEquals(
            "The amount of analysis was sufficient.",
            GrammarSafetyNet.repair("The amount of analysis was sufficient."),
        )
        assertEquals(
            "The amount of business was too large.",
            GrammarSafetyNet.repair("The amount of business was too many."),
        )
        assertEquals(
            "The amount of gas was sufficient.",
            GrammarSafetyNet.repair("The amount of gas was sufficient."),
        )
        assertEquals(
            "The amount of gas was too large.",
            GrammarSafetyNet.repair("The amount of gas was too many."),
        )
        assertEquals(
            "The amount of status was too large.",
            GrammarSafetyNet.repair("The amount of status was too many."),
        )
        assertEquals(
            "The amount of status information was sufficient.",
            GrammarSafetyNet.repair("The amount of status information was sufficient."),
        )
    }

    @Test
    fun grammarSafetyNetRepairsGenericAgreementCoordinationAndSpacing() {
        assertEquals(
            "There are several benefits to the change.",
            GrammarSafetyNet.repair("There is several benefits to the change."),
        )
        assertEquals(
            "Planning, reviewing, and publishing create a reliable routine.",
            GrammarSafetyNet.repair("Planning, reviewing and publishing creates a reliable routine."),
        )
        assertEquals(
            "The checks create a reliable routine.",
            GrammarSafetyNet.repair("The checks make create a reliable routine."),
        )
        assertEquals(
            "After a while, the values change.",
            GrammarSafetyNet.repair("After awhile the values changes."),
        )
        val validWit = "Use wit the way the speaker does."
        assertEquals(validWit, GrammarSafetyNet.repair(validWit))
    }

    @Test
    fun grammarSafetyNetLeavesValidVerbObjectSequenceUnchanged() {
        val valid = "The audit records changes every week."

        assertEquals(valid, GrammarSafetyNet.repair(valid))
        val diagnosis = "There is multiple sclerosis in the differential diagnosis."
        assertEquals(diagnosis, GrammarSafetyNet.repair(diagnosis))
        val embedded = "I think there is several reasons to wait."
        assertEquals("I think there are several reasons to wait.", GrammarSafetyNet.repair(embedded))
        val singularSubject = "The process of planning, reviewing and publishing creates value."
        assertTrue(GrammarSafetyNet.repair(singularSubject).endsWith("publishing creates value."))
        val labeledAction = "The shortcut makes Create available."
        assertEquals(labeledAction, GrammarSafetyNet.repair(labeledAction))
        val titledName = "Wit the dog waited near the gate."
        assertEquals(titledName, GrammarSafetyNet.repair(titledName))
        assertEquals(
            "This process creates a routine.",
            GrammarSafetyNet.repair("This process makes create a routine."),
        )
        val actor = "The policy forces the one to learn."
        assertEquals(actor, GrammarSafetyNet.repair(actor))
    }

    @Test
    fun generatedGrammarGateRejectsMalformedCoordinationAndEmbeddedQuestions() {
        assertTrue(
            GrammarQualityGate.errorScore(
                "The package is light and but durable.",
            ) > 0,
        )
        assertTrue(
            GrammarQualityGate.errorScore(
                "Some clients ask why does the form look different.",
            ) > 0,
        )
    }

    @Test
    fun generatedGrammarGateAllowsCleanModifierAndCoordination() {
        assertEquals(
            0,
            GrammarQualityGate.errorScore(
                "The report includes detailed analysis and recommendations.",
            ),
        )
        assertEquals(
            0,
            GrammarQualityGate.errorScore(
                "The policy is strict but fair.",
            ),
        )
        assertEquals(
            0,
            GrammarQualityGate.errorScore(
                "The review includes systems analysis and operations research.",
            ),
        )
    }
}
