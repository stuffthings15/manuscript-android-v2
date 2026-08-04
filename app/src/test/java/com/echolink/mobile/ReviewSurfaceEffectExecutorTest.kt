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
    fun reconnectReacquiresOneEligibleFieldAndShowsOneTrigger() {
        val fixture = hiddenFixture()

        fixture.executor.dispatch(ReviewSurfaceEvent.ServiceReconnected)
        fixture.executor.dispatch(ReviewSurfaceEvent.ServiceReconnected)

        assertEquals(listOf("reacquire:any"), fixture.actions.calls)
        assertEquals(ReviewSurfaceState.TriggerVisible(TARGET_ID), fixture.controller.state)
    }

    @Test
    fun closeScrubsBeforeAnyRestorationWork() {
        val fixture = openFixture()

        fixture.executor.dispatch(ReviewSurfaceEvent.CloseRequested)

        assertTrue(fixture.actions.calls.indexOf("scrub") < fixture.actions.calls.indexOf("reacquire:editor"))
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
        var reacquired = ReviewTarget(TARGET_ID, ReviewTargetEligibility.ELIGIBLE)

        override fun cancelStaleCorrectionAndCallbackWork() {
            calls += "cancel"
        }

        override fun detachReviewPanelSafely() {
            calls += "detach"
        }

        override fun scrubTransientTextHolders() {
            calls += "scrub"
        }

        override fun reacquireFocusedTarget(expectedTargetId: ReviewTargetId?): ReviewTarget {
            calls += "reacquire:${expectedTargetId?.value ?: "any"}"
            return reacquired
        }

        override fun restoreKeyboard(targetId: ReviewTargetId) {
            calls += "keyboard:${targetId.value}"
        }
    }

    companion object {
        private val TARGET_ID = ReviewTargetId("editor")
    }
}
