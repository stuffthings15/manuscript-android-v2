package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplacementVerificationPolicyTest {
    @Test
    fun initialWriteRouteNeverUsesANonmatchingNode() {
        assertEquals(
            ReplacementInitialRoute.ACCESSIBILITY_NODE,
            ReplacementRequestPolicy.initialRoute(nodeMatchesSource = true, connectionMatchesSource = true),
        )
        assertEquals(
            ReplacementInitialRoute.INPUT_CONNECTION,
            ReplacementRequestPolicy.initialRoute(nodeMatchesSource = false, connectionMatchesSource = true),
        )
        assertEquals(
            ReplacementInitialRoute.REJECT,
            ReplacementRequestPolicy.initialRoute(nodeMatchesSource = false, connectionMatchesSource = false),
        )
    }

    @Test
    fun anyVerifiedEditorCompletesImmediately() {
        assertEquals(
            ReplacementVerificationAction.COMPLETE,
            action(nodeVerified = true),
        )
        assertEquals(
            ReplacementVerificationAction.COMPLETE,
            action(connectionVerified = true),
        )
    }

    @Test
    fun unchangedFreshConnectionGetsOneFallbackAfterInitialWait() {
        assertEquals(
            ReplacementVerificationAction.RETRY,
            action(attempt = 0, freshConnectionMatchesSource = true),
        )
        assertEquals(
            ReplacementVerificationAction.TRY_FRESH_CONNECTION,
            action(attempt = 1, freshConnectionMatchesSource = true),
        )
        assertEquals(
            ReplacementVerificationAction.RETRY,
            action(
                attempt = 2,
                connectionFallbackAttempted = true,
                freshConnectionMatchesSource = true,
            ),
        )
    }

    @Test
    fun staleConnectionIsNeverUsedAsFallback() {
        assertEquals(
            ReplacementVerificationAction.RETRY,
            action(attempt = 1, freshConnectionMatchesSource = false),
        )
    }

    @Test
    fun finalUnverifiedAttemptFails() {
        assertEquals(
            ReplacementVerificationAction.FAIL,
            action(attempt = 7, connectionFallbackAttempted = true),
        )
    }

    @Test
    fun duplicateFieldRankingPrefersMatchingTextThenFocusThenDistance() {
        val matchingText = EditorTargetSelectionPolicy.score(
            textMatches = true,
            focused = false,
            horizontalDistancePx = 500,
            verticalDistancePx = 500,
        )
        val wrongFocusedText = EditorTargetSelectionPolicy.score(
            textMatches = false,
            focused = true,
            horizontalDistancePx = 0,
            verticalDistancePx = 0,
        )
        val focusedMatch = EditorTargetSelectionPolicy.score(true, true, 50, 50)
        val unfocusedMatch = EditorTargetSelectionPolicy.score(true, false, 0, 0)
        val nearFocusedMatch = EditorTargetSelectionPolicy.score(true, true, 10, 10)

        assertTrue(matchingText < wrongFocusedText)
        assertTrue(focusedMatch < unfocusedMatch)
        assertTrue(nearFocusedMatch < focusedMatch)
    }

    private fun action(
        nodeVerified: Boolean = false,
        connectionVerified: Boolean = false,
        attempt: Int = 0,
        connectionFallbackAttempted: Boolean = false,
        freshConnectionMatchesSource: Boolean = false,
    ) = ReplacementVerificationPolicy.nextAction(
        nodeVerified = nodeVerified,
        connectionVerified = connectionVerified,
        attempt = attempt,
        maxAttempts = 8,
        connectionFallbackAttempted = connectionFallbackAttempted,
        freshConnectionMatchesSource = freshConnectionMatchesSource,
    )
}
