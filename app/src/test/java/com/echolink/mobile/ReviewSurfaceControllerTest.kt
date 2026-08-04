package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSurfaceControllerTest {
    private val targetId = ReviewTargetId("field-17")
    private val eligibleTarget = ReviewTarget(targetId, ReviewTargetEligibility.ELIGIBLE)

    @Test
    fun closeEmitsCleanupExactlyOnceInRequiredOrder() {
        val controller = openController()

        val first = controller.dispatch(ReviewSurfaceEvent.CloseRequested)
        val repeated = controller.dispatch(ReviewSurfaceEvent.CloseRequested)
        val cancellation = controller.dispatch(ReviewSurfaceEvent.StaleWorkCancelled)

        assertEquals(
            listOf(
                ReviewSurfaceEffect.CancelStaleCorrectionAndCallbackWork,
                ReviewSurfaceEffect.DetachReviewPanelSafely,
                ReviewSurfaceEffect.ScrubTransientTextHolders,
            ),
            first,
        )
        assertEquals(ReviewSurfaceState.Closing(eligibleTarget), controller.state)
        assertTrue(repeated.isEmpty())
        assertTrue(cancellation.isEmpty())
    }

    @Test
    fun eligibleDetachReacquiresBeforeKeyboardThenRestoresTrigger() {
        val controller = openController()
        controller.dispatch(ReviewSurfaceEvent.CloseRequested)

        val effects = controller.dispatch(ReviewSurfaceEvent.PanelDetached)

        assertEquals(
            listOf(
                ReviewSurfaceEffect.ReacquireFocusedTarget(targetId),
                ReviewSurfaceEffect.RestoreKeyboard(targetId),
            ),
            effects,
        )
        assertEquals(ReviewSurfaceState.RestoringFocus(targetId), controller.state)
        assertTrue(controller.dispatch(ReviewSurfaceEvent.PanelDetached).isEmpty())

        controller.dispatch(ReviewSurfaceEvent.RestorationCompleted(eligibleTarget))
        assertEquals(ReviewSurfaceState.TriggerVisible(targetId), controller.state)
    }

    @Test
    fun everyIneligibleRestorationEndsHidden() {
        val ineligibleReasons = ReviewTargetEligibility.entries - ReviewTargetEligibility.ELIGIBLE

        ineligibleReasons.forEach { eligibility ->
            val controller = openController()
            controller.dispatch(ReviewSurfaceEvent.CloseRequested)
            controller.dispatch(ReviewSurfaceEvent.PanelDetached)

            val restoredTarget = ReviewTarget(
                id = targetId.takeUnless { eligibility == ReviewTargetEligibility.MISSING },
                eligibility = eligibility,
            )
            controller.dispatch(ReviewSurfaceEvent.RestorationCompleted(restoredTarget))

            assertEquals("eligibility=$eligibility", ReviewSurfaceState.Hidden, controller.state)
        }
    }

    @Test
    fun lossPasswordAndApplicationSwitchCloseWithoutRestoration() {
        val invalidatingEvents = listOf(
            ReviewSurfaceEvent.TargetLost,
            ReviewSurfaceEvent.PasswordFocused,
            ReviewSurfaceEvent.ApplicationSwitched,
        )

        invalidatingEvents.forEach { event ->
            val controller = openController()

            val first = controller.dispatch(event)
            val repeated = controller.dispatch(event)

            assertEquals(3, first.size)
            assertTrue(repeated.isEmpty())
            controller.dispatch(ReviewSurfaceEvent.PanelDetached)
            assertEquals(ReviewSurfaceState.Hidden, controller.state)
        }
    }

    @Test
    fun destroyIsTerminalAndNeverDuplicatesCloseEffects() {
        val controller = openController()

        val first = controller.dispatch(ReviewSurfaceEvent.Destroy)
        val repeated = controller.dispatch(ReviewSurfaceEvent.Destroy)
        val reopen = controller.dispatch(ReviewSurfaceEvent.FocusedTargetAvailable(eligibleTarget))

        assertEquals(3, first.size)
        assertTrue(repeated.isEmpty())
        assertTrue(reopen.isEmpty())
        assertEquals(ReviewSurfaceState.Destroyed, controller.state)
    }

    @Test
    fun destroyDuringClosingDoesNotRepeatCleanup() {
        val controller = openController()
        val closeEffects = controller.dispatch(ReviewSurfaceEvent.CloseRequested)

        val destroyEffects = controller.dispatch(ReviewSurfaceEvent.Destroy)

        assertEquals(3, closeEffects.size)
        assertTrue(destroyEffects.isEmpty())
        assertEquals(ReviewSurfaceState.Destroyed, controller.state)
    }

    @Test
    fun reconnectRequestsFocusedTargetWithoutReopeningDestroyedController() {
        val controller = ReviewSurfaceController()

        assertEquals(
            listOf(ReviewSurfaceEffect.ReacquireFocusedTarget(null)),
            controller.dispatch(ReviewSurfaceEvent.ServiceReconnected),
        )
        assertEquals(ReviewSurfaceState.Hidden, controller.state)

        controller.dispatch(ReviewSurfaceEvent.Destroy)
        assertTrue(controller.dispatch(ReviewSurfaceEvent.ServiceReconnected).isEmpty())
        assertEquals(ReviewSurfaceState.Destroyed, controller.state)
    }

    @Test
    fun stateContractContainsIdentifiersAndEligibilityButNoTextPayloadFields() {
        val contractTypes = listOf(
            ReviewTarget::class.java,
            ReviewSurfaceState::class.java,
            ReviewSurfaceEvent::class.java,
            ReviewSurfaceEffect::class.java,
        )
        val forbiddenNames = Regex("(?i)(editor|correction|snapshot|plain.?text|content|draft)")

        contractTypes.flatMap { it.declaredFields.toList() }.forEach { field ->
            assertFalse("Forbidden field ${field.name}", forbiddenNames.containsMatchIn(field.name))
        }
    }

    private fun openController(): ReviewSurfaceController = ReviewSurfaceController().apply {
        dispatch(ReviewSurfaceEvent.FocusedTargetAvailable(eligibleTarget))
        assertEquals(ReviewSurfaceState.TriggerVisible(targetId), state)
        dispatch(ReviewSurfaceEvent.OpenRequested)
        assertEquals(ReviewSurfaceState.Opening(targetId), state)
        dispatch(ReviewSurfaceEvent.ReviewAttached)
        assertEquals(ReviewSurfaceState.ReviewOpen(targetId), state)
    }
}
