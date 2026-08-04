package com.echolink.mobile

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewSurfaceLifecycleDeviceTest {
    @Test
    fun closeDetachesWithinThreeHundredMillisecondsAndRestoresWithinFiveHundred() {
        val fixture = openFixture()
        val started = SystemClock.elapsedRealtime()

        fixture.executor.dispatch(ReviewSurfaceEvent.CloseRequested)

        assertTrue(fixture.actions.detachedAt - started <= 300L)
        assertTrue(fixture.actions.triggerVisibleAt - started <= 500L)
        assertEquals(ReviewSurfaceState.TriggerVisible(TARGET_ID), fixture.controller.state)
    }

    @Test
    fun passwordEventWhileOpenScrubsWithoutKeyboardOrTriggerRestoration() {
        val fixture = openFixture()

        fixture.executor.dispatch(ReviewSurfaceEvent.PasswordFocused)

        assertEquals(1, fixture.actions.scrubCount)
        assertEquals(0, fixture.actions.keyboardRestoreCount)
        assertEquals(ReviewSurfaceState.Hidden, fixture.controller.state)
    }

    @Test
    fun destroyRejectsLateCallbackAndDetachesOnlyOnce() {
        val fixture = openFixture()
        val callbackToken = fixture.executor.currentCallbackToken()

        fixture.executor.dispatch(ReviewSurfaceEvent.Destroy)
        fixture.executor.dispatch(ReviewSurfaceEvent.Destroy)

        assertFalse(fixture.executor.isCurrentCallback(callbackToken))
        assertEquals(1, fixture.actions.detachCount)
        assertEquals(ReviewSurfaceState.Destroyed, fixture.controller.state)
    }

    private fun openFixture(): Fixture {
        val controller = ReviewSurfaceController()
        val actions = TimingActions()
        val executor = ReviewSurfaceEffectExecutor(controller, actions)
        executor.dispatch(
            ReviewSurfaceEvent.FocusedTargetAvailable(
                ReviewTarget(TARGET_ID, ReviewTargetEligibility.ELIGIBLE),
            ),
        )
        executor.dispatch(ReviewSurfaceEvent.OpenRequested)
        executor.dispatch(ReviewSurfaceEvent.ReviewAttached)
        actions.reset()
        return Fixture(controller, actions, executor)
    }

    private data class Fixture(
        val controller: ReviewSurfaceController,
        val actions: TimingActions,
        val executor: ReviewSurfaceEffectExecutor,
    )

    private class TimingActions : ReviewSurfaceEffectActions {
        var detachedAt = 0L
        var triggerVisibleAt = 0L
        var detachCount = 0
        var scrubCount = 0
        var keyboardRestoreCount = 0

        override fun cancelStaleCorrectionAndCallbackWork() = Unit

        override fun detachReviewPanelSafely() {
            detachCount += 1
            detachedAt = SystemClock.elapsedRealtime()
        }

        override fun scrubTransientTextHolders() {
            scrubCount += 1
        }

        override fun reacquireFocusedTarget(expectedTargetId: ReviewTargetId?): ReviewTarget =
            ReviewTarget(TARGET_ID, ReviewTargetEligibility.ELIGIBLE)

        override fun restoreKeyboard(targetId: ReviewTargetId) {
            keyboardRestoreCount += 1
        }

        override fun renderReviewSurfaceState(state: ReviewSurfaceState) {
            if (state is ReviewSurfaceState.TriggerVisible) {
                triggerVisibleAt = SystemClock.elapsedRealtime()
            }
        }

        fun reset() {
            detachedAt = 0L
            triggerVisibleAt = 0L
            detachCount = 0
            scrubCount = 0
            keyboardRestoreCount = 0
        }
    }

    companion object {
        private val TARGET_ID = ReviewTargetId("device-editor")
    }
}
