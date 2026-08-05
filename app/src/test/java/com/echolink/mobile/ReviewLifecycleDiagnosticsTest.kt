package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewLifecycleDiagnosticsTest {
    @Test
    fun accessibilityEventIncludesOnlyBoundedVerifiedIdentityMetadata() {
        val diagnostic = ReviewLifecycleDiagnostics.accessibilityEvent(
            eventType = 32,
            eventPackage = "com.example.fixture",
            eventWindowId = 7,
            verifiedPackage = "com.example.fixture",
            verifiedWindowId = 7,
            verified = true,
        )

        assertEquals(
            "kind=accessibility-event;eventType=32;eventPackage=com.example.fixture;" +
                "eventWindow=7;verifiedPackage=com.example.fixture;verifiedWindow=7;verified=true",
            diagnostic,
        )
        assertTrue(diagnostic.length <= ReviewLifecycleDiagnostics.MAX_DIAGNOSTIC_LENGTH)
    }

    @Test
    fun transitionsAndEffectsNeverIncludeOpaqueTargetOrEditorContent() {
        val secretTarget = ReviewTargetId("do-not-log-target-or-editor-content")
        val transition = ReviewLifecycleDiagnostics.transition(
            event = ReviewSurfaceEvent.CloseRequested,
            previousState = ReviewSurfaceState.ReviewOpen(secretTarget),
            nextState = ReviewSurfaceState.Closing(
                ReviewTarget(secretTarget, ReviewTargetEligibility.ELIGIBLE),
            ),
            effects = listOf(
                ReviewSurfaceEffect.CancelStaleCorrectionAndCallbackWork,
                ReviewSurfaceEffect.DetachReviewPanelSafely,
                ReviewSurfaceEffect.ScrubTransientTextHolders,
            ),
        )
        val effect = ReviewLifecycleDiagnostics.effect(
            ReviewSurfaceEffect.DetachReviewPanelSafely,
            ReviewLifecycleEffectOutcome.REMOVED,
        )

        assertEquals(
            "kind=surface-transition;event=CloseRequested;from=ReviewOpen;to=Closing;effects=3",
            transition,
        )
        assertEquals(
            "kind=surface-effect;effect=DetachReviewPanelSafely;outcome=REMOVED",
            effect,
        )
        assertFalse(transition.contains(secretTarget.value))
        assertFalse(effect.contains(secretTarget.value))
    }
}
