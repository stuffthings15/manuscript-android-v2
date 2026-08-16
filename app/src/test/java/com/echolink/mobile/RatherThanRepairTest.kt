package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "rather then" cost essay 01 paragraph 2 its whole correction.
 *
 * Both spellings are real words the shipped dictionary knows, and neither is a
 * function word, so when the model corrected it, ProofreadPreservationPolicy
 * read it as a content-word swap and refused the paragraph, which then fell to
 * the sentence-salvage path. Measured against the phone's own prepared text and
 * answer, it was the only one of six repairs in that paragraph the policy would
 * not allow. Repairing it deterministically leaves the model nothing to swap.
 */
class RatherThanRepairTest {
    @Test
    fun ratherAndOtherTakeThanNotThen() {
        assertEquals(
            "This essay considers those questions rather than treating it as a trend.",
            GrammarSafetyNet.repair(
                "This essay considers those questions rather then treating it as a trend.",
            ),
        )
        assertEquals(
            "There is no route other than the one they funded.",
            GrammarSafetyNet.repair("There is no route other then the one they funded."),
        )
    }

    @Test
    fun capitalisationIsKept() {
        assertEquals("Rather than wait, they left.", GrammarSafetyNet.repair("Rather then wait, they left."))
    }

    @Test
    fun aTemporalThenAfterAComparativeIsLeftAlone() {
        // The reason this rule is only "rather" and "other". A comparative can
        // legitimately be followed by a sequencing "then", and rewriting that
        // would change what the writer said.
        val sequence = "The hive got better then worse over the summer."
        assertEquals(sequence, GrammarSafetyNet.repair(sequence))
    }
}
