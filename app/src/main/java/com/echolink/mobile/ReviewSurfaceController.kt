package com.echolink.mobile

@JvmInline
value class ReviewTargetId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

enum class ReviewTargetEligibility {
    ELIGIBLE,
    INELIGIBLE,
    PASSWORD,
    MISSING,
    DETACHED,
    SWITCHED_APPLICATION,
}

data class ReviewTarget(
    val id: ReviewTargetId?,
    val eligibility: ReviewTargetEligibility,
) {
    val canRestore: Boolean
        get() = id != null && eligibility == ReviewTargetEligibility.ELIGIBLE
}

sealed interface ReviewSurfaceState {
    data object Hidden : ReviewSurfaceState
    data class TriggerVisible(val targetId: ReviewTargetId) : ReviewSurfaceState
    data class Opening(val targetId: ReviewTargetId) : ReviewSurfaceState
    data class ReviewOpen(val targetId: ReviewTargetId) : ReviewSurfaceState
    data class Closing(val retainedTarget: ReviewTarget) : ReviewSurfaceState
    data class RestoringFocus(val targetId: ReviewTargetId) : ReviewSurfaceState
    data object Destroyed : ReviewSurfaceState
}

sealed interface ReviewSurfaceEvent {
    data class FocusedTargetAvailable(val target: ReviewTarget) : ReviewSurfaceEvent
    data object OpenRequested : ReviewSurfaceEvent
    data object ReviewAttached : ReviewSurfaceEvent
    data object CloseRequested : ReviewSurfaceEvent
    data object StaleWorkCancelled : ReviewSurfaceEvent
    data object PanelDetached : ReviewSurfaceEvent
    data class RestorationCompleted(val target: ReviewTarget) : ReviewSurfaceEvent
    data object RestorationFailed : ReviewSurfaceEvent
    data object TargetLost : ReviewSurfaceEvent
    data object PasswordFocused : ReviewSurfaceEvent
    data object ApplicationSwitched : ReviewSurfaceEvent
    data object ServiceReconnected : ReviewSurfaceEvent
    data object Destroy : ReviewSurfaceEvent
}

sealed interface ReviewSurfaceEffect {
    data object CancelStaleCorrectionAndCallbackWork : ReviewSurfaceEffect
    data object DetachReviewPanelSafely : ReviewSurfaceEffect
    data object ScrubTransientTextHolders : ReviewSurfaceEffect
    data class ReacquireFocusedTarget(val mode: ReviewTargetReacquisition) : ReviewSurfaceEffect
    data class RestoreKeyboard(val targetId: ReviewTargetId) : ReviewSurfaceEffect
}

sealed interface ReviewTargetReacquisition {
    data object AdoptCurrent : ReviewTargetReacquisition
    data class RestoreExact(val targetId: ReviewTargetId) : ReviewTargetReacquisition
}

data class ReviewSurfaceTransition(
    val state: ReviewSurfaceState,
    val effects: List<ReviewSurfaceEffect> = emptyList(),
)

class ReviewSurfaceController(
    initialState: ReviewSurfaceState = ReviewSurfaceState.Hidden,
) {
    var state: ReviewSurfaceState = initialState
        private set
    private var reacquisitionPending = false

    fun dispatch(event: ReviewSurfaceEvent): List<ReviewSurfaceEffect> {
        if (event.resolvesPendingReacquisition()) {
            reacquisitionPending = false
        }
        if (event == ReviewSurfaceEvent.ServiceReconnected && reacquisitionPending) {
            if (state == ReviewSurfaceState.Hidden || state is ReviewSurfaceState.TriggerVisible) {
                return emptyList()
            }
            reacquisitionPending = false
        }
        val transition = reduce(state, event)
        state = transition.state
        if (transition.effects.any { it is ReviewSurfaceEffect.ReacquireFocusedTarget }) {
            reacquisitionPending = true
        }
        if (state == ReviewSurfaceState.Destroyed) {
            reacquisitionPending = false
        }
        return transition.effects
    }

    private fun ReviewSurfaceEvent.resolvesPendingReacquisition(): Boolean = when (this) {
        is ReviewSurfaceEvent.FocusedTargetAvailable,
        is ReviewSurfaceEvent.RestorationCompleted,
        ReviewSurfaceEvent.RestorationFailed,
        ReviewSurfaceEvent.TargetLost,
        ReviewSurfaceEvent.PasswordFocused,
        ReviewSurfaceEvent.ApplicationSwitched,
        ReviewSurfaceEvent.Destroy,
        -> true
        else -> false
    }

    companion object {
        private val closeEffects = listOf(
            ReviewSurfaceEffect.CancelStaleCorrectionAndCallbackWork,
            ReviewSurfaceEffect.DetachReviewPanelSafely,
            ReviewSurfaceEffect.ScrubTransientTextHolders,
        )

        fun reduce(
            state: ReviewSurfaceState,
            event: ReviewSurfaceEvent,
        ): ReviewSurfaceTransition {
            if (state == ReviewSurfaceState.Destroyed) {
                return ReviewSurfaceTransition(ReviewSurfaceState.Destroyed)
            }
            if (event == ReviewSurfaceEvent.Destroy) {
                return ReviewSurfaceTransition(
                    state = ReviewSurfaceState.Destroyed,
                    effects = if (state.hasAttachedReview()) closeEffects else emptyList(),
                )
            }

            return when (event) {
                is ReviewSurfaceEvent.FocusedTargetAvailable -> focusedTarget(state, event.target)
                ReviewSurfaceEvent.OpenRequested -> open(state)
                ReviewSurfaceEvent.ReviewAttached -> attached(state)
                ReviewSurfaceEvent.CloseRequested -> close(state)
                ReviewSurfaceEvent.StaleWorkCancelled -> ReviewSurfaceTransition(state)
                ReviewSurfaceEvent.PanelDetached -> detached(state)
                is ReviewSurfaceEvent.RestorationCompleted -> restored(state, event.target)
                ReviewSurfaceEvent.RestorationFailed -> restorationFailed(state)
                ReviewSurfaceEvent.TargetLost -> invalidate(state, ReviewTargetEligibility.MISSING)
                ReviewSurfaceEvent.PasswordFocused -> invalidate(state, ReviewTargetEligibility.PASSWORD)
                ReviewSurfaceEvent.ApplicationSwitched -> invalidate(
                    state,
                    ReviewTargetEligibility.SWITCHED_APPLICATION,
                )
                ReviewSurfaceEvent.ServiceReconnected -> reconnect(state)
                ReviewSurfaceEvent.Destroy -> error("Handled before transition dispatch")
            }
        }

        private fun focusedTarget(
            state: ReviewSurfaceState,
            target: ReviewTarget,
        ): ReviewSurfaceTransition = when {
            (state == ReviewSurfaceState.Hidden || state is ReviewSurfaceState.TriggerVisible) &&
                target.canRestore -> ReviewSurfaceTransition(
                ReviewSurfaceState.TriggerVisible(requireNotNull(target.id)),
            )
            state == ReviewSurfaceState.Hidden || state is ReviewSurfaceState.TriggerVisible ->
                ReviewSurfaceTransition(ReviewSurfaceState.Hidden)
            else -> ReviewSurfaceTransition(state)
        }

        private fun open(state: ReviewSurfaceState): ReviewSurfaceTransition = when (state) {
            is ReviewSurfaceState.TriggerVisible -> ReviewSurfaceTransition(
                ReviewSurfaceState.Opening(state.targetId),
            )
            else -> ReviewSurfaceTransition(state)
        }

        private fun attached(state: ReviewSurfaceState): ReviewSurfaceTransition = when (state) {
            is ReviewSurfaceState.Opening -> ReviewSurfaceTransition(
                ReviewSurfaceState.ReviewOpen(state.targetId),
            )
            else -> ReviewSurfaceTransition(state)
        }

        private fun close(state: ReviewSurfaceState): ReviewSurfaceTransition = when (state) {
            is ReviewSurfaceState.Opening -> beginClose(state.targetId)
            is ReviewSurfaceState.ReviewOpen -> beginClose(state.targetId)
            else -> ReviewSurfaceTransition(state)
        }

        private fun beginClose(
            targetId: ReviewTargetId,
            eligibility: ReviewTargetEligibility = ReviewTargetEligibility.ELIGIBLE,
        ): ReviewSurfaceTransition = ReviewSurfaceTransition(
            state = ReviewSurfaceState.Closing(ReviewTarget(targetId, eligibility)),
            effects = closeEffects,
        )

        private fun detached(state: ReviewSurfaceState): ReviewSurfaceTransition = when (state) {
            is ReviewSurfaceState.Closing -> {
                val target = state.retainedTarget
                if (target.canRestore) {
                    val targetId = requireNotNull(target.id)
                    ReviewSurfaceTransition(
                        state = ReviewSurfaceState.RestoringFocus(targetId),
                        effects = listOf(
                            ReviewSurfaceEffect.ReacquireFocusedTarget(
                                ReviewTargetReacquisition.RestoreExact(targetId),
                            ),
                            ReviewSurfaceEffect.RestoreKeyboard(targetId),
                        ),
                    )
                } else {
                    ReviewSurfaceTransition(ReviewSurfaceState.Hidden)
                }
            }
            else -> ReviewSurfaceTransition(state)
        }

        private fun restored(
            state: ReviewSurfaceState,
            target: ReviewTarget,
        ): ReviewSurfaceTransition = when {
            state is ReviewSurfaceState.RestoringFocus &&
                target.canRestore &&
                target.id == state.targetId -> ReviewSurfaceTransition(
                ReviewSurfaceState.TriggerVisible(state.targetId),
            )
            state is ReviewSurfaceState.RestoringFocus -> ReviewSurfaceTransition(
                ReviewSurfaceState.Hidden,
            )
            else -> ReviewSurfaceTransition(state)
        }

        private fun restorationFailed(state: ReviewSurfaceState): ReviewSurfaceTransition =
            if (state is ReviewSurfaceState.RestoringFocus) {
                ReviewSurfaceTransition(ReviewSurfaceState.Hidden)
            } else {
                ReviewSurfaceTransition(state)
            }

        private fun invalidate(
            state: ReviewSurfaceState,
            eligibility: ReviewTargetEligibility,
        ): ReviewSurfaceTransition = when (state) {
            is ReviewSurfaceState.TriggerVisible -> ReviewSurfaceTransition(ReviewSurfaceState.Hidden)
            is ReviewSurfaceState.Opening -> beginClose(state.targetId, eligibility)
            is ReviewSurfaceState.ReviewOpen -> beginClose(state.targetId, eligibility)
            is ReviewSurfaceState.Closing -> ReviewSurfaceTransition(
                ReviewSurfaceState.Closing(
                    state.retainedTarget.copy(eligibility = eligibility),
                ),
            )
            is ReviewSurfaceState.RestoringFocus -> ReviewSurfaceTransition(ReviewSurfaceState.Hidden)
            else -> ReviewSurfaceTransition(state)
        }

        private fun reconnect(state: ReviewSurfaceState): ReviewSurfaceTransition = when (state) {
            ReviewSurfaceState.Hidden,
            is ReviewSurfaceState.TriggerVisible,
            -> ReviewSurfaceTransition(
                state = state,
                effects = listOf(
                    ReviewSurfaceEffect.ReacquireFocusedTarget(
                        ReviewTargetReacquisition.AdoptCurrent,
                    ),
                ),
            )
            is ReviewSurfaceState.Opening -> beginClose(
                state.targetId,
                ReviewTargetEligibility.MISSING,
            )
            is ReviewSurfaceState.ReviewOpen -> beginClose(
                state.targetId,
                ReviewTargetEligibility.MISSING,
            )
            is ReviewSurfaceState.Closing -> ReviewSurfaceTransition(state)
            is ReviewSurfaceState.RestoringFocus -> ReviewSurfaceTransition(ReviewSurfaceState.Hidden)
            ReviewSurfaceState.Destroyed -> ReviewSurfaceTransition(ReviewSurfaceState.Destroyed)
        }

        private fun ReviewSurfaceState.hasAttachedReview(): Boolean =
            this is ReviewSurfaceState.Opening || this is ReviewSurfaceState.ReviewOpen

    }
}
