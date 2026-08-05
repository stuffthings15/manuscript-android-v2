package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSurfaceEffectExecutorTest {
    @Test
    fun duplicateCloseDetachesScrubsAndCancelsExactlyOnce() {
        val fixture = openFixture()

        fixture.executor.dispatch(ReviewSurfaceEvent.CloseRequested)
        fixture.executor.dispatch(ReviewSurfaceEvent.CloseRequested)

        assertEquals(1, fixture.actions.calls.count { it == "cancel" })
        assertEquals(1, fixture.actions.calls.count { it == "detach" })
        assertEquals(1, fixture.actions.calls.count { it == "scrub" })
    }

    @Test
    fun aStillAttachedPanelRetriesAutomaticallyWithoutASecondUserAction() {
        val fixture = openFixture()
        fixture.actions.detachOutcomes.addLast(ReviewPanelDetachOutcome.STILL_ATTACHED)
        fixture.actions.detachOutcomes.addLast(ReviewPanelDetachOutcome.REMOVED)

        fixture.executor.dispatch(ReviewSurfaceEvent.CloseRequested)

        assertEquals(
            listOf("cancel", "detach", "scrub", "detach", "reacquire:editor", "keyboard:editor"),
            fixture.actions.calls,
        )
        assertEquals(ReviewSurfaceState.TriggerVisible(TARGET_ID), fixture.controller.state)
    }

    @Test
    fun terminalDetachRetainsOwnershipUntilConfirmedThenRestoresAndAllowsFutureTriggers() {
        val fixture = openFixture()
        repeat(3) {
            fixture.actions.detachOutcomes.addLast(ReviewPanelDetachOutcome.STILL_ATTACHED)
        }
        fixture.actions.terminalDetachOutcome = ReviewPanelDetachOutcome.STILL_ATTACHED

        fixture.executor.dispatch(ReviewSurfaceEvent.CloseRequested)

        assertEquals(
            listOf("cancel", "detach", "scrub", "detach", "detach", "terminal-detach"),
            fixture.actions.calls,
        )
        assertTrue(fixture.actions.ownsAttachedSurface)
        assertTrue(fixture.controller.state is ReviewSurfaceState.Closing)

        fixture.actions.confirmTerminalDetach()

        assertFalse(fixture.actions.ownsAttachedSurface)
        assertEquals(ReviewSurfaceState.TriggerVisible(TARGET_ID), fixture.controller.state)

        fixture.executor.dispatch(ReviewSurfaceEvent.OpenRequested)
        assertTrue(fixture.controller.state is ReviewSurfaceState.Opening)
    }

    @Test
    fun invalidationRetriesAStillAttachedPanelWithoutRestoringTheTarget() {
        val fixture = openFixture()
        fixture.actions.detachOutcomes.addLast(ReviewPanelDetachOutcome.STILL_ATTACHED)
        fixture.actions.detachOutcomes.addLast(ReviewPanelDetachOutcome.REMOVED)

        fixture.executor.dispatch(ReviewSurfaceEvent.TargetLost)
        fixture.executor.dispatch(ReviewSurfaceEvent.PasswordFocused)

        assertEquals(listOf("cancel", "detach", "scrub", "detach"), fixture.actions.calls)
        assertEquals(ReviewSurfaceState.Hidden, fixture.controller.state)
    }

    @Test
    fun closeInvalidatesEveryPreviouslyIssuedCallbackToken() {
        val fixture = openFixture()
        val oldToken = fixture.executor.currentCallbackToken()

        fixture.executor.dispatch(ReviewSurfaceEvent.CloseRequested)

        assertFalse(fixture.executor.isCurrentCallback(oldToken))
        assertTrue(fixture.executor.isCurrentCallback(fixture.executor.currentCallbackToken()))
    }

    @Test
    fun passwordWhileOpenClosesImmediatelyWithoutRestoringKeyboardOrTrigger() {
        val fixture = openFixture()

        fixture.executor.dispatch(ReviewSurfaceEvent.PasswordFocused)

        assertEquals(listOf("cancel", "detach", "scrub"), fixture.actions.calls)
        assertEquals(ReviewSurfaceState.Hidden, fixture.controller.state)
    }

    @Test
    fun targetLossWhileOpenClosesImmediatelyWithoutRestoration() {
        val fixture = openFixture()

        fixture.executor.dispatch(ReviewSurfaceEvent.TargetLost)

        assertEquals(listOf("cancel", "detach", "scrub"), fixture.actions.calls)
        assertEquals(ReviewSurfaceState.Hidden, fixture.controller.state)
    }

    @Test
    fun packageSwitchWhileOpenClosesImmediatelyWithoutRestoration() {
        val fixture = openFixture()

        fixture.executor.dispatch(ReviewSurfaceEvent.ApplicationSwitched)

        assertEquals(listOf("cancel", "detach", "scrub"), fixture.actions.calls)
        assertEquals(ReviewSurfaceState.Hidden, fixture.controller.state)
    }

    @Test
    fun normalCloseReacquiresBeforeKeyboardRestoreAndShowsOneTrigger() {
        val fixture = openFixture()

        fixture.executor.dispatch(ReviewSurfaceEvent.CloseRequested)

        assertEquals(
            listOf("cancel", "detach", "scrub", "reacquire:editor", "keyboard:editor"),
            fixture.actions.calls,
        )
        assertEquals(ReviewSurfaceState.TriggerVisible(TARGET_ID), fixture.controller.state)
    }

    @Test
    fun sequentialReconnectsRevalidateTheOneEligibleFieldAndKeepOneTrigger() {
        val fixture = hiddenFixture()

        fixture.executor.dispatch(ReviewSurfaceEvent.ServiceReconnected)
        fixture.executor.dispatch(ReviewSurfaceEvent.ServiceReconnected)

        assertEquals(listOf("reacquire:any", "reacquire:any"), fixture.actions.calls)
        assertEquals(ReviewSurfaceState.TriggerVisible(TARGET_ID), fixture.controller.state)
    }

    @Test
    fun reconnectWhileTriggerVisibleRevalidatesInsteadOfStrandingControllerState() {
        val fixture = hiddenFixture()
        fixture.executor.dispatch(
            ReviewSurfaceEvent.FocusedTargetAvailable(
                ReviewTarget(TARGET_ID, ReviewTargetEligibility.ELIGIBLE),
            ),
        )
        fixture.actions.calls.clear()

        fixture.executor.dispatch(ReviewSurfaceEvent.ServiceReconnected)

        assertEquals(listOf("reacquire:any"), fixture.actions.calls)
        assertEquals(ReviewSurfaceState.TriggerVisible(TARGET_ID), fixture.controller.state)
    }

    @Test
    fun reconnectWhileTriggerVisibleAppliesSameNewAndIneligibleTargets() {
        val newTargetId = ReviewTargetId("new-editor")
        val cases = listOf(
            ReviewTarget(TARGET_ID, ReviewTargetEligibility.ELIGIBLE) to
                ReviewSurfaceState.TriggerVisible(TARGET_ID),
            ReviewTarget(newTargetId, ReviewTargetEligibility.ELIGIBLE) to
                ReviewSurfaceState.TriggerVisible(newTargetId),
            ReviewTarget(null, ReviewTargetEligibility.INELIGIBLE) to ReviewSurfaceState.Hidden,
            ReviewTarget(null, ReviewTargetEligibility.MISSING) to ReviewSurfaceState.Hidden,
            ReviewTarget(null, ReviewTargetEligibility.PASSWORD) to ReviewSurfaceState.Hidden,
        )

        cases.forEach { (validation, expectedState) ->
            val fixture = hiddenFixture()
            fixture.executor.dispatch(
                ReviewSurfaceEvent.FocusedTargetAvailable(
                    ReviewTarget(TARGET_ID, ReviewTargetEligibility.ELIGIBLE),
                ),
            )
            fixture.actions.calls.clear()
            fixture.actions.reacquired = validation

            fixture.executor.dispatch(ReviewSurfaceEvent.ServiceReconnected)

            assertEquals("validation=$validation", listOf("reacquire:any"), fixture.actions.calls)
            assertEquals("validation=$validation", expectedState, fixture.controller.state)
        }
    }

    @Test
    fun reconnectCanRetargetButOrdinaryCloseRejectsDifferentFocusedTarget() {
        val newTargetId = ReviewTargetId("other-package-editor")
        val reconnect = hiddenFixture()
        reconnect.executor.dispatch(
            ReviewSurfaceEvent.FocusedTargetAvailable(
                ReviewTarget(TARGET_ID, ReviewTargetEligibility.ELIGIBLE),
            ),
        )
        reconnect.actions.calls.clear()
        reconnect.actions.reacquired = ReviewTarget(newTargetId, ReviewTargetEligibility.ELIGIBLE)

        reconnect.executor.dispatch(ReviewSurfaceEvent.ServiceReconnected)

        assertEquals(ReviewSurfaceState.TriggerVisible(newTargetId), reconnect.controller.state)
        assertEquals(listOf("reacquire:any"), reconnect.actions.calls)

        val close = openFixture()
        close.actions.reacquired = ReviewTarget(newTargetId, ReviewTargetEligibility.ELIGIBLE)
        close.executor.dispatch(ReviewSurfaceEvent.CloseRequested)

        assertEquals(ReviewSurfaceState.Hidden, close.controller.state)
        assertEquals(listOf("cancel", "detach", "scrub", "reacquire:editor"), close.actions.calls)
    }

    @Test
    fun reconnectWhileReviewOpenScrubsAndClosesBeforeAnyAdoption() {
        val fixture = openFixture()
        fixture.actions.reacquired = ReviewTarget(
            ReviewTargetId("new-package-field"),
            ReviewTargetEligibility.ELIGIBLE,
        )

        fixture.executor.dispatch(ReviewSurfaceEvent.ServiceReconnected)

        assertEquals(listOf("cancel", "detach", "scrub"), fixture.actions.calls)
        assertEquals(ReviewSurfaceState.Hidden, fixture.controller.state)
    }

    @Test
    fun closeScrubsBeforeAnyRestorationWork() {
        val fixture = openFixture()

        fixture.executor.dispatch(ReviewSurfaceEvent.CloseRequested)

        assertTrue(fixture.actions.calls.indexOf("scrub") < fixture.actions.calls.indexOf("reacquire:editor"))
    }

    @Test
    fun closeRecordsContentFreeTransitionsAndEffectOutcomes() {
        val fixture = openFixture()

        fixture.executor.dispatch(ReviewSurfaceEvent.CloseRequested)

        assertTrue(
            fixture.actions.diagnostics.contains(
                "kind=surface-transition;event=CloseRequested;from=ReviewOpen;to=Closing;effects=3",
            ),
        )
        assertTrue(
            fixture.actions.diagnostics.contains(
                "kind=surface-effect;effect=DetachReviewPanelSafely;outcome=REMOVED",
            ),
        )
        assertTrue(
            fixture.actions.diagnostics.contains(
                "kind=surface-effect;effect=RestoreKeyboard;outcome=COMPLETED",
            ),
        )
        assertFalse(fixture.actions.diagnostics.any { it.contains(TARGET_ID.value) })
    }

    @Test
    fun destroyWhileOpenCleansOnceAndRejectsLateWork() {
        val fixture = openFixture()
        val oldToken = fixture.executor.currentCallbackToken()

        fixture.executor.dispatch(ReviewSurfaceEvent.Destroy)
        fixture.executor.dispatch(ReviewSurfaceEvent.Destroy)
        fixture.executor.dispatch(ReviewSurfaceEvent.CloseRequested)

        assertEquals(listOf("cancel", "detach", "scrub"), fixture.actions.calls)
        assertEquals(ReviewSurfaceState.Destroyed, fixture.controller.state)
        assertFalse(fixture.executor.isCurrentCallback(oldToken))
    }

    private fun openFixture(): Fixture = hiddenFixture().also { fixture ->
        fixture.executor.dispatch(
            ReviewSurfaceEvent.FocusedTargetAvailable(
                ReviewTarget(TARGET_ID, ReviewTargetEligibility.ELIGIBLE),
            ),
        )
        fixture.executor.dispatch(ReviewSurfaceEvent.OpenRequested)
        fixture.executor.dispatch(ReviewSurfaceEvent.ReviewAttached)
        fixture.actions.calls.clear()
        fixture.actions.diagnostics.clear()
    }

    private fun hiddenFixture(): Fixture {
        val controller = ReviewSurfaceController()
        val actions = RecordingActions()
        return Fixture(controller, actions, ReviewSurfaceEffectExecutor(controller, actions))
    }

    private data class Fixture(
        val controller: ReviewSurfaceController,
        val actions: RecordingActions,
        val executor: ReviewSurfaceEffectExecutor,
    )

    private class RecordingActions : ReviewSurfaceEffectActions {
        val calls = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val detachOutcomes = ArrayDeque<ReviewPanelDetachOutcome>()
        var reacquired = ReviewTarget(TARGET_ID, ReviewTargetEligibility.ELIGIBLE)
        var terminalDetachOutcome = ReviewPanelDetachOutcome.REMOVED
        var ownsAttachedSurface = true
        private var terminalDetachConfirmation: (() -> Unit)? = null

        override fun cancelStaleCorrectionAndCallbackWork() {
            calls += "cancel"
        }

        override fun detachReviewPanelSafely(): ReviewPanelDetachOutcome {
            calls += "detach"
            return detachOutcomes.removeFirstOrNull() ?: ReviewPanelDetachOutcome.REMOVED
        }

        override fun terminallyDetachReviewSurface(onDetached: () -> Unit) {
            calls += "terminal-detach"
            if (terminalDetachOutcome == ReviewPanelDetachOutcome.STILL_ATTACHED) {
                terminalDetachConfirmation = onDetached
            } else {
                ownsAttachedSurface = false
                onDetached()
            }
        }

        fun confirmTerminalDetach() {
            ownsAttachedSurface = false
            terminalDetachConfirmation?.invoke()
            terminalDetachConfirmation = null
        }

        override fun scrubTransientTextHolders() {
            calls += "scrub"
        }

        override fun reacquireFocusedTarget(mode: ReviewTargetReacquisition): ReviewTarget {
            calls += when (mode) {
                ReviewTargetReacquisition.AdoptCurrent -> "reacquire:any"
                is ReviewTargetReacquisition.RestoreExact -> "reacquire:${mode.targetId.value}"
            }
            return reacquired
        }

        override fun restoreKeyboard(targetId: ReviewTargetId) {
            calls += "keyboard:${targetId.value}"
        }

        override fun recordLifecycleDiagnostic(diagnostic: String) {
            diagnostics += diagnostic
        }
    }

    companion object {
        private val TARGET_ID = ReviewTargetId("editor")
    }
}
