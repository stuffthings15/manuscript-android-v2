package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewTargetLifecyclePolicyTest {
    @Test
    fun verifiedApplicationSwitchPasswordAndDetachInvalidateImmediately() {
        val cases = mapOf(
            RetainedTargetEvidence.VERIFIED_APPLICATION_SWITCH to
                ReviewTargetLifecycleAction.APPLICATION_SWITCHED,
            RetainedTargetEvidence.VERIFIED_PASSWORD_FOCUS to
                ReviewTargetLifecycleAction.PASSWORD_FOCUSED,
            RetainedTargetEvidence.VERIFIED_EXACT_TARGET_DETACHED to
                ReviewTargetLifecycleAction.TARGET_LOST,
        )

        cases.forEach { (evidence, expected) ->
            assertEquals(expected, ReviewTargetLifecyclePolicy.action(evidence))
        }
    }

    @Test
    fun eligibleFocusLossUsesTheTransientGraceButUnverifiedNoiseDoesNothing() {
        assertEquals(
            ReviewTargetLifecycleAction.ADOPT_VERIFIED_TARGET,
            ReviewTargetLifecyclePolicy.action(
                RetainedTargetEvidence.VERIFIED_DIFFERENT_ELIGIBLE_TARGET,
            ),
        )
        assertEquals(
            ReviewTargetLifecycleAction.DEFER_TARGET_LOSS,
            ReviewTargetLifecyclePolicy.action(RetainedTargetEvidence.VERIFIED_ELIGIBLE_FOCUS_LOST),
        )
        assertEquals(
            ReviewTargetLifecycleAction.KEEP,
            ReviewTargetLifecyclePolicy.action(RetainedTargetEvidence.VERIFIED_EXACT_TARGET_ELIGIBLE),
        )
        assertEquals(
            ReviewTargetLifecycleAction.KEEP,
            ReviewTargetLifecyclePolicy.action(RetainedTargetEvidence.UNVERIFIED_EVENT),
        )
    }
}
