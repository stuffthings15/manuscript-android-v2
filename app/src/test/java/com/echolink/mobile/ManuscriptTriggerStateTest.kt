package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ManuscriptTriggerStateTest {
    @Test
    fun focusedNormalFieldWithTextUsesTheSharedPulse() {
        assertEquals(
            ManuscriptTriggerState.VISIBLE_PULSING,
            ManuscriptTriggerPolicy.state(
                focused = true,
                editable = true,
                hasText = true,
                password = false,
                reviewOpen = false,
                animationsEnabled = true,
            ),
        )
    }

    @Test
    fun reducedMotionKeepsTheTriggerVisibleWithoutAnimation() {
        assertEquals(
            ManuscriptTriggerState.VISIBLE_STATIC_REDUCED_MOTION,
            ManuscriptTriggerPolicy.state(
                focused = true,
                editable = true,
                hasText = true,
                password = false,
                reviewOpen = false,
                animationsEnabled = false,
            ),
        )
    }

    @Test
    fun emptyPasswordUnfocusedAndReviewOpenStatesAreHidden() {
        val baseline = TriggerFacts(
            focused = true,
            editable = true,
            hasText = true,
            password = false,
            reviewOpen = false,
            animationsEnabled = true,
        )
        assertEquals(
            ManuscriptTriggerState.HIDDEN_NO_TEXT,
            ManuscriptTriggerPolicy.state(baseline.copy(hasText = false)),
        )
        assertEquals(
            ManuscriptTriggerState.HIDDEN_PASSWORD,
            ManuscriptTriggerPolicy.state(baseline.copy(password = true)),
        )
        assertEquals(
            ManuscriptTriggerState.HIDDEN_NOT_EDITABLE,
            ManuscriptTriggerPolicy.state(baseline.copy(editable = false)),
        )
        assertEquals(
            ManuscriptTriggerState.HIDDEN_UNFOCUSED,
            ManuscriptTriggerPolicy.state(baseline.copy(focused = false)),
        )
        assertEquals(
            ManuscriptTriggerState.HIDDEN_REVIEW_OPEN,
            ManuscriptTriggerPolicy.state(baseline.copy(reviewOpen = true)),
        )
    }
}
