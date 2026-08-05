package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManuscriptTriggerStateTest {
    @Test
    fun accessibilityPublicationRequiresAttachedVisibleVerifiedTrigger() {
        val visibleStates = listOf(
            ManuscriptTriggerState.VISIBLE_PULSING,
            ManuscriptTriggerState.VISIBLE_STATIC_REDUCED_MOTION,
        )
        visibleStates.forEach { state ->
            assertTrue(
                ManuscriptTriggerPolicy.shouldPublishAccessibilitySurface(
                    attached = true,
                    state = state,
                    verifiedEligibleTarget = true,
                ),
            )
        }
        ManuscriptTriggerState.entries
            .filterNot { it in visibleStates }
            .forEach { state ->
                assertFalse(
                    ManuscriptTriggerPolicy.shouldPublishAccessibilitySurface(
                        attached = true,
                        state = state,
                        verifiedEligibleTarget = true,
                    ),
                )
            }
        assertFalse(
            ManuscriptTriggerPolicy.shouldPublishAccessibilitySurface(
                attached = false,
                state = ManuscriptTriggerState.VISIBLE_PULSING,
                verifiedEligibleTarget = true,
            ),
        )
        assertFalse(
            ManuscriptTriggerPolicy.shouldPublishAccessibilitySurface(
                attached = true,
                state = ManuscriptTriggerState.VISIBLE_PULSING,
                verifiedEligibleTarget = false,
            ),
        )
    }

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
