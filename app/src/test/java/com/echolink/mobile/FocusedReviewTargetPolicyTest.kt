package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedReviewTargetPolicyTest {
    private val retained = ReviewTargetId("retained")
    private val moved = ReviewTargetId("moved")

    @Test
    fun refreshedRetainedNodeIsRejectedAfterFocusMoves() {
        assertFalse(
            FocusedReviewTargetPolicy.accepts(
                isInputFocused = false,
                eligibility = ReviewTargetEligibility.ELIGIBLE,
                actualTargetId = retained,
                expectedTargetId = retained,
            ),
        )
    }

    @Test
    fun onlyCurrentlyFocusedEligibleExpectedNodeIsAccepted() {
        assertTrue(
            FocusedReviewTargetPolicy.accepts(
                isInputFocused = true,
                eligibility = ReviewTargetEligibility.ELIGIBLE,
                actualTargetId = retained,
                expectedTargetId = retained,
            ),
        )
        assertFalse(
            FocusedReviewTargetPolicy.accepts(
                isInputFocused = true,
                eligibility = ReviewTargetEligibility.ELIGIBLE,
                actualTargetId = moved,
                expectedTargetId = retained,
            ),
        )
        assertTrue(
            FocusedReviewTargetPolicy.accepts(
                isInputFocused = true,
                eligibility = ReviewTargetEligibility.ELIGIBLE,
                actualTargetId = moved,
                expectedTargetId = null,
            ),
        )
    }
}
