package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

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
                ReviewSurfaceEffect.ReacquireFocusedTarget(
                    ReviewTargetReacquisition.RestoreExact(targetId),
                ),
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
    fun reconnectIsUnrestrictedButCloseRestorationRequiresOriginalTarget() {
        val controller = ReviewSurfaceController()
        controller.dispatch(ReviewSurfaceEvent.FocusedTargetAvailable(eligibleTarget))

        assertEquals(
            listOf(
                ReviewSurfaceEffect.ReacquireFocusedTarget(ReviewTargetReacquisition.AdoptCurrent),
            ),
            controller.dispatch(ReviewSurfaceEvent.ServiceReconnected),
        )
        controller.dispatch(ReviewSurfaceEvent.FocusedTargetAvailable(eligibleTarget))
        controller.dispatch(ReviewSurfaceEvent.OpenRequested)
        controller.dispatch(ReviewSurfaceEvent.ReviewAttached)
        controller.dispatch(ReviewSurfaceEvent.CloseRequested)

        assertEquals(
            listOf(
                ReviewSurfaceEffect.ReacquireFocusedTarget(
                    ReviewTargetReacquisition.RestoreExact(targetId),
                ),
                ReviewSurfaceEffect.RestoreKeyboard(targetId),
            ),
            controller.dispatch(ReviewSurfaceEvent.PanelDetached),
        )
    }

    @Test
    fun reconnectInvalidatesAttachedOrRestoringSurfacesBeforeAnyAdoption() {
        listOf(ReviewSurfaceState.Opening(targetId), ReviewSurfaceState.ReviewOpen(targetId)).forEach { state ->
            val transition = ReviewSurfaceController.reduce(state, ReviewSurfaceEvent.ServiceReconnected)

            assertTrue(transition.state is ReviewSurfaceState.Closing)
            assertEquals(3, transition.effects.size)
            assertTrue(transition.effects.none { it is ReviewSurfaceEffect.ReacquireFocusedTarget })
        }

        val closing = ReviewSurfaceController.reduce(
            ReviewSurfaceState.Closing(eligibleTarget),
            ReviewSurfaceEvent.ServiceReconnected,
        )
        val restoring = ReviewSurfaceController.reduce(
            ReviewSurfaceState.RestoringFocus(targetId),
            ReviewSurfaceEvent.ServiceReconnected,
        )

        assertEquals(ReviewSurfaceState.Closing(eligibleTarget), closing.state)
        assertTrue(closing.effects.isEmpty())
        assertEquals(ReviewSurfaceState.Hidden, restoring.state)
        assertTrue(restoring.effects.isEmpty())
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
            listOf(
                ReviewSurfaceEffect.ReacquireFocusedTarget(ReviewTargetReacquisition.AdoptCurrent),
            ),
            controller.dispatch(ReviewSurfaceEvent.ServiceReconnected),
        )
        assertEquals(ReviewSurfaceState.Hidden, controller.state)

        controller.dispatch(ReviewSurfaceEvent.Destroy)
        assertTrue(controller.dispatch(ReviewSurfaceEvent.ServiceReconnected).isEmpty())
        assertEquals(ReviewSurfaceState.Destroyed, controller.state)
    }

    @Test
    fun reconnectIsIdempotentUntilFocusedTargetResolution() {
        val controller = ReviewSurfaceController()

        val first = controller.dispatch(ReviewSurfaceEvent.ServiceReconnected)
        val repeated = controller.dispatch(ReviewSurfaceEvent.ServiceReconnected)

        assertEquals(
            listOf(ReviewSurfaceEffect.ReacquireFocusedTarget(ReviewTargetReacquisition.AdoptCurrent)),
            first,
        )
        assertTrue(repeated.isEmpty())

        controller.dispatch(ReviewSurfaceEvent.FocusedTargetAvailable(eligibleTarget))
        assertEquals(
            listOf(
                ReviewSurfaceEffect.ReacquireFocusedTarget(ReviewTargetReacquisition.AdoptCurrent),
            ),
            controller.dispatch(ReviewSurfaceEvent.ServiceReconnected),
        )
    }

    @Test
    fun reconnectValidationWhileTriggerVisibleAppliesEveryResult() {
        val newTargetId = ReviewTargetId("field-22")
        val cases = listOf(
            ReviewTarget(targetId, ReviewTargetEligibility.ELIGIBLE) to
                ReviewSurfaceState.TriggerVisible(targetId),
            ReviewTarget(newTargetId, ReviewTargetEligibility.ELIGIBLE) to
                ReviewSurfaceState.TriggerVisible(newTargetId),
            ReviewTarget(null, ReviewTargetEligibility.INELIGIBLE) to ReviewSurfaceState.Hidden,
            ReviewTarget(null, ReviewTargetEligibility.MISSING) to ReviewSurfaceState.Hidden,
            ReviewTarget(null, ReviewTargetEligibility.PASSWORD) to ReviewSurfaceState.Hidden,
        )

        cases.forEach { (validation, expectedState) ->
            val controller = ReviewSurfaceController()
            controller.dispatch(ReviewSurfaceEvent.FocusedTargetAvailable(eligibleTarget))

            assertEquals(
                listOf(
                    ReviewSurfaceEffect.ReacquireFocusedTarget(ReviewTargetReacquisition.AdoptCurrent),
                ),
                controller.dispatch(ReviewSurfaceEvent.ServiceReconnected),
            )
            controller.dispatch(ReviewSurfaceEvent.FocusedTargetAvailable(validation))

            assertEquals("validation=$validation", expectedState, controller.state)
        }
    }

    @Test
    fun repeatedReconnectsProcessEachCompletedValidation() {
        val newTargetId = ReviewTargetId("field-22")
        val controller = ReviewSurfaceController()
        controller.dispatch(ReviewSurfaceEvent.FocusedTargetAvailable(eligibleTarget))

        repeat(2) {
            assertEquals(
                listOf(
                    ReviewSurfaceEffect.ReacquireFocusedTarget(ReviewTargetReacquisition.AdoptCurrent),
                ),
                controller.dispatch(ReviewSurfaceEvent.ServiceReconnected),
            )
            controller.dispatch(
                ReviewSurfaceEvent.FocusedTargetAvailable(
                    ReviewTarget(newTargetId, ReviewTargetEligibility.ELIGIBLE),
                ),
            )
            assertEquals(ReviewSurfaceState.TriggerVisible(newTargetId), controller.state)
        }
    }

    @Test
    fun reconnectPendingClearsForEveryResolutionAndInvalidationEvent() {
        val resolutionEvents = listOf(
            ReviewSurfaceEvent.FocusedTargetAvailable(eligibleTarget),
            ReviewSurfaceEvent.RestorationCompleted(eligibleTarget),
            ReviewSurfaceEvent.RestorationFailed,
            ReviewSurfaceEvent.TargetLost,
            ReviewSurfaceEvent.PasswordFocused,
            ReviewSurfaceEvent.ApplicationSwitched,
        )

        resolutionEvents.forEach { resolution ->
            val controller = ReviewSurfaceController()
            controller.dispatch(ReviewSurfaceEvent.ServiceReconnected)
            assertTrue(controller.dispatch(ReviewSurfaceEvent.ServiceReconnected).isEmpty())

            controller.dispatch(resolution)

            assertEquals(
                "resolution=$resolution",
                1,
                controller.dispatch(ReviewSurfaceEvent.ServiceReconnected).size,
            )
        }
    }

    @Test
    fun reconnectCancelsPendingExactRestorationBeforeLaterAdoption() {
        val controller = openController()
        controller.dispatch(ReviewSurfaceEvent.CloseRequested)
        assertEquals(2, controller.dispatch(ReviewSurfaceEvent.PanelDetached).size)

        assertTrue(controller.dispatch(ReviewSurfaceEvent.ServiceReconnected).isEmpty())
        assertEquals(ReviewSurfaceState.Hidden, controller.state)

        assertEquals(
            listOf(
                ReviewSurfaceEffect.ReacquireFocusedTarget(ReviewTargetReacquisition.AdoptCurrent),
            ),
            controller.dispatch(ReviewSurfaceEvent.ServiceReconnected),
        )
    }

    @Test
    fun everyConcreteContractTypeRejectsPlaintextPayloadFields() {
        val rootTypes = listOf(
            ReviewTarget::class.java,
            ReviewTargetId::class.java,
            ReviewSurfaceState::class.java,
            ReviewSurfaceEvent::class.java,
            ReviewSurfaceEffect::class.java,
            ReviewTargetReacquisition::class.java,
        )
        val concreteTypes = rootTypes.flatMap(::concreteContractTypes).distinct()
        val concreteNames = concreteTypes.map(Class<*>::getSimpleName).toSet()
        val forbiddenNames = Regex("(?i)(editor|correction|snapshot|plain.?text|content|draft)")
        val opaqueIdentifierFields = setOf(
            "ReviewTargetId.value",
            "ReviewTarget.id",
            "TriggerVisible.targetId",
            "Opening.targetId",
            "ReviewOpen.targetId",
            "RestoringFocus.targetId",
            "RestoreExact.targetId",
            "RestoreKeyboard.targetId",
        )

        assertEquals(
            setOf(
                "ReviewTarget",
                "ReviewTargetId",
                "Hidden",
                "TriggerVisible",
                "Opening",
                "ReviewOpen",
                "Closing",
                "RestoringFocus",
                "Destroyed",
                "FocusedTargetAvailable",
                "OpenRequested",
                "ReviewAttached",
                "CloseRequested",
                "StaleWorkCancelled",
                "PanelDetached",
                "RestorationCompleted",
                "RestorationFailed",
                "TargetLost",
                "PasswordFocused",
                "ApplicationSwitched",
                "ServiceReconnected",
                "Destroy",
                "CancelStaleCorrectionAndCallbackWork",
                "DetachReviewPanelSafely",
                "ScrubTransientTextHolders",
                "ReacquireFocusedTarget",
                "RestoreKeyboard",
                "AdoptCurrent",
                "RestoreExact",
            ),
            concreteNames,
        )

        concreteTypes.flatMap { type ->
            type.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { type to it }
        }.forEach { (owner, field) ->
            val fieldLabel = "${owner.simpleName}.${field.name}"
            assertFalse("Forbidden field $fieldLabel", forbiddenNames.containsMatchIn(field.name))
            if (field.type == String::class.java || CharSequence::class.java.isAssignableFrom(field.type)) {
                assertTrue(
                    "Unexpected string payload $fieldLabel",
                    fieldLabel in opaqueIdentifierFields,
                )
            }
        }
    }

    private fun concreteContractTypes(root: Class<*>): List<Class<*>> {
        if (!root.isInterface && !Modifier.isAbstract(root.modifiers)) return listOf(root)
        return root.declaredClasses
            .filterNot { it.isInterface || Modifier.isAbstract(it.modifiers) }
            .sortedBy(Class<*>::getSimpleName)
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
