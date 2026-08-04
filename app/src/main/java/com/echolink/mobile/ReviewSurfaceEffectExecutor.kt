package com.echolink.mobile

interface ReviewSurfaceEffectActions {
    fun cancelStaleCorrectionAndCallbackWork()
    fun detachReviewPanelSafely()
    fun scrubTransientTextHolders()
    fun reacquireFocusedTarget(expectedTargetId: ReviewTargetId?): ReviewTarget
    fun restoreKeyboard(targetId: ReviewTargetId)
    fun renderReviewSurfaceState(state: ReviewSurfaceState) = Unit
}

/**
 * Runs lifecycle effects in controller order. Events raised by an effect are
 * deferred until the full effect batch has finished, so close always cancels,
 * detaches, and scrubs before focus or keyboard restoration can start.
 */
class ReviewSurfaceEffectExecutor(
    private val controller: ReviewSurfaceController,
    private val actions: ReviewSurfaceEffectActions,
) {
    private var callbackGeneration = 0L
    private var dispatching = false
    private val pendingEvents = ArrayDeque<ReviewSurfaceEvent>()

    fun dispatch(event: ReviewSurfaceEvent) {
        if (
            event == ReviewSurfaceEvent.ServiceReconnected &&
            controller.state is ReviewSurfaceState.TriggerVisible
        ) {
            return
        }
        pendingEvents.addLast(event)
        drainEvents()
    }

    fun currentCallbackToken(): Long = callbackGeneration

    fun isCurrentCallback(token: Long): Boolean =
        token == callbackGeneration && controller.state != ReviewSurfaceState.Destroyed

    private fun drainEvents() {
        if (dispatching) return
        dispatching = true
        try {
            while (pendingEvents.isNotEmpty()) {
                val event = pendingEvents.removeFirst()
                val previousState = controller.state
                val effects = controller.dispatch(event)
                if (controller.state != previousState) {
                    actions.renderReviewSurfaceState(controller.state)
                }
                runEffects(effects)
            }
        } finally {
            dispatching = false
        }
    }

    private fun runEffects(effects: List<ReviewSurfaceEffect>) {
        val raisedEvents = ArrayDeque<ReviewSurfaceEvent>()
        var reacquiredTarget: ReviewTarget? = null
        effects.forEach { effect ->
            when (effect) {
                ReviewSurfaceEffect.CancelStaleCorrectionAndCallbackWork -> {
                    callbackGeneration += 1L
                    actions.cancelStaleCorrectionAndCallbackWork()
                }
                ReviewSurfaceEffect.DetachReviewPanelSafely -> {
                    actions.detachReviewPanelSafely()
                    raisedEvents.addLast(ReviewSurfaceEvent.PanelDetached)
                }
                ReviewSurfaceEffect.ScrubTransientTextHolders ->
                    actions.scrubTransientTextHolders()
                is ReviewSurfaceEffect.ReacquireFocusedTarget -> {
                    val target = actions.reacquireFocusedTarget(effect.expectedTargetId)
                    reacquiredTarget = target
                    raisedEvents.addLast(
                        if (controller.state is ReviewSurfaceState.RestoringFocus) {
                            if (target.canRestore) {
                                ReviewSurfaceEvent.RestorationCompleted(target)
                            } else {
                                ReviewSurfaceEvent.RestorationFailed
                            }
                        } else {
                            ReviewSurfaceEvent.FocusedTargetAvailable(target)
                        },
                    )
                }
                is ReviewSurfaceEffect.RestoreKeyboard -> {
                    val target = reacquiredTarget
                    if (target?.canRestore == true && target.id == effect.targetId) {
                        actions.restoreKeyboard(effect.targetId)
                    }
                }
            }
        }
        pendingEvents.addAll(raisedEvents)
    }
}
